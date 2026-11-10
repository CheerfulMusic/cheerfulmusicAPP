import {computed,ref,onBeforeUnmount} from 'vue'
import {currentCreatorId,ApiError} from '../../../shared/http/api-client'
import {uncertainSubmission} from '../../works/model/generation-recovery'
import {continuationRequestKey} from './lyrics-continuation'
import {latestQuickCreation,startQuickCreation,type QuickCreation} from '../api/quick-creation'
import {rememberQuickIntent,recoverQuickIntent,forgetQuickIntent,quickIntentKey,type QuickIntent} from './quick-intent'

export function useQuickSession(){
 const intent=ref<QuickIntent>(),flow=ref<QuickCreation|null>(null),workId=ref(''),title=ref('')
 const preparing=ref(false),checking=ref(false),modalRequested=ref(false),minimized=ref(false),unknown=ref(false),issue=ref(''),recoverable=ref(true)
 let epoch=0,candidateWork='',alive=true,posting=false
 const locked=computed(()=>preparing.value||checking.value||!!intent.value||!!flow.value||unknown.value)
 const dialogOpen=computed(()=>modalRequested.value&&!minimized.value)
 const submissionPending=computed(()=>preparing.value&&!!intent.value&&!flow.value)
 const initialStatus=computed(()=>unknown.value?'SUBMISSION_UNKNOWN':intent.value?'SUBMITTING_QUICK':'PREPARING')
 const canResume=computed(()=>unknown.value&&!!intent.value&&!preparing.value&&!checking.value)
 function reset(){epoch++;candidateWork='';posting=false;intent.value=undefined;flow.value=null;workId.value='';title.value='';preparing.value=false;checking.value=false;modalRequested.value=false;minimized.value=false;unknown.value=false;issue.value='';recoverable.value=true}
 function prepare(){preparing.value=true;modalRequested.value=true;minimized.value=false;unknown.value=false;issue.value=''}
 function saveMarker(){if(intent.value)recoverable.value=rememberQuickIntent({...intent.value,minimized:minimized.value})}
 function visible(value:boolean){minimized.value=!value;saveMarker()}
 function close(){modalRequested.value=false;visible(false)}
 function show(){modalRequested.value=true;visible(true)}
 function abortPreparation(){if(!intent.value&&!flow.value){preparing.value=false;modalRequested.value=false}}
 function resolve(value:QuickCreation|null){
  if(value&&value.workId!==workId.value)return
  if(!value&&(flow.value||preparing.value))return
  if(value){
   flow.value=value;unknown.value=false;issue.value=''
   if(intent.value&&intent.value.flowId!==value.id){intent.value={...intent.value,flowId:value.id};saveMarker()}
  }
  else if(intent.value&&!flow.value&&!preparing.value){unknown.value=true;issue.value='提交结果仍待确认 请核对原任务 不要重复生成'}
  else if(!preparing.value){flow.value=null;workId.value='';unknown.value=false;issue.value=''}
 }
 async function restore(id:string,songTitle:string){
  const turn=epoch,owner=currentCreatorId();candidateWork=id;title.value=songTitle;checking.value=true
  const marker=recoverQuickIntent(owner,id)
  intent.value=marker;minimized.value=marker?.minimized??false
  try{
   const result=await latestQuickCreation(id)
   if(!alive||turn!==epoch||owner!==currentCreatorId())return
   if(result||marker){workId.value=id;title.value=songTitle;resolve(result);modalRequested.value=!minimized.value}
   else{flow.value=null;workId.value='';unknown.value=false;issue.value=''}
  }catch{
   if(alive&&turn===epoch&&owner===currentCreatorId()){workId.value=id;title.value=songTitle;unknown.value=true;issue.value='进度暂时无法读取 请稍后刷新 原任务不会重复提交';modalRequested.value=false}
  }finally{if(alive&&turn===epoch)checking.value=false}
 }
 async function post(saved:QuickIntent){
  if(posting)return
  posting=true
  const turn=epoch;preparing.value=true
  try{
   const accepted=await startQuickCreation(saved.workId,saved.workVersion,saved.requestKey,saved.language)
   if(!alive||turn!==epoch||saved.owner!==currentCreatorId())return
   if(!flow.value)resolve(accepted)
  }catch(e){
   if(!alive||turn!==epoch||saved.owner!==currentCreatorId())return
   if(flow.value)return // 查询已经确认受理时，迟到的提交超时不能把任务退回“未知”。
   if(uncertainSubmission(e instanceof ApiError?e:{})){
    unknown.value=true;issue.value='提交结果仍待确认 请核对原任务 不要重复生成'
   }else{
    // 明确拒绝时先读取本站已有任务，避免将另一页签已受理的创作当成新请求。
    if(e instanceof ApiError&&['QUICK_CREATION_PENDING','PERSONAL_GENERATION_BUSY'].includes(e.code||'')){
     try{const found=await latestQuickCreation(saved.workId);if(!alive||turn!==epoch||saved.owner!==currentCreatorId())return;if(found){resolve(found);return}}catch{}
    }
    if(!alive||turn!==epoch||saved.owner!==currentCreatorId())return
    forgetQuickIntent(saved.owner,saved.workId);intent.value=undefined;workId.value='';modalRequested.value=false;unknown.value=false
    throw e
   }
  }finally{if(alive&&turn===epoch){preparing.value=false;posting=false}}
 }
 async function submit(id:string,version:number,language:string,songTitle:string){
  if(intent.value||flow.value){if(!posting)preparing.value=false;return}
  const owner=currentCreatorId()
  const saved:QuickIntent={owner,workId:id,workVersion:version,requestKey:continuationRequestKey(id,version,language,'quick'),language,createdAt:Date.now(),minimized:minimized.value}
  intent.value=saved;workId.value=id;candidateWork=id;title.value=songTitle;saveMarker()
  await post(saved)
 }
 async function resume(){
  const saved=intent.value;if(!saved||!canResume.value||saved.owner!==currentCreatorId())return
  const turn=epoch;checking.value=true
  try{
   const found=await latestQuickCreation(saved.workId)
   if(!alive||turn!==epoch||saved.owner!==currentCreatorId())return
   if(found){resolve(found);return}
   if(flow.value)return
   show();await post(saved)
  }catch(e){if(alive&&turn===epoch)issue.value=e instanceof Error?e.message:'暂时无法核对原请求'}
  finally{if(alive&&turn===epoch)checking.value=false}
 }
 function storageChanged(event:StorageEvent){
  // 已接管的任务由当前页轮询。其他页签收起弹窗时，不改变当前页的展示状态。
  if(candidateWork&&!intent.value&&!flow.value&&!preparing.value&&!checking.value&&event.newValue&&event.key===quickIntentKey(currentCreatorId(),candidateWork))void restore(candidateWork,title.value)
 }
 window.addEventListener('storage',storageChanged)
 onBeforeUnmount(()=>{alive=false;epoch++;window.removeEventListener('storage',storageChanged)})
 return {intent,flow,workId,title,preparing,checking,locked,dialogOpen,submissionPending,initialStatus,minimized,unknown,issue,recoverable,canResume,reset,prepare,abortPreparation,close,show,visible,resolve,restore,submit,resume}
}
