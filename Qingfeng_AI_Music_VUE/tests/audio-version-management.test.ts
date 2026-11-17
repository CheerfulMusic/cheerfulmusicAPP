// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,type App} from 'vue'
import AudioPanel from '../src/modules/works/components/AudioPanel.vue'
import type {AudioAsset} from '../src/modules/works/api/detail'
import type {Work} from '../src/modules/studio'
const api=vi.hoisted(()=>({list:vi.fn(),selection:vi.fn(),select:vi.fn(),save:vi.fn(),remove:vi.fn(),close:vi.fn(),queue:vi.fn()}))
vi.mock('../src/modules/works/api/detail',()=>({listAudio:api.list,saveAudioNote:api.save,deleteAudioVersion:api.remove,audioUrl:(w:string,a:string)=>`/audio/${w}/${a}`}))
vi.mock('../src/modules/works/api/section-edits',()=>({getAudioSelection:api.selection,selectAudio:api.select}))
vi.mock('../src/shared/player/store',()=>({usePlayer:()=>({setQueue:api.queue,close:api.close,tracks:[],updateTitles:vi.fn()})}))
const asset=(id:string,versionNumber:number):AudioAsset=>({id,versionNumber,note:'',noteVersion:0,workId:'work',origin:'GENERATED',contentType:'audio/mpeg',byteSize:12000,createdAt:'2026-11-18T00:00:00Z'})
const first=asset('a',1),second=asset('b',2),work={id:'work',title:'晚风'} as Work
let app:App|undefined
async function flush(){for(let i=0;i<12;i++)await nextTick()}
function mount(){app=createApp({render:()=>h(AudioPanel,{work,title:work.title,tasks:[]})});app.mount(document.body.appendChild(document.createElement('div')))}
function row(id='a'){return document.querySelector<HTMLInputElement>(`input[value="${id}"]`)!.closest('article')!}
function button(text:string,root:ParentNode=document){return [...root.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.trim()===text)!}
beforeEach(()=>{vi.clearAllMocks();api.list.mockResolvedValue([second,first]);api.selection.mockResolvedValue({asset:second,version:3});api.remove.mockResolvedValue({deleted:true,assetId:'a'});vi.spyOn(window,'confirm').mockReturnValue(true)})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML='';vi.restoreAllMocks()})
it('saves a private per-version note and renders the saved text after remount',async()=>{
 api.save.mockResolvedValue({...first,note:'婚礼入场用',noteVersion:1});mount();await flush();button('添加备注',row()).click();await flush()
 const textarea=row().querySelector('textarea')!;textarea.value='婚礼入场用';textarea.dispatchEvent(new Event('input'));button('保存备注',row()).click();await flush()
 expect(api.save).toHaveBeenCalledWith('work','a','婚礼入场用',0);expect(row().textContent).toContain('婚礼入场用');expect(api.select).not.toHaveBeenCalled()
 app!.unmount();app=undefined;document.body.innerHTML='';api.list.mockResolvedValue([second,{...first,note:'婚礼入场用',noteVersion:1}]);mount();await flush();expect(row().textContent).toContain('婚礼入场用')
})
it('retains unsaved text on save failure and cancels without a request',async()=>{
 api.save.mockRejectedValue(new Error('备注已修改，请刷新'));mount();await flush();button('添加备注',row()).click();await flush();const textarea=row().querySelector('textarea')!;textarea.value='保留这句';textarea.dispatchEvent(new Event('input'));button('保存备注',row()).click();await flush()
 expect(row().querySelector('textarea')!.value).toBe('保留这句');expect(row().textContent).toContain('备注已修改');button('取消',row()).click();await flush();expect(row().querySelector('textarea')).toBeNull();expect(api.save).toHaveBeenCalledTimes(1)
})
it('does not delete a selected version or a cancelled deletion',async()=>{
 mount();await flush();button('删除',row('b')).click();await flush();expect(api.remove).not.toHaveBeenCalled();expect(document.body.textContent).toContain('请先切换')
 vi.mocked(window.confirm).mockReturnValue(false);button('删除',row()).click();await flush();expect(api.remove).not.toHaveBeenCalled()
})
it('removes only the confirmed version and keeps remaining version numbers',async()=>{
 mount();await flush();button('删除',row()).click();await flush();expect(api.remove).toHaveBeenCalledWith('work','a');expect(document.querySelector('input[value="a"]')).toBeNull();expect(row('b').textContent).toContain('版本 2');expect(api.queue).toHaveBeenCalledWith([],false)
})
it('keeps the row and note when deletion is rejected by the server',async()=>{
 api.remove.mockRejectedValue(new Error('此版本已发布，请先更换公开版本或设为私密。'));api.list.mockResolvedValue([second,{...first,note:'公开的版本'}]);mount();await flush();button('删除',row()).click();await flush();expect(row().textContent).toContain('公开的版本');expect(document.body.textContent).toContain('此版本已发布');expect(api.queue).not.toHaveBeenCalled()
})
it('prevents repeat deletion while the request is pending',async()=>{
 let done!:(v:unknown)=>void;api.remove.mockReturnValue(new Promise(resolve=>done=resolve));mount();await flush();button('删除',row()).click();await flush();expect(row().querySelector<HTMLButtonElement>('.audio-delete')!.disabled).toBe(true);expect(row('b').querySelector<HTMLInputElement>('input')!.disabled).toBe(true);done({deleted:true,assetId:'a'});await flush();expect(api.remove).toHaveBeenCalledTimes(1)
})
