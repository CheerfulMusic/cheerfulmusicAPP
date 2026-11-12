import type {Work} from '../../studio'
import type {GenerationTask} from '../api/detail'
import {loadMusicSettings,loadTaskMusicSettings,saveMusicSettings,type MusicPreferences} from '../api/music-settings'
import {ApiError} from '../../../shared/http/api-client'

function canonical(value:unknown):string{
 if(Array.isArray(value))return '['+value.map(canonical).join(',')+']'
 if(value&&typeof value==='object')return '{'+Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>JSON.stringify(k)+':'+canonical(v)).join(',')+'}'
 return JSON.stringify(value)??'null'
}
/** 仅在明确重新制作时恢复所选音频绑定的原设置，不使用最新任务的参数。 */
export async function restoreRemakeSettings(work:Work,source:GenerationTask,current:()=>boolean,chosen?:MusicPreferences):Promise<Work|null>{
 const [original,live]=await Promise.all([chosen?Promise.resolve(null):loadTaskMusicSettings(work.id,source.id),loadMusicSettings(work.id)])
 if(!current())return null
 if(live.work.version!==work.version)throw new ApiError('草稿已被其他页面修改，请重新载入后再保存',409,'WORK_VERSION_CONFLICT')
 const desired=chosen||original?.preferences
 if(!desired){
  // 旧任务无完整快照时，仅允许确认现有参数仍是原记录；不猜测丢失的高级选项。
  if(!live.direction&&source.style===live.work.style&&(source.vocalGender||'AUTO')===live.work.vocalGender)return live.work
  throw new Error('这首歌的原音乐设置记录不完整 请通过自定义创作重新选择')
 }
 if(live.direction&&canonical(desired)===canonical(live.direction.preferences))return live.work
 const preferences=JSON.parse(JSON.stringify(desired)) as MusicPreferences
 const saved=await saveMusicSettings(work.id,live.work.version,preferences)
 return current()?saved.work:null
}
