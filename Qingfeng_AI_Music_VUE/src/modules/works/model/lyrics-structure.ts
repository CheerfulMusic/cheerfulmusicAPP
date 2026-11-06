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
