import type {MusicCatalog,MusicPreferences,MusicRecipe} from '../api/music-settings'
import type {Work} from '../../studio'
import {splitStyle} from '../../agent/model/style-compiler'
export function validMusicPreferences(value:unknown,catalog:MusicCatalog):value is MusicPreferences{
 const p=value as MusicPreferences
 if(!p||p.version!==1||!['AUTO','MALE','FEMALE'].includes(p.vocalGender)||typeof p.freeText!=='string'||p.freeText.length>300||typeof p.negativeText!=='string'||p.negativeText.length>200||!p.choices||typeof p.choices!=='object'||Array.isArray(p.choices))return false
 if(p.recipeId!==null&&!catalog.recipes.some(r=>r.id===p.recipeId))return false
 return Object.entries(p.choices).every(([id,values])=>{const group=catalog.groups.find(g=>g.id===id);return !!group&&Array.isArray(values)&&values.length<=group.max&&new Set(values).size===values.length&&values.every(v=>typeof v==='string'&&group.options.some(o=>o.id===v))})
}
export function legacyPreferences(work:Work):MusicPreferences{
 const style=work.style||''
 // 作品风格可能带 Agent 编译出的补充描述（基础风格 | 补充描述）。
 // 分类判断只看基础风格，避免补充描述里的英文词误导主风格；补充描述本身作为自由要求保留。
 const {base,tail}=splitStyle(style)
 const genre=/R&B/.test(base)?'rnb':/爵士|波萨/.test(base)?'jazz':/摇滚/.test(base)?'rock':/中国风|国风|古韵/.test(base)?'chinese':/说唱/.test(base)?'rap':/电子|氛围/.test(base)?'electronic':/民谣|乡村/.test(base)?'folk':'pop'
 // 目录选项维持原有判断：补充描述里的乐器不重复登记，它已经作为自由要求参与提交。
 return {version:1,recipeId:null,choices:{genre:base==='自动推荐'?[]:[genre],instruments:/钢琴/.test(base)?['piano']:genre==='folk'?['guitar']:[]},vocalGender:work.vocalGender||'AUTO',freeText:base==='自动推荐'?tail:tail||style,negativeText:''}
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
