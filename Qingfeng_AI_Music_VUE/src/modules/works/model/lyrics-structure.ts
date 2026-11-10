export const lyricTemplates = {
  AAB: ['Verse 1','Verse 2','Chorus','Interlude','Verse 2','Chorus','Chorus 2'],
  ABB: ['Verse','Chorus','Chorus 2','Interlude','Verse','Chorus','Chorus 2'],
  ABC: ['Verse','Pre-Chorus','Chorus','Interlude','Pre-Chorus','Chorus','Chorus 2'],
  ABCD: ['Verse','Pre-Chorus','Chorus','Interlude','Pre-Chorus','Chorus','Bridge','Chorus'],
} as const
export type LyricsTemplateId = 'AUTO' | keyof typeof lyricTemplates
export interface LyricsStructure { templateId: LyricsTemplateId; chorusFirst: boolean }
export function normalizeLyricsStructure(value?: {templateId?: unknown; chorusFirst?: unknown}): LyricsStructure {
  const templateId = typeof value?.templateId === 'string' && Object.hasOwn(lyricTemplates,value.templateId) ? value.templateId as keyof typeof lyricTemplates : 'AUTO'
  return {templateId,chorusFirst:templateId!=='AUTO'&&value?.chorusFirst===true}
}
export function lyricSections(value: LyricsStructure): string[] {
  const sections: string[] = value.templateId==='AUTO'?[]:[...lyricTemplates[value.templateId]]
  if(value.chorusFirst&&sections.includes('Chorus')) { sections.splice(sections.indexOf('Chorus'),1); sections.unshift('Chorus') }
  return sections
}
export const lyricsStructureKey = (value: LyricsStructure) => value.templateId==='AUTO'?'':`${value.templateId}:${value.chorusFirst?'first':'normal'}`

/**
 * Agent 识别到的中文段落名 → 现成的歌词模板。
 * 只映射能明确对应的组合，认不出来就保持 AUTO，绝不替用户编造段落。
 */
export function templateForSections(names: string[]): LyricsTemplateId {
  const has = (name: string) => names.includes(name)
  if (has('桥段')) return 'ABCD'
  if (has('副歌')) return 'ABB'
  if (has('主歌')) return 'AAB'
  return 'AUTO'
}
