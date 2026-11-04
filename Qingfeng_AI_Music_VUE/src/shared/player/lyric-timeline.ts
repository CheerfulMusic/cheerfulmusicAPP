import type { LyricLine } from '../../modules/works/api/detail'

/** 时间轴已按开始时间排序。前奏、间奏和尾奏期间不把尚未唱到的句子标成正在演唱。 */
export function lyricPosition(lines:readonly LyricLine[],time:number):{active:number;anchor:number} {
  if(!Number.isFinite(time)||!lines.length)return{active:-1,anchor:0}
  let low=0,high=lines.length-1,last=-1
  while(low<=high){const middle=(low+high)>>>1;if(lines[middle]!.start<=time){last=middle;low=middle+1}else high=middle-1}
  return{active:last>=0&&time<lines[last]!.end?last:-1,anchor:Math.max(0,last)}
}
export function plainLyricLines(text:string):string[]{
  return text.replace(/\[(?:verse(?:\s*\d+)?|chorus(?:\s*\d+)?|pre[- ]?chorus|bridge|intro|outro|instrumental|interlude|hook|主歌\s*\d*|副歌\s*\d*|桥段|前奏|尾奏|间奏)\]/gi,'').split(/\r?\n/).map(line=>line.trim()).filter(Boolean)
}
