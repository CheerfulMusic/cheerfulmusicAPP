/** 只保存任务标识，不保存故事、歌词或凭据；刷新后仅查询，用户明确恢复时才重放原请求。 */
export interface QuickIntent {owner:string;workId:string;workVersion:number;requestKey:string;language:string;createdAt:number;minimized:boolean;flowId?:string}
export const quickIntentKey=(owner:string,work:string)=>`qingfeng.quick-intent:${owner}:${work}`
export function rememberQuickIntent(value:QuickIntent){try{localStorage.setItem(quickIntentKey(value.owner,value.workId),JSON.stringify(value));return true}catch{return false}}
export function recoverQuickIntent(owner:string,work:string):QuickIntent|undefined{
 try{
  const v=JSON.parse(localStorage.getItem(quickIntentKey(owner,work))||'null')
  if(v&&v.owner===owner&&v.workId===work&&Number.isInteger(v.workVersion)&&v.workVersion>=0&&/^[0-9a-f-]{36}$/i.test(v.requestKey)&&['zh-CN','en','mixed'].includes(v.language)&&Number.isFinite(v.createdAt)&&typeof v.minimized==='boolean')return {owner,workId:work,workVersion:v.workVersion,requestKey:v.requestKey,language:v.language,createdAt:v.createdAt,minimized:v.minimized,...(typeof v.flowId==='string'?{flowId:v.flowId}:{})}
 }catch{}
}
export function forgetQuickIntent(owner:string,work:string){try{localStorage.removeItem(quickIntentKey(owner,work))}catch{}}
