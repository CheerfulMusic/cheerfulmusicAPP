import { afterEach, expect, it, vi } from 'vitest'
import { readRecovery, recoveryKey, writeRecovery,createRecoveryStore,migrateRecoveryOwner } from '../src/shared/drafts/recovery'
afterEach(()=>{vi.unstubAllGlobals();vi.useRealTimers()})
function storage(){const data=new Map<string,string>();vi.stubGlobal('localStorage',{get length(){return data.size},key:(n:number)=>[...data.keys()][n]||null,getItem:(k:string)=>data.get(k)||null,setItem:(k:string,v:string)=>data.set(k,v),removeItem:(k:string)=>data.delete(k)});return data}
const valid=(value:unknown):value is {text:string}=>!!value&&typeof (value as {text:string}).text==='string'
it('restores only the requested creator and work without mixing private stories',()=>{
  storage();const first=recoveryKey('a','story','w'),other=recoveryKey('b','story','w');writeRecovery(first,{text:'private story'})
  expect(readRecovery(first,valid)).toEqual({text:'private story'});expect(readRecovery(other,valid)).toBeUndefined();expect(readRecovery(recoveryKey('a','lyrics','w'),valid)).toBeUndefined()
})
it('expires stale drafts without clearing unrelated browser data',()=>{
  const data=storage();vi.useFakeTimers();vi.setSystemTime(new Date('2026-11-03T10:00:00Z'));const key=recoveryKey('a','story','w');writeRecovery(key,{text:'old'});data.set('qingfeng.locale','en')
  vi.advanceTimersByTime(8*24*60*60*1000);expect(readRecovery(key,valid)).toBeUndefined();expect(data.get('qingfeng.locale')).toBe('en')
})
it('ignores corrupt drafts and reports blocked browser storage',()=>{
  const data=storage(),key=recoveryKey('a','lyrics','w');data.set(key,'{broken');expect(readRecovery(key,valid)).toBeUndefined()
  vi.stubGlobal('localStorage',{getItem(){throw new Error('blocked')},setItem(){throw new Error('blocked')},get length(){throw new Error('blocked')}})
  expect(writeRecovery(key,{text:'still in the editor'})).toBe(false);expect(readRecovery(key,valid)).toBeUndefined()
})
it('R5 干净旧标签反复写入也不能覆盖另一个标签的脏副本',()=>{
 storage();const a=createRecoveryStore('tab-a'),b=createRecoveryStore('tab-b'),key=recoveryKey('owner','story','work')
 const check=(v:unknown):v is {draft:{story:string};dirty:boolean}=>!!v&&typeof (v as {draft?:{story?:unknown}}).draft?.story==='string'
 b.write(key,{draft:{story:'旧的服务器内容'},dirty:false});a.write(key,{draft:{story:'尚未保存的新故事'},dirty:true});b.write(key,{draft:{story:'旧的服务器内容'},dirty:false})
 expect(createRecoveryStore('reload').read(key,check)?.draft.story).toBe('尚未保存的新故事')
 expect(a.copies(key,check)).toHaveLength(2)
})
it('R5 两个脏副本分别保留，选择恢复前可另存当前编辑',()=>{
 storage();const a=createRecoveryStore('a'),b=createRecoveryStore('b'),key=recoveryKey('owner','lyrics','work')
 const check=(v:unknown):v is {text:string;dirty:boolean}=>valid(v)&&typeof (v as {dirty?:unknown}).dirty==='boolean'
 a.write(key,{text:'歌词A',dirty:true});b.write(key,{text:'歌词B',dirty:true});a.checkpoint(key,{text:'歌词A',dirty:true});a.write(key,{text:'歌词B',dirty:true})
 expect(a.copies(key,check).map(c=>c.value.text)).toContain('歌词A');expect(a.copies(key,check).map(c=>c.value.text)).toContain('歌词B')
})
it('R5 无内容变化的旧页面卸载不会获得更新的编辑时间',()=>{
 storage();vi.useFakeTimers();vi.setSystemTime(10000);const a=createRecoveryStore('a'),b=createRecoveryStore('b'),key=recoveryKey('o','story','w')
 b.write(key,{text:'旧内容',dirty:false});vi.advanceTimersByTime(100);a.write(key,{text:'新内容',dirty:false});vi.advanceTimersByTime(100);b.write(key,{text:'旧内容',dirty:false})
 expect(a.read(key,valid)?.text).toBe('新内容')
})
it('R5 清理过期版本与另一标签新写入交错时，不会删除新内容',()=>{
 const data=storage();vi.useFakeTimers();vi.setSystemTime(10000)
 const a=createRecoveryStore('a'),b=createRecoveryStore('b'),key=recoveryKey('o','story','w')
 a.write(key,{text:'过期内容',dirty:true});const expired=[...data.keys()][0]!
 vi.advanceTimersByTime(8*24*60*60*1000)
 const remove=localStorage.removeItem.bind(localStorage);let armed=true
 localStorage.removeItem=(name:string)=>{if(name===expired&&armed){armed=false;a.write(key,{text:'恰好同时写入的新内容',dirty:true})}remove(name)}
 b.write(key,{text:'另一标签的内容',dirty:false})
 expect(a.copies(key,valid).map(c=>c.value.text)).toContain('恰好同时写入的新内容')
})

