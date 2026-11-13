export const lyricTemplates = {
  AAB: ['Verse 1','Verse 2','Chorus','Interlude','Verse 2','Chorus','Chorus 2'],
  ABB: ['Verse','Chorus','Chorus 2','Interlude','Verse','Chorus','Chorus 2'],
  ABC: ['Verse','Pre-Chorus','Chorus','Interlude','Pre-Chorus','Chorus','Chorus 2'],
  ABCD: ['Verse','Pre-Chorus','Chorus','Interlude','Pre-Chorus','Chorus','Bridge','Chorus'],
} as const
export const lyricSectionNames = {
  'Verse': '主歌',
  'Verse 1': '主歌一',
  'Verse 2': '主歌二',
  'Pre-Chorus': '预副歌',
  'Chorus': '副歌',
  'Chorus 2': '第二副歌',
  'Bridge': '桥段',
  'Interlude': '间奏',
} as const
export type LyricSection = keyof typeof lyricSectionNames
export type PresetLyricsStructure = {templateId: 'AUTO' | keyof typeof lyricTemplates; chorusFirst: boolean}
export type LyricsTemplateId = PresetLyricsStructure['templateId'] | 'CUSTOM'
export type LyricsStructure = PresetLyricsStructure | {templateId: 'CUSTOM'; chorusFirst: false; customSections: LyricSection[]}
export const maxCustomLyricSections = 24

export function validCustomLyricSections(sections: unknown): sections is LyricSection[] {
  return Array.isArray(sections) && sections.length <= maxCustomLyricSections
    && sections.every(section => typeof section === 'string' && Object.hasOwn(lyricSectionNames, section))
    && sections.some(section => section !== 'Interlude')
}

export function normalizeLyricsStructure(value?: {templateId?: unknown; chorusFirst?: unknown; customSections?: unknown}): LyricsStructure {
  if (value?.templateId === 'CUSTOM' && validCustomLyricSections(value.customSections)) {
    return {templateId: 'CUSTOM', chorusFirst: false, customSections: [...value.customSections]}
  }
  const templateId = typeof value?.templateId === 'string' && Object.hasOwn(lyricTemplates, value.templateId)
    ? value.templateId as keyof typeof lyricTemplates : 'AUTO'
  return {templateId, chorusFirst: templateId !== 'AUTO' && value?.chorusFirst === true}
}
export function lyricSections(value: LyricsStructure): LyricSection[] {
  if (value.templateId === 'CUSTOM') return [...value.customSections]
  const sections: LyricSection[] = value.templateId === 'AUTO' ? [] : [...lyricTemplates[value.templateId]]
  if (value.chorusFirst) {
    const firstChorus = sections.indexOf('Chorus')
    if (firstChorus >= 0) sections.unshift(...sections.splice(firstChorus, 1))
  }
  return sections
}
export function lyricsStructureKey(value: LyricsStructure): string {
  if (value.templateId === 'CUSTOM') return `CUSTOM:${value.customSections.join('|')}`
  return value.templateId === 'AUTO' ? '' : `${value.templateId}:${value.chorusFirst ? 'first' : 'normal'}`
}

export function lyricsStructureQuery(value: LyricsStructure) {
  return {
    templateId: value.templateId,
    chorusFirst: String(value.chorusFirst),
    customSections: value.templateId === 'CUSTOM' ? value.customSections : undefined,
  }
}

export function lyricsStructureFromQuery(query: Record<string, unknown>) {
  return normalizeLyricsStructure({
    ...query,
    chorusFirst: query.chorusFirst === 'true',
    customSections: typeof query.customSections === 'string' ? [query.customSections] : query.customSections,
  })
}

/**
 * Agent 识别到的中文段落名 → 现成的歌词模板。
 * 只映射能明确对应的组合，认不出来就保持 AUTO，绝不替用户编造段落。
 */
export function templateForSections(names: string[]): PresetLyricsStructure['templateId'] {
  const has = (name: string) => names.includes(name)
  if (has('桥段')) return 'ABCD'
  if (has('副歌')) return 'ABB'
  if (has('主歌')) return 'AAB'
  return 'AUTO'
}
