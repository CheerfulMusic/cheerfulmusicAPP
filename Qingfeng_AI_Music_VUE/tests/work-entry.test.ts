// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick,reactive,type App} from 'vue'
import WorkDetailPage from '../src/modules/works/pages/WorkDetailPage.vue'
import type {AudioAsset} from '../src/modules/works/api/detail'
const api=vi.hoisted(()=>({work:vi.fn(),tasks:vi.fn(),system:vi.fn(),quick:vi.fn(),audio:vi.fn(),selection:vi.fn(),usage:vi.fn(),revision:vi.fn(),push:vi.fn(),paid:vi.fn(),publication:vi.fn(),covers:vi.fn(),lyricsReady:false,route:{params:{id:'work-a'},query:{}}}))
vi.mock('vue-router',()=>({useRoute:()=>reactive(api.route),useRouter:()=>({push:api.push}),onBeforeRouteLeave:vi.fn(),onBeforeRouteUpdate:vi.fn()}))
vi.mock('../src/modules/studio',()=>({loadWork:api.work}))
vi.mock('../src/shared/http/system',()=>({getSystemInfo:api.system}))
vi.mock('../src/modules/artist/api/artist',async original=>({...await original<typeof import('../src/modules/artist/api/artist')>(),getSongPublication:api.publication}))
vi.mock('../src/shared/http/api-client',async original=>({...await original<typeof import('../src/shared/http/api-client')>(),currentCreatorId:()=> 'fixture-owner'}))
vi.mock('../src/modules/works/api/detail',async original=>({...await original<typeof import('../src/modules/works/api/detail')>(),listTasks:api.tasks,listAudio:api.audio,getUsage:api.usage,getRevision:api.revision,submitTask:api.paid}))
vi.mock('../src/modules/works/api/section-edits',()=>({getAudioSelection:api.selection,selectAudio:api.paid}))
vi.mock('../src/modules/works/api/covers',()=>({loadCovers:api.covers,coverUrl:(_work:string,id?:string)=>id?`/covers/${id}`:undefined}))
vi.mock('../src/shared/player/store',()=>({usePlayer:()=>({current:null,tracks:[],setQueue:vi.fn(),toggle:vi.fn(),updateTitles:vi.fn()})}))
vi.mock('../src/modules/studio/components/QuickCreationProgress.vue',async()=>{
 const {defineComponent,h,ref,onMounted}=await import('vue')
 return {default:defineComponent({emits:['busy','resolved','checked'],setup(_,ctx){const message=ref('正在核对创作进度');onMounted(async()=>{try{const result=await api.quick();message.value=result?'正在制作音乐':'';ctx.emit('busy',!!result);ctx.emit('resolved',result);ctx.emit('checked',true)}catch{message.value='进度暂时无法读取';ctx.emit('checked',false)}});return()=>h('section',{class:'quick-progress'},message.value) }})}
})
vi.mock('../src/modules/works/components/SongResult.vue',()=>({default:{props:['title','asset','cover'],template:'<section id="song-result"><img v-if="cover" :src="cover" alt="歌曲封面"><h2>{{title}}</h2><button>试听歌曲</button></section>'}}))
vi.mock('../src/modules/works/components/LyricsPanel.vue',async()=>{
 const {defineComponent}=await import('vue')
 return {default:defineComponent({
  emits:['ready'],mounted(){this.$emit('ready',api.lyricsReady)},template:'<div class="test-lyrics">歌词草稿</div>',
 })}
})
vi.mock('../src/modules/works/components/SectionEditor.vue',()=>({default:{template:'<div/>'}}))
vi.mock('../src/modules/works/components/SharePanel.vue',()=>({default:{template:'<div/>'}}))
vi.mock('../src/modules/works/components/MusicSettingsSnapshot.vue',()=>({default:{template:'<div/>'}}))
vi.mock('../src/modules/works/components/MusicGenerationDialog.vue',()=>({default:{template:'<div/>'}}))
const audio:AudioAsset={id:'audio-a',workId:'work-a',origin:'GENERATED',contentType:'audio/mpeg',byteSize:1000,createdAt:'2026-11-10T00:00:00Z'}
function deferred<T>(){let resolve!:(v:T)=>void,reject!:(e:Error)=>void;const promise=new Promise<T>((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
async function flush(){for(let i=0;i<20;i++)await nextTick()}
let app:App|undefined
function mount(){app=createApp(WorkDetailPage);app.component('RouterLink',{props:['to'],template:'<a :href="to"><slot/></a>'});app.mount(document.body.appendChild(document.createElement('div')))}
function visible(selector:string){const el=document.querySelector<HTMLElement>(selector);if(!el)return false;for(let p:HTMLElement|null=el;p;p=p.parentElement){if(p.style.display==='none')return false}return true}
beforeEach(()=>{
 vi.clearAllMocks();localStorage.clear();sessionStorage.clear();api.route.params.id='work-a';api.route.query={};api.lyricsReady=false
 api.covers.mockRejectedValue(new Error('offline'))
 api.work.mockImplementation(async(id:string)=>({id,version:1,title:'晚风把今天吹成一首歌',style:'流行',story:'我的故事',recipient:'',vocalGender:'AUTO',message:''}));api.tasks.mockResolvedValue([]);api.system.mockResolvedValue({capabilities:{musicGeneration:true}});api.quick.mockResolvedValue(null);api.audio.mockResolvedValue([audio]);api.selection.mockResolvedValue({asset:audio,version:1});api.usage.mockResolvedValue({music:{dailyUnlimited:true}});api.revision.mockResolvedValue(null)
})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML='';vi.restoreAllMocks();vi.useRealTimers()})
it('refreshes a cover that finishes before an active music task, even when the initial cover read preceded submission',async()=>{
 vi.useFakeTimers()
 api.tasks.mockResolvedValue([{id:'task-a',status:'RUNNING'}])
 api.covers.mockResolvedValueOnce({currentCoverId:null,version:0,assets:[],edits:[],aiAvailable:false,automaticPending:false})
  .mockResolvedValue({currentCoverId:'automatic-cover',version:1,assets:[],edits:[],aiAvailable:false,automaticPending:false})
 mount();await flush()
 expect(document.querySelector('#song-result img')).toBeNull()
 await vi.advanceTimersByTimeAsync(5000);await flush()
 expect(document.querySelector('#song-result img')?.getAttribute('src')).toBe('/covers/automatic-cover')
 const calls=api.covers.mock.calls.length
 await vi.advanceTimersByTimeAsync(5000);await flush()
 expect(api.covers).toHaveBeenCalledTimes(calls)
})
it('keeps an existing manual cover without polling for an automatic replacement during music generation',async()=>{
 vi.useFakeTimers()
 api.tasks.mockResolvedValue([{id:'task-a',status:'RUNNING'}])
 api.covers.mockResolvedValue({currentCoverId:'manual-cover',version:1,assets:[],edits:[],aiAvailable:false,automaticPending:false})
 mount();await flush()
 await vi.advanceTimersByTimeAsync(10000);await flush()
 expect(api.covers).toHaveBeenCalledOnce()
 expect(document.querySelector('#song-result img')?.getAttribute('src')).toBe('/covers/manual-cover')
})
it('shows only a player-shaped skeleton until audio is classified, never the creation heading',async()=>{
 const pending=deferred<{asset:AudioAsset;version:number}>();api.selection.mockReturnValue(pending.promise);mount();await flush();expect(visible('.song-loading')).toBe(true);expect(visible('.quick-progress')).toBe(false);expect(visible('.work-detail-top')).toBe(false);expect(visible('.test-lyrics')).toBe(false);pending.resolve({asset:audio,version:1});await flush();expect(visible('#song-result')).toBe(true);expect(visible('.song-loading')).toBe(false);expect(visible('.creation-steps')).toBe(false)
})
it('opens completed music even while progress and service-status reads are unresolved',async()=>{
 const task=deferred<never>(),quick=deferred<never>(),system=deferred<never>();api.tasks.mockReturnValue(task.promise);api.quick.mockReturnValue(quick.promise);api.system.mockReturnValue(system.promise);mount();await flush();expect(visible('#song-result')).toBe(true);expect(visible('.work-detail-top')).toBe(false);expect(visible('.quick-progress')).toBe(false);expect(visible('.test-lyrics')).toBe(false);expect(api.paid).not.toHaveBeenCalled()
})
it('keeps completed music accessible when all auxiliary reads fail',async()=>{
 api.tasks.mockRejectedValue(new Error('tasks offline'));api.quick.mockRejectedValue(new Error('quick offline'));api.system.mockRejectedValue(new Error('system offline'));mount();await flush();expect(visible('#song-result')).toBe(true);expect(document.body.textContent).toContain('已有歌曲仍可试听');expect(visible('.quick-progress')).toBe(false);expect(visible('.description-back-button')).toBe(false)
})
it('loads a selected recording without waiting for the full version list',async()=>{
 const list=deferred<AudioAsset[]>();api.audio.mockReturnValue(list.promise);mount();await flush();expect(visible('#song-result')).toBe(true);expect(visible('.song-loading')).toBe(false);list.reject(new Error('offline'));await flush();expect(visible('#song-result')).toBe(true)
})
it('shows the lyric editor only for a confirmed empty draft',async()=>{
 api.audio.mockResolvedValue([]);api.selection.mockResolvedValue({asset:null,version:0});mount();await flush();expect(visible('#song-result')).toBe(false);expect(visible('.test-lyrics')).toBe(true);expect(visible('.work-detail-top')).toBe(true);expect(visible('.description-back-button')).toBe(true)
})
it('does not mistake failed progress lookup for a draft or enable another submission',async()=>{
 api.audio.mockResolvedValue([]);api.selection.mockResolvedValue({asset:null,version:0});api.quick.mockRejectedValue(new Error('offline'));mount();await flush();expect(visible('.test-lyrics')).toBe(false);expect(document.body.textContent).toContain('原任务不会重复提交');expect(api.paid).not.toHaveBeenCalled()
})
it('shows real generation progress without the lyric editor for an active task',async()=>{
 api.audio.mockResolvedValue([]);api.selection.mockResolvedValue({asset:null,version:0});api.tasks.mockResolvedValue([{id:'task-a',status:'RUNNING'}]);api.quick.mockResolvedValue({id:'quick-a',status:'RUNNING'});mount();await flush();expect(visible('.quick-progress')).toBe(true);expect(visible('.test-lyrics')).toBe(false);expect(api.paid).not.toHaveBeenCalled()
})
it('keeps the current song visible and uses a compact notice for a newer version being made',async()=>{
 api.quick.mockResolvedValue({id:'quick-a',status:'RUNNING'});api.tasks.mockResolvedValue([{id:'task-a',status:'RUNNING'}]);mount();await flush();expect(visible('#song-result')).toBe(true);expect(visible('.work-entry-progress.has-song')).toBe(true);expect(visible('.work-detail-top')).toBe(false);expect(api.paid).not.toHaveBeenCalled()
})
it('reports audio-read failure with a safe retry instead of opening draft controls',async()=>{
 api.audio.mockRejectedValue(new Error('offline'));api.selection.mockRejectedValue(new Error('offline'));mount();await flush();expect(document.body.textContent).toContain('你的作品仍然保留');expect(visible('.test-lyrics')).toBe(false);api.audio.mockResolvedValue([audio]);api.selection.mockResolvedValue({asset:audio,version:1});[...document.querySelectorAll('button')].find(b=>b.textContent==='重新读取歌曲')!.click();await flush();expect(visible('#song-result')).toBe(true);expect(api.paid).not.toHaveBeenCalled()
})
it('ignores a late old-work response after navigating to another song',async()=>{
 const old=deferred<never>();api.work.mockReturnValueOnce(old.promise);mount();await flush();reactive(api.route).params.id='work-b';await flush();expect(visible('#song-result')).toBe(true);old.reject(new Error('old work offline'));await flush();expect(document.body.textContent).not.toContain('old work offline')
})

it('asks for a visible publication choice before generation and lets the saved scope be changed',async()=>{
 api.lyricsReady=true
 api.audio.mockResolvedValue([])
 api.selection.mockResolvedValue({asset:null,version:0})
 api.publication.mockResolvedValue({profileReady:true,published:true,publicationPending:false,song:null})
 localStorage.setItem('qingfeng.generation-scope:fixture-owner','public')
 mount()
 await flush()
 const dialog=document.querySelector<HTMLDialogElement>('.save-generation-dialog')!
 dialog.showModal=()=>{dialog.open=true}
 document.querySelector<HTMLButtonElement>('.creation-primary')!.click()
 await flush()
 expect(dialog.open).toBe(true)
 expect(dialog.querySelector<HTMLInputElement>('input[value="public"]')!.checked).toBe(true)
 expect(dialog.querySelector('button[type="submit"]')!.textContent).toBe('生成并发布到广场')
 dialog.querySelector<HTMLInputElement>('input[value="private"]')!.click()
 await flush()
 expect(dialog.querySelector('button[type="submit"]')!.textContent).toBe('私密生成')
 expect(api.paid).not.toHaveBeenCalled()
})
