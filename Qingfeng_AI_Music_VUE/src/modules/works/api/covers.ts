import {ensureCreator,requestJson,uploadForm,apiUrl} from '../../../shared/http/api-client'
import {defaultCover} from '../../../shared/i18n'
export interface CoverAsset{id:string;origin:'UPLOADED'|'AI';createdAt:string}
export interface CoverEdit{id:string;sourceId:string;prompt:string;status:string;coverId:string|null;errorCode:string|null;createdAt:string}
export interface CoverState{currentCoverId:string|null;version:number;assets:CoverAsset[];edits:CoverEdit[];aiAvailable:boolean}
export function coverUrl(work:string,id?:string|null){return id?apiUrl(`app/works/${work}/covers/${id}`):defaultCover()}
export async function loadCovers(work:string){await ensureCreator();return requestJson<CoverState>(`app/works/${work}/covers`)}
export async function uploadCover(work:string,file:Blob){const form=new FormData();form.append('file',file,'cover.jpg');return uploadForm<CoverAsset>(`app/works/${work}/covers`,form)}
export async function selectCover(work:string,coverId:string|null,expectedVersion:number){await ensureCreator();return requestJson<CoverState>(`app/works/${work}/covers/selection`,'PUT',{coverId,expectedVersion})}
export async function editCover(work:string,sourceId:string,prompt:string,requestKey:string){await ensureCreator();return requestJson<CoverEdit>(`app/works/${work}/covers/edits`,'POST',{sourceId,prompt,requestKey})}
