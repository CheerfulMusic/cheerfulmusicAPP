import { isLyricLine } from './lyric-line-edit'
import { selectedLyricLanguage } from '../../../shared/i18n/lyric-language'

export interface LyricRhymeSection {
  lines: string[]
  lineIndex: number
}

const sectionHeading = /^\s*\[[^\]]+\]\s*$/
const inlineHeading = /^([ \t]*(?:\[(?:verse|chorus|pre[- ]?chorus|bridge|intro|outro|instrumental|interlude|hook|rap|主歌|副歌|预副歌|桥段|前奏|尾奏|间奏|说唱)[^\]]*\][ \t]*)+)/i

export function lyricSectionPrefix(line: string) {
  return inlineHeading.exec(line)?.[1] ?? ''
}

export function rhymeLanguage(section: LyricRhymeSection, language?: string | null) {
  const selected = selectedLyricLanguage(language)
  if (selected && selected !== 'zh-CN' && selected !== 'en' && selected !== 'mixed') return selected
  const text = section.lines.join('\n')
  const han = /\p{Script=Han}/u.test(text)
  const latin = /\p{Script=Latin}/u.test(text)
  if (han && latin) return 'mixed'
  if (han) return 'zh-CN'
  if (latin) return 'en'
  return selected ?? 'zh-CN'
}

export function rhymeSectionAt(source: string, selectedStart: number, draft?: string): LyricRhymeSection | undefined {
  let lines: string[] = []
  let lineIndex = -1
  for (const row of source.matchAll(/[^\r\n]*(?:\r\n|\r|\n|$)/g)) {
    const line = row[0].replace(/(?:\r\n|\r|\n)$/, '')
    const prefix = lyricSectionPrefix(line)
    if (!line.trim() || sectionHeading.test(line) || prefix) {
      if (lineIndex >= 0) break
      lines = []
      if (!line.trim() || sectionHeading.test(line)) continue
    }
    const selected = row.index === selectedStart
    const text = (selected && draft !== undefined ? draft : line.slice(prefix.length)).trim()
    if (selected) {
      if (!isLyricLine(text)) return
      lineIndex = lines.length
    }
    // Keep an oversized line from shifting the apparent rhyme pattern.
    if (!isLyricLine(text)) {
      if (lineIndex >= 0) break
      lines = []
      continue
    }
    lines.push(text)
  }
  if (lineIndex < 0) return
  // Keep long sections within the request limit while keeping nearby reference lines.
  const first = Math.max(0, Math.min(lineIndex - 19, lines.length - 40))
  return { lines: lines.slice(first, first + 40), lineIndex: lineIndex - first }
}