it('login association copies all guest branches, keeps target edits and is idempotent after reload',()=>{
 const data=storage();const a=createRecoveryStore('guest-tab'),b=createRecoveryStore('account-tab')
 const guest=recoveryKey('guest','story','work'),target=recoveryKey('account','story','work'),other=recoveryKey('other','story','work')
 a.write(guest,{text:'尚未保存的游客故事',dirty:true});a.checkpoint(guest,{text:'另一个游客副本',dirty:true})
 b.write(target,{text:'账号原有编辑',dirty:true});a.write(other,{text:'其他账号的隐私',dirty:true})
 const guestNames=[...data.keys()].filter(key=>key.startsWith(guest+':tab:'))
 expect(migrateRecoveryOwner('guest','account')).toBe(true)
 const copies=createRecoveryStore('after-redirect').copies(target,valid)
 expect(copies.map(c=>c.value.text)).toEqual(expect.arrayContaining(['尚未保存的游客故事','另一个游客副本','账号原有编辑']))
 expect(copies.map(c=>c.value.text)).not.toContain('其他账号的隐私')
 expect(guestNames.every(key=>data.has(key))).toBe(true)
 const count=data.size;expect(migrateRecoveryOwner('guest','account')).toBe(true);expect(data.size).toBe(count)
})
it('association keeps original timestamps and never revives expired drafts',()=>{
 const data=storage();vi.useFakeTimers();vi.setSystemTime(10000)
 const guest=recoveryKey('g','lyrics','w'),target=recoveryKey('a','lyrics','w')
 writeRecovery(guest,{text:'旧歌词',dirty:true});vi.advanceTimersByTime(8*24*60*60*1000)
 expect(migrateRecoveryOwner('g','a')).toBe(true);expect(readRecovery(target,valid)).toBeUndefined()
 data.set(recoveryKey('g','story','broken'),'null');expect(migrateRecoveryOwner('g','a')).toBe(true)
})
it('failed copy preserves originals and can resume without overwriting account drafts',()=>{
 const data=storage(),guest=recoveryKey('g','story','w'),target=recoveryKey('a','story','w')
 writeRecovery(guest,{text:'保留的故事',dirty:true});const original=[...data.entries()]
 const set=localStorage.setItem.bind(localStorage);localStorage.setItem=()=>{throw new Error('quota')}
 expect(migrateRecoveryOwner('g','a')).toBe(false);expect([...data.entries()]).toEqual(original)
 localStorage.setItem=set;expect(migrateRecoveryOwner('g','a')).toBe(true)
 expect(readRecovery(target,valid)?.text).toBe('保留的故事')
})
