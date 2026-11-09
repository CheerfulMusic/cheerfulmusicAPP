import type {DraftInput} from '../api/works'
import type {InspirationPreset} from './inspiration-presets'
export type CreationMode='quick'|'custom'
export const creationMode=(value:unknown,existing=false):CreationMode=>value==='custom'||value!=='quick'&&existing?'custom':'quick'
/** 音乐标签与作者素材分开：只有空白输入且明确选“没有灵感”时填入示例。 */
export function chooseCreationStyle(draft:DraftInput,preset:InspirationPreset,noIdea:boolean,translate:(text:string)=>string):DraftInput{
 return {...draft,style:preset.style,vocalGender:preset.vocalGender,story:noIdea&&!draft.story.trim()?translate(preset.description):draft.story}
}
