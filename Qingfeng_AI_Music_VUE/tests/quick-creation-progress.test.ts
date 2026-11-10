// @vitest-environment jsdom
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {createApp,h,nextTick,reactive,type App} from 'vue'
import QuickCreationProgress from '../src/modules/studio/components/QuickCreationProgress.vue'
import type {QuickCreation} from '../src/modules/studio/api/quick-creation'

const mocks=vi.hoisted(()=>({latest:vi.fn(),retry:vi.fn()}))
vi.mock('../src/modules/studio/api/quick-creation',()=>({latestQuickCreation:mocks.latest,retryQuickCreation:mocks.retry,quickInProgress:(s:string)=>['QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC','MUSIC_QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN'].includes(s)}))
let app:App|undefined
const flow=(status:string,extra:Partial<QuickCreation>={}):QuickCreation=>({id:'flow-a',workId:'work-a',status,revisionId:null,taskId:null,errorCode:null,canRetry:false,createdAt:new Date().toISOString(),...extra})
async function flush(){for(let i=0;i<8;i++)await nextTick()}
function mount(resultReady=false,completionMode:'reveal'|'navigate'='reveal'){
 const props=reactive({workId:'work-a',title:'今天的歌',resultReady,autoOpen:true,completionMode}),view=vi.fn(),updated=vi.fn()
 app=createApp({render:()=>h(QuickCreationProgress,{...props,onView:view,onUpdated:updated})})
 app.component('RouterLink',{props:['to'],template:'<a :href="to"><slot/></a>'})
 app.mount(document.body.appendChild(document.createElement('div')))
 return {props,view,updated}
}
function click(label:string){const button=[...document.querySelectorAll('button')].find(b=>b.textContent?.trim()===label);expect(button).toBeTruthy();button!.click()}
beforeEach(()=>{
 vi.clearAllMocks();vi.useFakeTimers()
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value(){this.open=true}})
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value(){this.open=false}})
 mocks.latest.mockResolvedValue(flow('WRITING'));mocks.retry.mockResolvedValue(flow('WRITING'))
})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML='';vi.useRealTimers();vi.restoreAllMocks()})
it('automatically opens one dialog for writing, without a manual lyric-editing detour',async()=>{
 mount();await flush()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true)
 expect(document.querySelector('.generation-live')?.textContent).toContain('正在编写歌词')
 expect(document.querySelector('.generation-stages')?.textContent).toContain('编写歌词')
 expect(document.body.textContent).not.toContain('查看已有歌词')
 expect(mocks.retry).not.toHaveBeenCalled()
})
it('keeps a dismissed dialog closed across polling and lets the user reopen it',async()=>{
 mount();await flush();click('收起，后台继续');await flush()
 mocks.latest.mockResolvedValue(flow('RUNNING',{revisionId:'revision-a',taskId:'task-a'}))
 await vi.advanceTimersByTimeAsync(4000);await flush()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(false)
 click('查看生成进度');await flush()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true)
 expect(document.querySelector('.generation-live')?.textContent).toContain('谱曲与演唱')
})
it('automatically reveals the result only after the saved audio can be displayed',async()=>{
 const {props,view}=mount();await flush()
 mocks.latest.mockResolvedValue(flow('SUCCEEDED',{revisionId:'revision-a',taskId:'task-a'}))
 await vi.advanceTimersByTimeAsync(4000);await flush()
 expect(view).not.toHaveBeenCalled()
 expect(document.querySelector<HTMLButtonElement>('.generation-primary')?.disabled).toBe(true)
 props.resultReady=true;await flush()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(false)
 expect(view).toHaveBeenCalledTimes(1)
 expect(document.querySelector('.quick-progress')).toBeNull()
})
it('shows a failure and explicit recovery choices instead of starting another paid request',async()=>{
 mount();await flush();mocks.latest.mockResolvedValue(flow('FAILED',{errorCode:'LYRICS_UNAVAILABLE',canRetry:true}))
 await vi.advanceTimersByTimeAsync(4000);await flush()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true)
 expect(document.body.textContent).toContain('故事已保留')
 expect(document.querySelector('a')?.getAttribute('href')).toContain('mode=custom')
 expect(mocks.retry).not.toHaveBeenCalled()
})
it('keeps an uncertain lyric result out of the retry path and allows a fresh song',async()=>{
 mocks.latest.mockResolvedValue(flow('SUBMISSION_UNKNOWN',{errorCode:'LYRICS_SUBMISSION_UNKNOWN'}))
 mount(false,'navigate');await flush()
 expect(document.querySelector('dialog h2')?.textContent).toBe('歌词结果待核对')
 expect(document.body.textContent).not.toContain('重试歌词生成')
 expect(document.querySelector('a')?.getAttribute('href')).toBe('/create?mode=quick')
 await vi.advanceTimersByTimeAsync(8000);await flush()
 expect(mocks.latest).toHaveBeenCalledTimes(1)
 click('刷新进度');await flush()
 expect(mocks.latest).toHaveBeenCalledTimes(2)
 expect(mocks.retry).not.toHaveBeenCalled()
})
it('recovers an initial progress read failure by querying only, never resubmitting',async()=>{
 mocks.latest.mockRejectedValueOnce(new Error('network'))
 mount();await flush()
 expect(document.querySelector('[role="alert"]')?.textContent).toContain('原任务不会重复提交')
 await vi.advanceTimersByTimeAsync(4000);await flush()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true)
 expect(mocks.retry).not.toHaveBeenCalled()
})
it('does not advertise cancellation before the quick workflow has created a music task',async()=>{
 mocks.latest.mockResolvedValue(flow('QUEUED'));mount();await flush()
 expect(document.querySelector('.generation-live')?.textContent).toContain('创作已排队')
 expect(document.body.textContent).not.toContain('撤销这次生成')
 expect(document.body.textContent).not.toContain('你仍可以撤销')
})
it('shows the music stage when lyrics are ready and the music task is queued',async()=>{
 mocks.latest.mockResolvedValue(flow('QUEUED',{revisionId:'revision-a',taskId:'task-a'}));mount();await flush()
 expect(document.querySelector('.generation-live')?.textContent).toContain('音乐已排队')
 expect(document.querySelector('.generation-stages .current')?.textContent).toContain('谱曲与演唱')
})
it('does not navigate away after the user minimizes a creation-page dialog',async()=>{
 const {props,view}=mount(false,'navigate');await flush();click('收起，后台继续');await flush()
 mocks.latest.mockResolvedValue(flow('SUCCEEDED',{revisionId:'revision-a',taskId:'task-a'}))
 await vi.advanceTimersByTimeAsync(4000);props.resultReady=true;await flush()
 expect(view).not.toHaveBeenCalled();expect(document.body.textContent).toContain('歌曲已保存')
 click('试听歌曲');expect(view).toHaveBeenCalledTimes(1)
})
it('navigates to the result when the user is still waiting in the creation-page dialog',async()=>{
 const {props,view}=mount(false,'navigate');await flush();mocks.latest.mockResolvedValue(flow('SUCCEEDED'))
 await vi.advanceTimersByTimeAsync(4000);props.resultReady=true;await flush()
 expect(view).toHaveBeenCalledTimes(1)
})
it('ignores backdrop clicks for quick creation but supports an explicit close',async()=>{
 mount();await flush();const dialog=document.querySelector<HTMLDialogElement>('dialog')!
 dialog.dispatchEvent(new MouseEvent('click',{bubbles:true,clientX:-10,clientY:-10}));await flush();expect(dialog.open).toBe(true)
 document.querySelector<HTMLButtonElement>('[aria-label="关闭生成进度弹窗"]')!.click();await flush();expect(dialog.open).toBe(false)
})

