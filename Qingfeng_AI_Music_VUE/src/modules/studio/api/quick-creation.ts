import {ensureCreator,requestJson} from '../../../shared/http/api-client'
export interface QuickCreation {id:string;workId:string;status:string;revisionId:string|null;taskId:string|null;errorCode:string|null;canRetry:boolean;createdAt:string}
async function call<T>(path:string,method='GET',body?:unknown){await ensureCreator();return requestJson<T>(path,method,body)}
export const startQuickCreation=(work:string,workVersion:number,requestKey:string,language:string)=>call<QuickCreation>(`app/works/${work}/quick-creations`,'POST',{workVersion,requestKey,language})
export const latestQuickCreation=(work:string)=>call<QuickCreation|null>(`app/works/${work}/quick-creations/latest`).then(v=>v??null)
export const retryQuickCreation=(work:string,id:string)=>call<QuickCreation>(`app/works/${work}/quick-creations/${id}/retry`,'POST',{})
export const quickInProgress=(status:string)=>['QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC','MUSIC_QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN'].includes(status)
