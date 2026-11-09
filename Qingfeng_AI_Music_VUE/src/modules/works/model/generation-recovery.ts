export function needsGenerationRefresh(tasks: readonly {status:string}[], awaitingResponse=false) {
  return awaitingResponse || tasks.some(task=>['QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN'].includes(task.status))
}

/** 仅保存请求标识与歌词版本，不保存歌词、故事或凭据。 */
export interface PendingGeneration {workId:string;revisionId:string;requestKey:string;createdAt:number}
const key=(owner:string,work:string)=>`qingfeng.pending-music:${owner}:${work}`
export function rememberGeneration(owner:string,pending:PendingGeneration,storage?:Pick<Storage,'setItem'>){
  try{(storage??localStorage).setItem(key(owner,pending.workId),JSON.stringify(pending));return true}catch{return false}
}
export function recoverGeneration(owner:string,work:string,storage?:Pick<Storage,'getItem'>):PendingGeneration|undefined{
  try{const value=JSON.parse((storage??localStorage).getItem(key(owner,work))||'null');if(value&&value.workId===work&&typeof value.revisionId==='string'&&value.revisionId&&typeof value.requestKey==='string'&&/^[0-9a-f-]{36}$/i.test(value.requestKey)&&Number.isFinite(value.createdAt))return value}catch{}
}
export function forgetGeneration(owner:string,work:string,storage?:Pick<Storage,'removeItem'>){try{(storage??localStorage).removeItem(key(owner,work))}catch{}}
export function uncertainSubmission(error:{status?:number;code?:string}){
  if(['MUSIC_PROVIDER_UNAVAILABLE','MUSIC_PROVIDER_COOLDOWN_UNAVAILABLE'].includes(error.code||''))return false
  return error.status===undefined||error.status===0||error.status===408||error.status>=500
}
export function generationProblem(code:string|null|undefined){
  switch(code){
    case 'LOCAL_PREPARATION_FAILED':case 'PROVIDER_CONNECT_FAILED':case 'PROVIDER_COOLDOWN':return '本次尚未提交音乐服务，内容已保留，可以稍后重试。'
    case 'PROVIDER_AUTH_FAILED':case 'PROVIDER_BALANCE_LOW':case 'PROVIDER_ENDPOINT_UNAVAILABLE':return '音乐生成服务暂不可用，内容已保留，已有歌曲仍可试听和分享。'
    case 'PROVIDER_RATE_LIMITED':return '音乐服务当前繁忙，请稍后再试，避免连续点击。'
    case 'PROVIDER_INPUT_REJECTED':return '音乐服务未接受这次要求，请调整音乐设置后再试。'
    case 'AUDIO_ARCHIVE_FAILED':return '音乐已经生成，但保存尚未完成；请仅重试保存音频，无需重新生成。'
    case 'QUERY_REVIEW_REQUIRED':case 'PROVIDER_DELAYED':return '等待超过预期，正在后台核对原任务。你可以先离开，稍后回来查看。'
    case 'PROVIDER_FAILED':case 'PROVIDER_REJECTED':return '音乐服务未完成这次创作，歌词和设置已保留。'
    case 'CHECKING_CLOSED':return '已结束本次等待。平台仍可能完成原任务，请避免连续发起相同创作。'
    default:return ''
  }
}
