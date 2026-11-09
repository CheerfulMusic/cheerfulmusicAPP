import type {MusicCatalog,MusicPreferences,MusicRecipe} from '../api/music-settings'
import type {Work} from '../../studio'
export function validMusicPreferences(value:unknown,catalog:MusicCatalog):value is MusicPreferences{
 const p=value as MusicPreferences
 if(!p||p.version!==1||!['AUTO','MALE','FEMALE'].includes(p.vocalGender)||typeof p.freeText!=='string'||p.freeText.length>300||typeof p.negativeText!=='string'||p.negativeText.length>200||!p.choices||typeof p.choices!=='object'||Array.isArray(p.choices))return false
 if(p.recipeId!==null&&!catalog.recipes.some(r=>r.id===p.recipeId))return false
 return Object.entries(p.choices).every(([id,values])=>{const group=catalog.groups.find(g=>g.id===id);return !!group&&Array.isArray(values)&&values.length<=group.max&&new Set(values).size===values.length&&values.every(v=>typeof v==='string'&&group.options.some(o=>o.id===v))})
}
export function legacyPreferences(work:Work):MusicPreferences{
 const style=work.style||'',genre=/R&B/.test(style)?'rnb':/爵士|波萨/.test(style)?'jazz':/摇滚/.test(style)?'rock':/中国风|国风|古韵/.test(style)?'chinese':/说唱/.test(style)?'rap':/电子|氛围/.test(style)?'electronic':/民谣|乡村/.test(style)?'folk':'pop'
 return {version:1,recipeId:null,choices:{genre:style==='自动推荐'?[]:[genre],instruments:/钢琴/.test(style)?['piano']:genre==='folk'?['guitar']:[]},vocalGender:work.vocalGender||'AUTO',freeText:style==='自动推荐'?'':style,negativeText:''}
}
export function applyRecipe(current:MusicPreferences,recipe:MusicRecipe):MusicPreferences{
 return {...current,recipeId:recipe.id,choices:{...Object.fromEntries(Object.entries(recipe.choices).map(([key,values])=>[key,[...values]])),exclude:[...(current.choices.exclude||[])]}}
}
export function toggleMusicOption(current:MusicPreferences,catalog:MusicCatalog,groupId:string,option:string):MusicPreferences|null{
 const group=catalog.groups.find(g=>g.id===groupId);if(!group||!group.options.some(o=>o.id===option))return null
 const values=current.choices[groupId]||[];let next:string[]
 if(values.includes(option))next=values.filter(v=>v!==option)
 else if(group.max===1)next=[option]
 else {if(values.length>=group.max)return null;next=[...values,option]}
 return {...current,recipeId:null,choices:{...current.choices,[groupId]:next}}
}
