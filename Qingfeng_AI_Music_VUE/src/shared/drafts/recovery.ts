const prefix='qingfeng.recovery.v1:'
const maxAge=7*24*60*60*1000
interface Envelope<T>{savedAt:number;contentAt?:number;value:T;immutable?:boolean}
export interface RecoveryCopy<T>{id:string;savedAt:number;value:T}
export function recoveryKey(owner:string,kind:'story'|'lyrics',id:string){return `${prefix}${owner}:${kind}:${id}`}
function content(value:unknown):string{
 if(!value||typeof value!=='object')return JSON.stringify(value)
 const ignored=new Set(['dirty','conflict','part','flowVersion','workId','draftKey','version','workVersion','baseId'])
 return JSON.stringify(Object.fromEntries(Object.entries(value).filter(([key])=>!ignored.has(key)).sort(([a],[b])=>a.localeCompare(b))))
}
/** 每个文档实例只写自己的分支；localStorage没有CAS，不能靠读后覆盖保护别的标签。 */
export function createRecoveryStore(writer:string=crypto.randomUUID()){
 const ownKeys=new Map<string,string>()
 const branch=(key:string)=>ownKeys.get(key)
 function copies<T>(key:string,validate:(value:unknown)=>value is T):RecoveryCopy<T>[] {
  const result:RecoveryCopy<T>[]=[]
  try{const names=[key];for(let n=0;n<localStorage.length;n++){const name=localStorage.key(n);if(name?.startsWith(key+':tab:'))names.push(name)}
   for(const name of names){try{const raw=localStorage.getItem(name);if(!raw)continue;const entry=JSON.parse(raw) as Envelope<unknown>;if(!Number.isFinite(entry.savedAt)||Date.now()-entry.savedAt>maxAge){if(entry.immutable||name===branch(key))localStorage.removeItem(name);continue}if(validate(entry.value))result.push({id:name,savedAt:entry.contentAt??entry.savedAt,value:entry.value})}catch{/* 损坏分支不影响其他副本。 */}}
  }catch{/* 无法使用本机存储时返回空列表。 */}
  return result.sort((a,b)=>b.savedAt-a.savedAt||a.id.localeCompare(b.id))
 }
 function read<T>(key:string,validate:(value:unknown)=>value is T):T|undefined {
  const all=copies(key,validate),clean=all.filter(c=>!(c.value as {dirty?:boolean}).dirty)
  const unsaved=all.filter(c=>(c.value as {dirty?:boolean}).dirty&&!clean.some(saved=>saved.savedAt>=c.savedAt&&content(saved.value)===content(c.value)))
  return (unsaved[0]||all[0])?.value
 }
 function write<T>(key:string,value:T):boolean{
  try{
   // 每次先写新的不可变版本，再删除本实例的旧版本。清理过期分支不会撞上另一个标签的新写入。
   for(let n=localStorage.length-1;n>=0;n--){const name=localStorage.key(n);if(!name?.startsWith(prefix))continue;try{const old=JSON.parse(localStorage.getItem(name)||'{}') as Envelope<unknown>;if(old.immutable&&Date.now()-old.savedAt>maxAge)localStorage.removeItem(name)}catch{}}
   const previous=ownKeys.get(key),raw=previous?localStorage.getItem(previous):null,old=raw?JSON.parse(raw) as Envelope<T>:undefined
   const same=old&&content(old.value)===content(value),name=`${key}:tab:${writer}:${crypto.randomUUID()}`
   localStorage.setItem(name,JSON.stringify({savedAt:Date.now(),contentAt:same?(old.contentAt??old.savedAt):Date.now(),value,immutable:true} satisfies Envelope<T>))
   ownKeys.set(key,name);if(previous)localStorage.removeItem(previous);return true
  }catch{return false}
 }
 function checkpoint<T>(key:string,value:T):boolean{try{localStorage.setItem(`${key}:tab:checkpoint-${crypto.randomUUID()}`,JSON.stringify({savedAt:Date.now(),contentAt:Date.now(),value,immutable:true} satisfies Envelope<T>));return true}catch{return false}}
 function remove(key:string){try{const own=branch(key);if(own)localStorage.removeItem(own);ownKeys.delete(key)}catch{/* 不删除其他标签的恢复分支。 */}}
 return {read,write,copies,checkpoint,remove}
}
const store=createRecoveryStore()
export const readRecovery=store.read,writeRecovery=store.write,listRecoveryCopies=store.copies,checkpointRecovery=store.checkpoint,removeRecovery=store.remove

/** Copy immutable recovery branches only for a guest association confirmed by the session endpoint. */
export function migrateRecoveryOwner(source:string,target:string):boolean {
 if(!source||!target||source===target||source.includes(':')||target.includes(':'))return source===target
 try{
  const from=prefix+source+':',to=prefix+target+':',names:string[]=[]
  for(let n=0;n<localStorage.length;n++){const key=localStorage.key(n);if(key?.startsWith(from))names.push(key)}
  for(const key of names){
   const tail=key.slice(from.length);if(!/^(story|lyrics):[^:]+/.test(tail))continue
   const raw=localStorage.getItem(key);if(!raw)continue
   let entry:Envelope<unknown>;try{entry=JSON.parse(raw)}catch{continue}
   if(!entry||typeof entry!=='object'||!Number.isFinite(entry.savedAt)||Date.now()-entry.savedAt>maxAge)continue
   const split=tail.indexOf(':tab:'),base=split<0?tail:tail.slice(0,split),branch=split<0?'legacy':tail.slice(split+5)
   let destination=`${to}${base}:tab:claimed-${source}:${branch}:${entry.savedAt}`
   const existing=localStorage.getItem(destination)
   if(existing===raw)continue
   if(existing!==null)destination+=`:copy:${crypto.randomUUID()}`
   localStorage.setItem(destination,raw) // Retain source, timestamps, and all target branches.
  }
  return true
 }catch{return false}
}
