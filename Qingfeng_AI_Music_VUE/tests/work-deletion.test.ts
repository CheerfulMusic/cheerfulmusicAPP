// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,h,nextTick,type App} from 'vue'
import WorkLibraryPage from '../src/modules/works/pages/WorkLibraryPage.vue'
import {deleteWork} from '../src/modules/works/api/works'
import {requestJson,resetCreatorSession} from '../src/shared/http/api-client'
const player=vi.hoisted(()=>({current:null as null|{audio:string},reset:vi.fn(),setQueue:vi.fn(),toggle:vi.fn(),playing:false}))
vi.mock('../src/shared/player/store',()=>({usePlayer:()=>player}))
const work={id:'11111111-1111-4111-8111-111111111111',version:0,recipient:'',occasion:'',story:'晚风里的故事',message:'',style:'民谣',title:'我的歌',vocalGender:'AUTO',createdAt:'2026-11-10T00:00:00Z',updatedAt:'2026-11-10T00:00:00Z'}
const row={work,status:'LYRICS_DRAFT',audioId:null,audioOrigin:null,songTitle:null,coverId:null}
const reply=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status})
let app:App|undefined,list:unknown[],deleteResult:()=>Promise<Response>,listReads:number,deleted:number
const flush=async()=>{for(let i=0;i<10;i++){await nextTick();await new Promise(r=>setTimeout(r,0))}}
function mount(){app=createApp({render:()=>h(WorkLibraryPage)});app.component('RouterLink',{props:['to'],template:'<a :href="to"><slot/></a>'});app.mount(document.body.appendChild(document.createElement('div')))}
function button(label:string){const value=[...document.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.trim()===label);expect(value).toBeTruthy();return value!}
async function ask(){button('删除作品').click();await flush()}
beforeEach(()=>{
 vi.clearAllMocks();resetCreatorSession();localStorage.clear();sessionStorage.clear();player.current=null;list=[row];listReads=0;deleted=0
 Object.defineProperty(HTMLDialogElement.prototype,'showModal',{configurable:true,value(){this.open=true}})
 Object.defineProperty(HTMLDialogElement.prototype,'close',{configurable:true,value(){this.open=false}})
 deleteResult=async()=>{list=[];return reply({deleted:true})}
 vi.stubGlobal('fetch',vi.fn(async(input:string,options?:RequestInit)=>{
  const path=String(input),method=options?.method||'GET'
  if(path.endsWith('/csrf'))return reply({token:'csrf'})
  if(path.endsWith('/session'))return reply({creatorId:'owner'})
  if(path.includes('/works/library')){listReads++;return reply(list)}
  if(method==='DELETE'){deleted++;return deleteResult()}
  throw new Error('Unexpected request '+path)
 }))
})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML='';vi.unstubAllGlobals();resetCreatorSession();vi.restoreAllMocks()})
it('accepts the current confirmation response and sends the completion acknowledgment',async()=>{
 await expect(deleteWork(work.id,true)).resolves.toEqual({deleted:true});expect(deleted).toBe(1)
 expect(vi.mocked(fetch).mock.calls.at(-1)?.[0]).toContain('acknowledgeCompleted=true')
})
it('accepts a complete legacy deleted work with matching id and normalizes the result',async()=>{
 deleteResult=async()=>reply(work);await expect(deleteWork(work.id)).resolves.toEqual({deleted:true});expect(deleted).toBe(1)
})
it('rejects a legacy work with another id and incomplete, empty or arbitrary success payloads',async()=>{
 for(const value of [{...work,id:'another'}, {}, {id:work.id}, {success:true}, {deleted:false}]){
  deleteResult=async()=>reply(value);await expect(deleteWork(work.id)).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 }
})
it('never turns an unrelated 404 into deletion success in the API client',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>reply({code:'WORK_NOT_FOUND',message:'作品不存在或你无权访问'},404)))
 await expect(requestJson('app/works/'+work.id,'DELETE')).rejects.toMatchObject({status:404,code:'WORK_NOT_FOUND'})
})
it('closes the dialog after legacy 200 instead of showing an error and deleting twice',async()=>{
 deleteResult=async()=>{list=[];return reply(work)};mount();await flush();await ask();button('确认删除').click();await flush()
 expect(deleted).toBe(1);expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(false);expect(document.querySelector('.library-song')).toBeNull();expect(document.body.textContent).toContain('作品已删除')
})
it('refreshes and removes an expired card after 404 without claiming a new deletion',async()=>{
 deleteResult=async()=>{list=[];return reply({code:'WORK_NOT_FOUND',message:'作品不存在或你无权访问'},404)};mount();await flush();await ask();button('确认删除').click();await flush()
 expect(document.querySelector('.library-song')).toBeNull();expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(false);expect(document.body.textContent).toContain('作品已不在当前列表中');expect(listReads).toBe(2)
})
it('retains the error if a 404 work is still returned by the list',async()=>{
 deleteResult=async()=>reply({code:'WORK_NOT_FOUND',message:'作品不存在或你无权访问'},404);mount();await flush();await ask();button('确认删除').click();await flush()
 expect(document.querySelector('.library-song')).not.toBeNull();expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true);expect(document.querySelector('.work-delete-dialog .notice')?.textContent).toContain('作品不存在或你无权访问')
})
it('preserves the card and confirmation dialog when deletion fails with 500',async()=>{
 deleteResult=async()=>reply({message:'server error'},500);mount();await flush();await ask();button('确认删除').click();await flush()
 expect(document.querySelector('.library-song')).not.toBeNull();expect(document.querySelector<HTMLDialogElement>('dialog')?.open).toBe(true);expect(deleted).toBe(1)
})
it('shows the completed-song warning before sending explicit acknowledgment',async()=>{
 list=[{...row,status:'COMPLETED',audioId:'audio-a',audioOrigin:'GENERATED'}];mount();await flush();await ask()
 expect(deleted).toBe(0);expect(document.querySelector('.delete-warning')?.textContent).toContain('请先下载');button('确认删除').click();await flush()
 expect(vi.mocked(fetch).mock.calls.some(([path,init])=>String(path).includes('acknowledgeCompleted=true')&&init?.method==='DELETE')).toBe(true)
})
it('does not remove the new account data when identity changes during deletion',async()=>{
 let finish!:(value:Response)=>void;deleteResult=()=>new Promise(r=>finish=r);mount();await flush();await ask();button('确认删除').click();await flush()
 list=[{...row,work:{...work,id:'new-account-work',title:'另一个账号的歌'}}];window.dispatchEvent(new Event('qingfeng:identity-changed'));await flush();finish(reply(work));await flush()
 expect(document.body.textContent).toContain('另一个账号的歌');expect(document.body.textContent).not.toContain('作品已删除')
})
