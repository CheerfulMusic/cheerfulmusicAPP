import {ensureCreator,requestJson} from '../../../shared/http/api-client'
import type {Work} from '../../studio'
export interface MusicPreferences {version:1;recipeId:string|null;choices:Record<string,string[]>;vocalGender:'AUTO'|'MALE'|'FEMALE';freeText:string;negativeText:string}
export interface MusicOption {id:string;zh:string;en:string;prompt:string}
export interface MusicGroup {id:string;zh:string;en:string;max:number;options:MusicOption[]}
export interface MusicRecipe {id:string;zh:string;en:string;choices:Record<string,string[]>}
export interface MusicCatalog {version:number;groups:MusicGroup[];recipes:MusicRecipe[]}
export interface MusicDirection {preferences:MusicPreferences;style:string;negativeTags:string;summaryZh:string;summaryEn:string;catalogVersion:number;compilation?:{styleCharacters:number;styleLimit:number;negativeCharacters:number;negativeLimit:number;simplified:boolean;notice:string}|null}
export interface PhotoInspiration {summary:string;arrangement:string}
export interface MusicSettingsView {work:Work;direction:MusicDirection|null;photoInspiration?:PhotoInspiration|null}
async function call<T>(path:string,method='GET',body?:unknown){await ensureCreator();return requestJson<T>(path,method,body)}
export const loadMusicCatalog=()=>call<MusicCatalog>('app/music-settings/catalog')
export const loadMusicSettings=(work:string)=>call<MusicSettingsView>(`app/works/${work}/music-settings`)
export const previewMusicSettings=(preferences:MusicPreferences)=>call<MusicDirection>('app/music-settings/preview','POST',preferences)
export const previewWorkMusicSettings=(work:string,preferences:MusicPreferences)=>call<MusicDirection>(`app/works/${work}/music-settings/preview`,'POST',preferences)
export const musicBriefAvailable=()=>call<{available:boolean}>('app/music-settings/simplify').then(value=>value.available)
export async function suggestMusicBrief(text:string){await ensureCreator();return requestJson<{original:string;text:string}>('app/music-settings/simplify','POST',{text},20000)}
export const saveMusicSettings=(work:string,expectedVersion:number,preferences:MusicPreferences)=>call<MusicSettingsView>(`app/works/${work}/music-settings`,'PUT',{expectedVersion,preferences})
export const loadTaskMusicSettings=(work:string,task:string)=>call<MusicDirection|null>(`app/works/${work}/generations/${task}/music-settings`).then(value=>value?.preferences?value:null)
