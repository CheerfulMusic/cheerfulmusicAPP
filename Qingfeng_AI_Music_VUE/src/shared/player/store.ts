import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { NativeAudioPool } from './audio-cache'
export interface PlayableTrack { id: string; title: string; titleKey?: string; cover: string; audio: string; provenance?: 'SAMPLE' | 'IMPORTED' | 'GENERATED' }
export const usePlayer = defineStore('player', () => {
  const current=ref<PlayableTrack|null>(null),playing=ref(false),starting=ref(false),buffering=ref(false),time=ref(0),duration=ref(0),error=ref(''),queue=ref<PlayableTrack[]>([])
  const hasQueue=computed(()=>queue.value.length>1&&queue.value.some(track=>track.id===current.value?.id))
  const seekVersion=ref(0),segment=ref<{track:string;start:number;end:number;loop:boolean}|null>(null)
  let audio:HTMLAudioElement|undefined,operation=0,waitingTimer:ReturnType<typeof setTimeout>|undefined,autoAdvance=true
  const same=(track:PlayableTrack)=>current.value?.id===track.id&&current.value?.audio===track.audio
  function stopWaiting(){clearTimeout(waitingTimer);buffering.value=false}
  function readDuration(){duration.value=audio&&Number.isFinite(audio.duration)?audio.duration:0}
  function waitForAudio(){
    stopWaiting();buffering.value=true
    waitingTimer=setTimeout(()=>{operation++;starting.value=false;audio?.pause();stopWaiting();error.value='播放等待时间较长，请点击重试。'},15000)
  }
  const pool=new NativeAudioPool(source=>{
    const element=new Audio();element.preload='auto'
    const active=()=>element===audio&&current.value?.audio===source
    element.addEventListener('timeupdate',()=>{
      if(!active())return
      if(element.currentTime>time.value)stopWaiting();time.value=element.currentTime
      const part=segment.value
      if(part&&current.value?.id===part.track&&!element.paused&&time.value>=part.end){
        if(part.loop){element.currentTime=part.start;time.value=part.start;seekVersion.value++}else{element.pause();segment.value=null}
      }
    })
    element.addEventListener('seeking',()=>{if(active())time.value=element.currentTime})
    element.addEventListener('seeked',()=>{if(active()){time.value=element.currentTime;seekVersion.value++;if(element.readyState>=3)stopWaiting()}})
    element.addEventListener('loadedmetadata',()=>{if(active())readDuration()})
    element.addEventListener('durationchange',()=>{if(active())readDuration()})
    element.addEventListener('play',()=>{if(active())playing.value=true})
    element.addEventListener('playing',()=>{if(active()&&!element.paused){playing.value=true;starting.value=false;stopWaiting()}})
    element.addEventListener('pause',()=>{if(active()){playing.value=false;stopWaiting()}})
    element.addEventListener('waiting',()=>{if(active()&&(starting.value||!element.paused))waitForAudio()})
    element.addEventListener('stalled',()=>{if(active()&&(starting.value||!element.paused)&&!buffering.value)waitForAudio()})
    element.addEventListener('canplay',()=>{if(active())stopWaiting()})
    element.addEventListener('error',()=>{
      if(!active()||!element.error)return
      error.value='音频无法加载，链接可能已失效或文件已损坏。可点击重试。';playing.value=false;starting.value=false;stopWaiting()
    })
    element.addEventListener('ended',()=>{
      if(!active())return
      playing.value=false;starting.value=false;stopWaiting()
      const part=segment.value
      if(part&&part.track===current.value?.id){
        if(part.loop){element.currentTime=part.start;time.value=part.start;seekVersion.value++;const turn=operation;void element.play().catch(()=>{if(turn===operation){segment.value=null;error.value='暂时无法播放，请点击重试，并检查礼物链接是否有效。'}})}else segment.value=null
        return
      }
      const index=queue.value.findIndex(track=>track.id===current.value?.id)
      if(autoAdvance&&hasQueue.value&&index<queue.value.length-1)void next()
    })
    return element
  })
  async function toggle(track:PlayableTrack){
    const turn=++operation
    if(same(track)&&audio&&(starting.value||!audio.paused)){starting.value=false;audio.pause();stopWaiting();return}
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
  function seek(value:number){segment.value=null;if(audio&&Number.isFinite(value)&&duration.value>0){try{audio.currentTime=Math.min(duration.value,Math.max(0,value));time.value=audio.currentTime;seekVersion.value++}catch{error.value='当前音频暂不支持跳转进度'}}}
  async function playSegment(track:PlayableTrack,start:number,end:number,loop=true){
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<=start)return
    segment.value=null
    if(!same(track)||!playing.value){const pending=toggle(track),turn=operation;await pending;if(turn!==operation)return}
    if(!same(track)||!playing.value||!audio)return
    readDuration();const until=Math.min(end,duration.value)
    if(start>=until){error.value='这个片段超出了音频实际时长，请调整范围。';return}
    seek(start);segment.value={track:track.id,start,end:until,loop}
  }
  function stopSegment(pause=false){segment.value=null;if(pause){operation++;starting.value=false;audio?.pause();stopWaiting()}}
  function close(){operation++;segment.value=null;audio?.pause();if(audio)audio.preload='metadata';current.value=null;queue.value=[];time.value=0;duration.value=0;error.value='';starting.value=false;stopWaiting();playing.value=false}
  /** 身份切换和分享失效彻底释放，与普通收起播放器分开。 */
  function reset(){close();audio=undefined;pool.clear()}
  return {current,playing,starting,buffering,time,duration,error,hasQueue,seekVersion,toggle,seek,close,reset,setQueue,next,updateCover,segment,playSegment,stopSegment}
})
