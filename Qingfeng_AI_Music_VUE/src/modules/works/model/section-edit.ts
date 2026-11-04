export interface LyricSlice {text:string;start:number;end:number}
export interface EditHint {text:string;start?:number;end?:number;occurrence?:number}
/** 偏移使用JS/Java一致的UTF-16计数，保留原换行和未修改文字。 */
export function lyricSlices(text:string):LyricSlice[]{
  const rows:LyricSlice[]=[]
  for(const match of text.matchAll(/[^\r\n]+/g)){
    const line=match[0]
    if(!line.trim()||/^\s*\[[^\]]+\]\s*$/.test(line))continue
    // 元数据标签与正文同一行时，保留整行供用户明确修改，避免静默删掉标签。
    rows.push({text:line,start:match.index,end:match.index+line.length})
  }
  return rows
}
export const comparableLyric=(text:string)=>text.replace(/\[[^\]]+\]/g,'').replace(/[\p{P}\p{Z}\s]/gu,'').toLocaleLowerCase()
export function hintedLine(rows:LyricSlice[],hint?:EditHint):number{
  if(!hint)return-1
  const text=comparableLyric(hint.text);if(!text)return-1
  const matches=rows.map((row,index)=>({row,index})).filter(({row})=>comparableLyric(row.text)===text)
  if(matches.length)return matches[hint.occurrence||0]?.index??-1
  const partial=rows.map((row,index)=>({row,index})).filter(({row})=>comparableLyric(row.text).includes(text))
  return partial.length===1?partial[0]!.index:-1
}
export function suggestedWindow(duration:number,hint?:EditHint):{start:number;end:number}{
  if(!Number.isFinite(duration)||duration<20)return{start:0,end:0}
  const limit=Math.floor(duration*50)/100
  const center=Number.isFinite(hint?.start)?Math.max(0,Math.min(duration,hint!.start!)):0
  let start=Math.max(0,center-2),end=Math.min(duration,Math.max(start+10,Math.min(center+limit,(hint?.end??center+8)+2)))
  if(end-start<10)start=Math.max(0,end-10)
  if(end-start>limit)end=start+limit
  return{start:Math.round(start*100)/100,end:Math.round(end*100)/100}
}
export function windowLines(rows:LyricSlice[],timed:{text:string;start:number;end:number}[],start:number,end:number):number[]{
  const occurrences=new Map<string,number>(),result:number[]=[]
  for(const line of timed){
    const text=comparableLyric(line.text),occurrence=occurrences.get(text)||0;occurrences.set(text,occurrence+1)
    if(line.start>=end||line.end<=start)continue
    const index=hintedLine(rows,{text:line.text,occurrence});if(index>=0)result.push(index)
  }
  return result
}
export function alignWindow(range:{start:number;end:number},timed:{start:number;end:number}[],duration:number){
  let {start,end}=range
  for(let pass=0;pass<timed.length;pass++){
    const overlaps=timed.filter(line=>line.start<end&&line.end>start)
    const nextStart=Math.max(0,Math.min(start,...overlaps.map(l=>l.start))),nextEnd=Math.min(duration,Math.max(end,...overlaps.map(l=>l.end)))
    if(nextStart===start&&nextEnd===end)break
    start=nextStart;end=nextEnd
  }
  return{start:Math.floor(start*100)/100,end:Math.ceil(end*100)/100}
}
export function validWindow(start:number,end:number,duration:number){return Number.isFinite(start)&&Number.isFinite(end)&&Number.isFinite(duration)&&start>=0&&end<=duration&&end-start>=10-1e-8&&end-start<=duration/2+1e-8}
export function replaceLyrics(source:string,from:number,to:number,replacement:string){return source.slice(0,from)+replacement.trim()+source.slice(to)}
