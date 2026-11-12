// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import SyncedLyrics from '../src/modules/works/components/SyncedLyrics.vue'
import type {PlaybackLyrics} from '../src/modules/works/api/detail'
const api=vi.hoisted(()=>({get:vi.fn(),sync:vi.fn(),rewrite:vi.fn()}))
let player:ReturnType<typeof reactive<{current:{id:string}|null;playing:boolean;time:number;seekVersion:number}>>
vi.mock('../src/shared/player/store',()=>({usePlayer:()=>player}))
vi.mock('../src/modules/works/api/detail',()=>({getPlaybackLyrics:api.get,syncPlaybackLyrics:api.sync,rewriteLyricLine:api.rewrite}))
let app:App|undefined
const scroll=vi.fn(),seek=vi.fn(),edit=vi.fn()
const lines=[{text:'第一句',start:10,end:15},{text:'第二句',start:18,end:25},{text:'第三句',start:30,end:35}]
const snapshot=(status:PlaybackLyrics['status']='NOT_REQUESTED',canSync=true):PlaybackLyrics=>({status,canSync,text:'[Verse]\n第一句\n第二句\n第三句',lines:status==='READY'?lines:[]})
async function flush(){for(let i=0;i<12;i++)await nextTick()}
function mount(editable=false){const props=reactive({workId:'work-a',assetId:'audio-a',lyrics:'离线歌词',editable});app=createApp({setup:()=>()=>h(SyncedLyrics,{...props,onSeek:seek,onEdit:edit})});app.mount(document.body.appendChild(document.createElement('div')));return props}
function deferred<T>(){let resolve!:(value:T)=>void;return{promise:new Promise<T>(r=>resolve=r),resolve}}
beforeEach(()=>{
 vi.useFakeTimers();vi.clearAllMocks();sessionStorage.clear();player=reactive({current:null,playing:false,time:0,seekVersion:0})
 api.get.mockResolvedValue(snapshot());api.sync.mockResolvedValue(snapshot('READY'))
 Object.defineProperty(document,'visibilityState',{configurable:true,value:'visible'})
 Object.defineProperty(window,'matchMedia',{configurable:true,value:vi.fn(()=>({matches:false}))})
 Object.defineProperty(HTMLElement.prototype,'scrollTo',{configurable:true,value:scroll})
 vi.spyOn(HTMLElement.prototype,'getBoundingClientRect').mockImplementation(function(this:HTMLElement){const top=this.dataset.line?100+Number(this.dataset.line)*80:100;return{top,left:0,right:300,bottom:top+40,width:300,height:40,x:0,y:top,toJSON(){}}})
 Object.defineProperty(HTMLElement.prototype,'clientHeight',{configurable:true,get(){return 200}})
 Object.defineProperty(HTMLElement.prototype,'offsetHeight',{configurable:true,get(){return 40}})
})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML='';vi.restoreAllMocks();vi.useRealTimers()})
it('does not spend timing quota just by opening the page or playing another song',async()=>{
 mount();await flush();expect(api.sync).not.toHaveBeenCalled();player.current={id:'other'};player.playing=true;await flush();expect(api.sync).not.toHaveBeenCalled();expect(document.body.textContent).toContain('第一句');expect(document.body.textContent).not.toContain('局部改词')
})
it('automatically syncs once when this song starts and follows exact time boundaries',async()=>{
 mount();await flush();player.current={id:'audio-a'};player.playing=true;await flush();expect(api.sync).toHaveBeenCalledTimes(1);expect(api.sync.mock.calls[0]!.slice(0,2)).toEqual(['work-a','audio-a']);expect(api.sync.mock.calls[0]![2]).toMatch(/^[a-f0-9-]{36}$/)
 player.time=19;await flush();expect(document.querySelector('.lyric-lines .active')?.textContent).toBe('第二句');expect(scroll).toHaveBeenCalled();player.time=26;await flush();expect(document.querySelector('.active')).toBeNull();expect(document.body.textContent).toContain('间奏');player.playing=false;player.playing=true;await flush();expect(api.sync).toHaveBeenCalledTimes(1)
})
it('waits for the initial GET before submitting even when playback is already active',async()=>{
 const pending=deferred<PlaybackLyrics>();api.get.mockReturnValueOnce(pending.promise);player.current={id:'audio-a'};player.playing=true;mount();await flush();expect(api.sync).not.toHaveBeenCalled();pending.resolve(snapshot());await flush();expect(api.sync).toHaveBeenCalledTimes(1)
})
it('uses cached timing without POST and restores following immediately on forward/backward seeks',async()=>{
 api.get.mockResolvedValue(snapshot('READY'));player.current={id:'audio-a'};player.playing=true;mount();await flush();document.querySelector('.lyric-viewport')!.dispatchEvent(new Event('wheel'));await flush();expect(document.body.textContent).toContain('回到正在唱的歌词');player.time=31;player.seekVersion++;await flush();expect(document.querySelector('.active')?.textContent).toBe('第三句');expect(document.querySelector('.lyric-follow')).toBeNull();expect(scroll.mock.lastCall![0].behavior).toBe('instant');player.time=11;player.seekVersion++;await flush();expect(document.querySelector('.active')?.textContent).toBe('第一句');expect(api.sync).not.toHaveBeenCalled()
})
it('allows manual reading for six seconds and then resumes without scrolling the page',async()=>{
 api.get.mockResolvedValue(snapshot('READY'));player.current={id:'audio-a'};player.playing=true;mount();await flush();scroll.mockClear();document.querySelector('.lyric-viewport')!.dispatchEvent(new Event('wheel'));await flush();player.time=19;await flush();expect(scroll).not.toHaveBeenCalled();await vi.advanceTimersByTimeAsync(6000);await flush();expect(scroll).toHaveBeenCalled();expect(document.querySelector('.lyric-follow')).toBeNull()
})
it('continues GET polling after temporary network failure without sending another POST',async()=>{
 api.sync.mockResolvedValue(snapshot('FETCHING'));api.get.mockResolvedValueOnce(snapshot()).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(snapshot('READY'));player.current={id:'audio-a'};player.playing=true;mount();await flush();await vi.advanceTimersByTimeAsync(3000);await flush();expect(document.body.textContent).toContain('第一句');await vi.advanceTimersByTimeAsync(6000);await flush();expect(document.querySelector('.lyric-lines')).not.toBeNull();expect(api.sync).toHaveBeenCalledTimes(1)
})
it('unknown submission only queries; it never retries POST or invents timing',async()=>{
 api.sync.mockRejectedValue(new Error('timeout'));api.get.mockResolvedValueOnce(snapshot()).mockResolvedValue(snapshot('UNKNOWN'));player.current={id:'audio-a'};player.playing=true;mount();await flush();await vi.advanceTimersByTimeAsync(12000);await flush();expect(api.sync).toHaveBeenCalledTimes(1);expect(document.querySelector('.plain-lyric-lines')).not.toBeNull();expect(document.querySelector('.lyric-lines')).toBeNull()
})
it('never automatically retries failed timing across pause or reload, but supports an explicit retry',async()=>{
 api.get.mockResolvedValue(snapshot('FAILED'));player.current={id:'audio-a'};player.playing=true;mount();await flush();expect(api.sync).not.toHaveBeenCalled();document.querySelector<HTMLButtonElement>('.lyric-fetch')!.click();await flush();expect(api.sync).toHaveBeenCalledTimes(1);expect(document.querySelector('.lyric-lines')).not.toBeNull()
})
it('does not resubmit a persisted pending request after remount',async()=>{
 sessionStorage.setItem('qingfeng.lyrics.timing:audio-a','a'.repeat(36));player.current={id:'audio-a'};player.playing=true;mount();await flush();expect(api.sync).not.toHaveBeenCalled();expect(document.body.textContent).toContain('重试同步歌词')
})
it('discards old responses and old follow timers after switching songs',async()=>{
 const pending=deferred<PlaybackLyrics>();api.sync.mockReturnValueOnce(pending.promise);player.current={id:'audio-a'};player.playing=true;const props=mount();await flush();props.assetId='audio-b';api.get.mockResolvedValue(snapshot('EMPTY'));await flush();pending.resolve(snapshot('READY'));await flush();expect(document.querySelector('.lyric-lines')).toBeNull();expect(document.body.textContent).toContain('这首音频未返回可用的歌词时间轴');expect(api.sync).toHaveBeenCalledTimes(1)
})
it('does not sync unsupported audio and still displays the full lyrics',async()=>{
 api.get.mockResolvedValue(snapshot('NOT_REQUESTED',false));player.current={id:'audio-a'};player.playing=true;mount();await flush();expect(api.sync).not.toHaveBeenCalled();expect(document.body.textContent).toContain('第二句');expect(document.querySelector('.lyric-fetch')).toBeNull()
})
it('seeks to the selected timed lyric, including while paused',async()=>{
 api.get.mockResolvedValue(snapshot('READY'));player.current={id:'audio-a'};mount();await flush();document.querySelectorAll<HTMLButtonElement>('.lyric-lines button')[1]!.click();await flush();expect(seek).toHaveBeenCalledWith(18);expect(api.sync).not.toHaveBeenCalled()
})
it('keeps seeking independent from editing and sends the selected repeated line to the section editor',async()=>{
 const ready=snapshot('READY')
 ready.lines=[{text:'副歌',start:10,end:14},{text:'副歌',start:32,end:37}]
 api.get.mockResolvedValue(ready);api.rewrite.mockResolvedValue({text:'新的副歌'})
 player.current={id:'audio-a'};player.playing=true;player.time=33
 mount(true);await flush()
 document.querySelector<HTMLButtonElement>('[data-line="1"] button:first-child')!.click()
 expect(seek).toHaveBeenCalledWith(32)
 document.querySelector<HTMLButtonElement>('[data-line="1"] .lyric-edit-line')!.click();await flush()
 expect(seek).toHaveBeenCalledTimes(1)
 const field=document.querySelector<HTMLTextAreaElement>('.playback-line-draft textarea')!
 expect(field.value).toBe('副歌')
 scroll.mockClear();player.time=11;await flush()
 expect(scroll).not.toHaveBeenCalled()
 document.querySelector<HTMLButtonElement>('.playback-line-actions .text-button')!.click();await flush()
 expect(field.value).toBe('副歌')
 document.querySelector<HTMLButtonElement>('.playback-line-suggestion button')!.click();await flush()
 expect(field.value).toBe('新的副歌')
 document.querySelector('form.playback-line-draft')!.dispatchEvent(new Event('submit',{cancelable:true}));await flush()
 expect(edit).toHaveBeenCalledExactlyOnceWith({text:'副歌',start:32,end:37,occurrence:1,replacement:'新的副歌'})
})
it('stops polling after five consecutive failures and cleans all timers on unmount',async()=>{
 api.get.mockRejectedValue(new Error('offline'));mount();await flush();await vi.advanceTimersByTimeAsync(90000);await flush();expect(api.get).toHaveBeenCalledTimes(5);app!.unmount();app=undefined;await vi.advanceTimersByTimeAsync(200000);expect(api.get).toHaveBeenCalledTimes(5)
})
it('respects reduced motion and never centers an unrelated playing track',async()=>{
 Object.defineProperty(window,'matchMedia',{configurable:true,value:vi.fn(()=>({matches:true}))});api.get.mockResolvedValue(snapshot('READY'));player.current={id:'other'};player.playing=true;mount();await flush();expect(scroll).not.toHaveBeenCalled();player.current={id:'audio-a'};await flush();expect(scroll.mock.lastCall![0].behavior).toBe('instant')
})
