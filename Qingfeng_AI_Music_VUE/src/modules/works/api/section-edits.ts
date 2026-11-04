import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import type { AudioAsset, GenerationTask } from './detail'
export interface SectionContext {available:boolean;reason:string|null;title:string|null;lyrics:string|null;duration:number|null;minimumSeconds:number;maximumSeconds:number;lines:{text:string;start:number;end:number}[]}
export interface SectionEdit {taskId:string;sourceAssetId:string;resultAssetId:string|null;start:number;end:number;replacementLyrics:string;status:string;errorCode:string|null;title:string;createdAt:string}
export interface AudioSelection {assetId:string|null;version:number;asset:AudioAsset|null}
export interface SectionInput {sourceAssetId:string;workVersion:number;requestKey:string;start:number;end:number;textStart:number;textEnd:number;replacementLyrics:string}
async function call<T>(path:string,method='GET',body?:unknown){await ensureCreator();return requestJson<T>(path,method,body,60000)}
export const sectionContext=(work:string,asset:string)=>call<SectionContext>(`app/works/${work}/audio/${asset}/section-context`)
export const listSectionEdits=(work:string)=>call<SectionEdit[]>(`app/works/${work}/section-edits`)
export const submitSectionEdit=(work:string,input:SectionInput)=>call<GenerationTask>(`app/works/${work}/section-edits`,'POST',input)
export const getAudioSelection=(work:string)=>call<AudioSelection>(`app/works/${work}/audio/selection`)
export const selectAudio=(work:string,assetId:string,version:number)=>call<AudioSelection>(`app/works/${work}/audio/selection`,'PUT',{assetId,version})
