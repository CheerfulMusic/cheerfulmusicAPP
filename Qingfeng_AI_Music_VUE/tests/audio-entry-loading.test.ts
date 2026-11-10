// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,type App} from 'vue'
import AudioPanel from '../src/modules/works/components/AudioPanel.vue'
import type {AudioAsset} from '../src/modules/works/api/detail'
import type {Work} from '../src/modules/studio'
const api=vi.hoisted(()=>({list:vi.fn(),selection:vi.fn(),select:vi.fn(),toggle:vi.fn(),queue:vi.fn()}))
vi.mock('../src/modules/works/api/detail',()=>({listAudio:api.list,audioUrl:(w:string,a:string)=>`/audio/${w}/${a}`}))
vi.mock('../src/modules/works/api/section-edits',()=>({getAudioSelection:api.selection,selectAudio:api.select}))
vi.mock('../src/shared/player/store',()=>({usePlayer:()=>({setQueue:api.queue,toggle:api.toggle})}))
const asset=(id='audio-a',sectionEdit=false):AudioAsset=>({id,sectionEdit,workId:'work-a',origin:'GENERATED',contentType:'audio/mpeg',byteSize:12000,createdAt:'2026-11-10T00:00:00Z'})
const work={id:'work-a',title:'晚风',recipient:''} as Work
function deferred<T>(){let resolve!:(v:T)=>void,reject!:(e:Error)=>void;const promise=new Promise<T>((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
async function flush(){for(let i=0;i<12;i++)await nextTick()}
let app:App|undefined
function mount(){const selected=vi.fn(),state=vi.fn();app=createApp({render:()=>h(AudioPanel,{work,onSelected:selected,'onLoad-state':state})});app.mount(document.body.appendChild(document.createElement('div')));return {selected,state}}
beforeEach(()=>{vi.clearAllMocks();api.list.mockResolvedValue([asset()]);api.selection.mockResolvedValue({asset:asset(),version:3});api.select.mockResolvedValue({asset:asset(),version:4})})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML=''})
it('publishes the selected song while the version list is still pending',async()=>{
 const list=deferred<AudioAsset[]>();api.list.mockReturnValue(list.promise);const {selected,state}=mount();await flush();expect(selected).toHaveBeenCalledWith(asset());expect(state).toHaveBeenCalledWith('ready');expect(api.select).not.toHaveBeenCalled();list.resolve([asset()]);await flush()
})
it('keeps a selected recording usable when the version list fails',async()=>{
 api.list.mockRejectedValue(new Error('offline'));const {selected,state}=mount();await flush();expect(selected).toHaveBeenCalledWith(asset());expect(state.mock.lastCall).toEqual(['ready']);expect(document.body.textContent).toContain('已有歌曲仍可试听')
})
it('falls back to a complete non-edit recording if the selection read fails',async()=>{
 api.selection.mockRejectedValue(new Error('offline'));api.list.mockResolvedValue([asset('edit',true),asset()]);const {selected,state}=mount();await flush();expect(selected).toHaveBeenCalledWith(asset());expect(state.mock.lastCall).toEqual(['ready']);expect(api.select).not.toHaveBeenCalled()
})
it('does not declare an empty draft when only one read succeeded',async()=>{
 api.selection.mockRejectedValue(new Error('offline'));api.list.mockResolvedValue([]);const {selected,state}=mount();await flush();expect(state.mock.lastCall).toEqual(['failed']);expect(selected).not.toHaveBeenCalled()
})
it('declares no audio only after both successful reads agree',async()=>{
 const selection=deferred<{asset:null;version:number}>();api.list.mockResolvedValue([]);api.selection.mockReturnValue(selection.promise);const {state}=mount();await flush();expect(state.mock.calls).toEqual([['loading']]);selection.resolve({asset:null,version:0});await flush();expect(state.mock.lastCall).toEqual(['ready'])
})
it('retries reads explicitly after failure without creating or choosing audio',async()=>{
 api.list.mockRejectedValue(new Error('offline'));api.selection.mockRejectedValue(new Error('offline'));const {state,selected}=mount();await flush();expect(state.mock.lastCall).toEqual(['failed']);api.list.mockResolvedValue([asset()]);api.selection.mockResolvedValue({asset:asset(),version:3});document.querySelector<HTMLButtonElement>('button')!.click();await flush();expect(state.mock.lastCall).toEqual(['ready']);expect(selected).toHaveBeenCalledWith(asset());expect(api.select).not.toHaveBeenCalled()
})
it('ignores late responses after the song page unmounts',async()=>{
 const pending=deferred<{asset:AudioAsset;version:number}>();api.selection.mockReturnValue(pending.promise);const {selected,state}=mount();await flush();app!.unmount();app=undefined;pending.resolve({asset:asset(),version:3});await flush();expect(selected).not.toHaveBeenCalled();expect(state.mock.calls).toEqual([['loading']])
})
it('preserves version preview when the selection cannot be saved',async()=>{
 api.select.mockRejectedValue(new Error('changed'));mount();await flush();document.querySelector<HTMLButtonElement>('.audio-actions button')!.click();await flush();expect(api.toggle).toHaveBeenCalledWith(expect.objectContaining({id:'audio-a'}));expect(document.body.textContent).toContain('changed')
})
