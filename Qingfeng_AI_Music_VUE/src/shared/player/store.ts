import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { NativeAudioPool } from './audio-cache'
export interface PlayableTrack { id: string; title: string; titleKey?: string; cover: string; audio: string; provenance?: 'SAMPLE' | 'IMPORTED' | 'GENERATED' }
export const usePlayer = defineStore('player', () => {
  const current=ref<PlayableTrack|null>(null),playing=ref(false),starting=ref(false),buffering=ref(false),time=ref(0),duration=ref(0),error=ref(''),queue=ref<PlayableTrack[]>([])
  const hasQueue=computed(()=>queue.value.length>1&&queue.value.some(track=>track.id===current.value?.id))
  const tracks=computed(()=>Object.freeze(queue.value.map(track=>Object.freeze({...track}))))
  const repeatOne=ref(false)
  function toggleRepeat(){repeatOne.value=!repeatOne.value}
  const seekVersion=ref(0),segment=ref<{track:string;start:number;end:number;loop:boolean}|null>(null)
  let audio:HTMLAudioElement|undefined,operation=0,waitingTimer:ReturnType<typeof setTimeout>|undefined,autoAdvance=true
  type SegmentState={track:string;start:number;end:number;loop:boolean}
  type RestoreState={time:number;paused:boolean;playbackRate:number;segment:SegmentState|null;playing:boolean;starting:boolean;buffering:boolean;duration:number;queue:PlayableTrack[];autoAdvance:boolean}
  // 签名过期兜底：以原始同源 URL 计数，池 key 恒为 track.audio；全过程不触碰 pool 的 Map。
  const autoRetries=new Map<string,number>()
  let reauthSequence=0,reauthPending=false,pendingRestore=false,restoreOperation=0
  let restoreTimer:ReturnType<typeof setTimeout>|undefined,seekGuardTimer:ReturnType<typeof setTimeout>|undefined
  let restoreSeekGuard=false,reauthCleanup:(()=>void)|null=null
  const same=(track:PlayableTrack)=>current.value?.id===track.id&&current.value?.audio===track.audio
  function stopWaiting(){clearTimeout(waitingTimer);buffering.value=false}
  function readDuration(){duration.value=audio&&Number.isFinite(audio.duration)?audio.duration:0}
  function waitForAudio(){
    stopWaiting();buffering.value=true
    waitingTimer=setTimeout(()=>{operation++;starting.value=false;audio?.pause();stopWaiting();error.value='播放等待时间较长，请点击重试。'},15000)
  }
  /** 302 后 currentSrc 是否含 auth_key 属方案 E-1 待实测；检测不到时 code 4 按非 CDN 终态处理。 */
  function cdnSigned(element:HTMLAudioElement){try{return element.currentSrc.includes('auth_key=')}catch{return false}}
  function retriable(code:number|null,element:HTMLAudioElement|undefined){
    if(code===2)return true
    if(code===4)return !!element&&cdnSigned(element)
    return false
  }
  function cancelReauth(){
    if(reauthCleanup){reauthCleanup();reauthCleanup=null}
    clearTimeout(restoreTimer);restoreTimer=undefined
    clearTimeout(seekGuardTimer);seekGuardTimer=undefined
    restoreSeekGuard=false;reauthPending=false;pendingRestore=false
  }
  function finishReauth(success:boolean){
    cancelReauth()
    if(success)return
    stopWaiting();starting.value=false;playing.value=false
    error.value='音频无法加载，链接可能已失效或文件已损坏。可点击重试。'
  }
  /** 一次性重新授权：重新请求原始同源 URL 拿新的 302，不调用 element.load()、不碰池。 */
  function reauthorize(fromUser=false):boolean{
    const element=audio,track=current.value
    if(!element||!track||reauthPending||pendingRestore)return false
    const code=element.error?.code??null
    if(!fromUser&&!retriable(code,element))return false
    const key=track.audio,used=autoRetries.get(key)??0
    if(fromUser)autoRetries.set(key,0)
    else{if(used>=1)return false;autoRetries.set(key,used+1)}
    const snapshot:RestoreState={
      time:Number.isFinite(element.currentTime)?element.currentTime:time.value,
      paused:element.paused,
      playbackRate:Number.isFinite(element.playbackRate)?element.playbackRate:1,
      segment:segment.value?{...segment.value}:null,
      playing:playing.value,starting:starting.value,buffering:buffering.value,
      duration:duration.value,queue:[...queue.value],autoAdvance
    }
    restoreOperation=operation
    reauthPending=true;pendingRestore=true
    error.value='';stopWaiting();playing.value=false;starting.value=!snapshot.paused
    const onReady=()=>{
      if(operation!==restoreOperation||element!==audio||!reauthPending){cancelReauth();return}
      restoreSeekGuard=true
      clearTimeout(seekGuardTimer)
      seekGuardTimer=setTimeout(()=>{restoreSeekGuard=false},1000)
      try{
        element.playbackRate=snapshot.playbackRate
        const d=Number.isFinite(element.duration)&&element.duration>0?element.duration:snapshot.duration
        const part=snapshot.segment
        let target=Math.min(Math.max(0,snapshot.time),d>0?d:Math.max(0,snapshot.time))
        if(part&&snapshot.time>=part.end){
          if(part.loop){target=Math.min(part.start,d>0?d:part.start);segment.value={...part}}
          else{segment.value=null;target=Math.min(part.end,d>0?d:part.end)}
        }else segment.value=part?{...part}:null
        element.currentTime=target;time.value=target
      }catch{/* 恢复失败不阻断播放；由 play() 或恢复超时给出终态 */}
      readDuration()
      seekVersion.value++
      pendingRestore=false;reauthPending=false
      clearTimeout(restoreTimer);restoreTimer=undefined
      if(reauthCleanup){reauthCleanup();reauthCleanup=null}
      starting.value=!snapshot.paused
      if(snapshot.paused){element.pause();stopWaiting();return}
      waitForAudio()
      void element.play().catch((cause:unknown)=>{
        if(operation!==restoreOperation||element!==audio)return
        starting.value=false;stopWaiting();playing.value=false
        error.value=cause instanceof Error&&cause.name==='NotAllowedError'?'浏览器暂停了播放，请点击播放继续。':'音频无法加载，链接可能已失效或文件已损坏。可点击重试。'
      })
    }
    element.addEventListener('loadedmetadata',onReady)
    reauthCleanup=()=>element.removeEventListener('loadedmetadata',onReady)
    clearTimeout(restoreTimer)
    restoreTimer=setTimeout(()=>{if(reauthPending)finishReauth(false)},15000)
    // 只改 element.src 强制重新命中同源地址；路径不变、后端忽略该参数；active() 仍比对闭包里的原始 source。
    const separator=track.audio.includes('?')?'&':'?'
    element.src=track.audio+separator+'reauth='+(++reauthSequence)
    return true
  }
  const pool=new NativeAudioPool(source=>{
    const element=new Audio();element.preload='auto'
    const active=()=>element===audio&&current.value?.audio===source
    element.addEventListener('timeupdate',()=>{
      if(!active()||pendingRestore)return
      if(element.currentTime>time.value)stopWaiting();time.value=element.currentTime
      const part=segment.value
      if(part&&current.value?.id===part.track&&!element.paused&&time.value>=part.end){
        if(part.loop){element.currentTime=part.start;time.value=part.start;seekVersion.value++}else{element.pause();segment.value=null}
      }
    })
    element.addEventListener('seeking',()=>{if(active()&&!pendingRestore)time.value=element.currentTime})
    element.addEventListener('seeked',()=>{
      if(!active())return
      time.value=element.currentTime
      if(pendingRestore||restoreSeekGuard){restoreSeekGuard=false;clearTimeout(seekGuardTimer);return}
      seekVersion.value++;if(element.readyState>=3)stopWaiting()
    })
    element.addEventListener('loadedmetadata',()=>{if(active())readDuration()})
    element.addEventListener('durationchange',()=>{if(active())readDuration()})
    element.addEventListener('play',()=>{if(active())playing.value=true})
    element.addEventListener('playing',()=>{if(active()&&!element.paused){playing.value=true;starting.value=false;stopWaiting()}})
    element.addEventListener('pause',()=>{if(active()){playing.value=false;stopWaiting()}})
    element.addEventListener('waiting',()=>{if(active()&&!pendingRestore&&(starting.value||!element.paused))waitForAudio()})
    element.addEventListener('stalled',()=>{if(active()&&!pendingRestore&&(starting.value||!element.paused)&&!buffering.value)waitForAudio()})
    element.addEventListener('canplay',()=>{if(active())stopWaiting()})
    element.addEventListener('error',()=>{
      if(!active()||!element.error)return
      const code=element.error.code
      if(code===1)return
      if(reauthPending||pendingRestore){finishReauth(false);return}
      if(retriable(code,element)&&reauthorize())return
      error.value='音频无法加载，链接可能已失效或文件已损坏。可点击重试。';playing.value=false;starting.value=false;stopWaiting()
    })
    element.addEventListener('ended',()=>{
      if(!active()||pendingRestore)return
      playing.value=false;starting.value=false;stopWaiting()
      const part=segment.value
      if(part&&part.track===current.value?.id){
        if(part.loop){element.currentTime=part.start;time.value=part.start;seekVersion.value++;const turn=operation;void element.play().catch(()=>{if(turn===operation){segment.value=null;error.value='暂时无法播放，请点击重试，并检查礼物链接是否有效。'}})}else segment.value=null
        return
      }
      if(repeatOne.value){
        const turn=operation
        try{
          element.currentTime=0;time.value=0;seekVersion.value++;starting.value=true;waitForAudio()
          void element.play().catch((cause:unknown)=>{
            if(turn!==operation||!active())return
            starting.value=false;element.pause();playing.value=false;stopWaiting()
            error.value=cause instanceof Error&&cause.name==='NotAllowedError'?'浏览器暂停了播放，请点击播放继续。':'暂时无法播放，请点击重试，并检查礼物链接是否有效。'
          })
        }catch{
          if(turn===operation&&active()){starting.value=false;element.pause();playing.value=false;stopWaiting();error.value='暂时无法播放，请点击重试，并检查礼物链接是否有效。'}
        }
        return
      }
      const index=queue.value.findIndex(track=>track.id===current.value?.id)
      if(autoAdvance&&hasQueue.value&&index<queue.value.length-1)void next()
    })
    return element
  })
  async function toggle(track:PlayableTrack){
    cancelReauth()
    const turn=++operation
    // 仅「可重试」的错误路由到重新授权；code 1/3 与非 CDN 的 code 4 保留原有 load() 手动重试入口。
    const retryable=!!audio&&!!audio.error&&retriable(audio.error.code,audio)
    if(same(track)&&audio&&audio.error&&retryable){
      error.value=''
      if(reauthorize(true))return
    }
    // error 状态下不得退化成暂停，否则「重试播放」按钮会失效。
    if(same(track)&&audio&&!audio.error&&(starting.value||!audio.paused)){starting.value=false;audio.pause();stopWaiting();return}
    if(!same(track))segment.value=null
    stopWaiting();audio?.pause();error.value=''
    // 最近两首保留原生缓冲；关闭后重开、重播和版本对比不主动重置媒体源。
    audio=undefined;current.value=track;playing.value=false;starting.value=true;time.value=0;duration.value=0
    if(!queue.value.some(item=>item.id===track.id&&item.audio===track.audio)){queue.value=[track];autoAdvance=false}
    try{
      const element=pool.acquire(track.audio);audio=element;element.preload='auto'
      if(!element.getAttribute('src'))element.src=track.audio
      else if(element.error)element.load()
      if(element.ended||Number.isFinite(element.duration)&&element.currentTime>=element.duration)element.currentTime=0
      readDuration();time.value=element.currentTime;waitForAudio()
      // 直接在点击处理内调用play，前面不能await整首fetch或其他网络请求。
      const pending=element.play()
      await pending
      if(turn===operation){starting.value=false;stopWaiting()}
    }catch(cause){
      if(turn!==operation)return
      starting.value=false;audio?.pause();playing.value=false;stopWaiting()
      error.value=cause instanceof Error&&cause.name==='NotAllowedError'?'浏览器暂停了播放，请点击播放继续。':error.value||'暂时无法播放，请点击重试，并检查礼物链接是否有效。'
    }
  }
  function updateCover(id:string,cover:string){if(current.value?.id===id)current.value={...current.value,cover};queue.value=queue.value.map(track=>track.id===id?{...track,cover}:track)}
  function setQueue(tracks:PlayableTrack[],advance=true){queue.value=tracks;autoAdvance=advance}
  async function next(direction=1){if(!hasQueue.value)return;const index=queue.value.findIndex(track=>track.id===current.value?.id),track=queue.value[(index+direction+queue.value.length)%queue.value.length];if(track)await toggle(track)}
  function seek(value:number){restoreSeekGuard=false;clearTimeout(seekGuardTimer);segment.value=null;if(audio&&Number.isFinite(value)&&duration.value>0){try{audio.currentTime=Math.min(duration.value,Math.max(0,value));time.value=audio.currentTime;seekVersion.value++}catch{error.value='当前音频暂不支持跳转进度'}}}
  async function playSegment(track:PlayableTrack,start:number,end:number,loop=true){
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<=start)return
    segment.value=null
    if(!same(track)||!playing.value){const pending=toggle(track),turn=operation;await pending;if(turn!==operation)return}
    if(!same(track)||!playing.value||!audio)return
    readDuration();const until=Math.min(end,duration.value)
    if(start>=until){error.value='这个片段超出了音频实际时长，请调整范围。';return}
    seek(start);segment.value={track:track.id,start,end:until,loop}
  }
  function stopSegment(pause=false){segment.value=null;if(pause){cancelReauth();operation++;starting.value=false;audio?.pause();stopWaiting()}}
  function close(){cancelReauth();operation++;segment.value=null;repeatOne.value=false;audio?.pause();if(audio)audio.preload='metadata';current.value=null;queue.value=[];time.value=0;duration.value=0;error.value='';starting.value=false;stopWaiting();playing.value=false}
  /** 身份切换和分享失效彻底释放，与普通收起播放器分开。 */
  function reset(){close();audio=undefined;pool.clear()}
  return {current,playing,starting,buffering,time,duration,error,hasQueue,tracks,repeatOne,toggleRepeat,seekVersion,toggle,seek,close,reset,setQueue,next,updateCover,segment,playSegment,stopSegment}
})