function mountStudioProgress(){
 const props=reactive({workId:'',dialogOpen:true,submissionStatus:'PREPARING',submissionPending:false,awaitingSubmission:false,initialFlow:null as QuickCreation|null,resultReady:false,completionMode:'navigate' as const})
 const view=vi.fn()
 app=createApp({render:()=>h(QuickCreationProgress,{...props,onVisibility:(value:boolean)=>{props.dialogOpen=value},onView:view})})
 app.mount(document.body.appendChild(document.createElement('div')))
 return {props,view}
}

it('keeps the same open dialog through preparing, submitting, queued and writing',async()=>{
 const {props}=mountStudioProgress();await flush()
 const dialog=document.querySelector<HTMLDialogElement>('dialog')!
 const status=()=>dialog.querySelector('.generation-live')?.textContent
 expect(dialog.open).toBe(true);expect(status()).toContain('正在保存创作要求')
 Object.assign(props,{workId:'work-a',submissionStatus:'SUBMITTING_QUICK',submissionPending:true})
 await vi.advanceTimersByTimeAsync(4000);await flush()
 expect(status()).toContain('正在提交你的音乐创作');expect(mocks.latest).not.toHaveBeenCalled()
 Object.assign(props,{submissionPending:false,initialFlow:flow('QUEUED')});await flush()
 expect(status()).toContain('创作已排队');expect(mocks.latest).not.toHaveBeenCalled()
 await vi.advanceTimersByTimeAsync(4000);await flush()
 expect(status()).toContain('正在编写歌词');expect(dialog.open).toBe(true)
 expect(document.querySelectorAll('dialog')).toHaveLength(1);expect(document.querySelector('dialog')).toBe(dialog)
})

it('queries an uncertain submission without reopening a dialog dismissed during preparation',async()=>{
 const {props,view}=mountStudioProgress();await flush()
 click('收起，后台继续');await flush()
 expect(props.dialogOpen).toBe(false)
 Object.assign(props,{workId:'work-a',submissionPending:true,submissionStatus:'SUBMITTING_QUICK'});await flush()
 expect(mocks.latest).not.toHaveBeenCalled()
 Object.assign(props,{submissionPending:false,awaitingSubmission:true,submissionStatus:'SUBMISSION_UNKNOWN'});await flush()
 expect(mocks.latest).toHaveBeenCalledOnce();expect(mocks.retry).not.toHaveBeenCalled()
 expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(false);expect(view).not.toHaveBeenCalled()
 click('查看生成进度');await flush();expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true)
})
