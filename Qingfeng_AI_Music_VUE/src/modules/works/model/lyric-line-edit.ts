import { ApiError } from '../../../shared/http/api-client'
import { rewriteLyricLine, type LyricRewriteAction } from '../api/detail'

export const lyricsMaxLength = 12000
const lineBreak = /[\r\n\u0085\u2028\u2029\v\f]/

export interface LyricLineSelection {
  source: string
  start: number
  end: number
  line: string
  context: string
}

function isLyricLine(line: string) {
  return !!line.trim() && line.length <= 160 && !lineBreak.test(line) && !/^\s*\[.*\]\s*$/.test(line)
}

function selectLyricText(source: string, start: number, end: number): LyricLineSelection | undefined {
  const line = source.slice(start, end)
  if (!isLyricLine(line)) return
  return { source, start, end, line, context: source.slice(Math.max(0, start - 180), start) + '\n' + source.slice(end, end + 179) }
}

export function selectLyricLine(source: string, start: number, end: number) {
  if (lineBreak.test(source.slice(start, end))) return
  const lineStart = start === 0 ? 0 : source.lastIndexOf('\n', start - 1) + 1
  const lineEnd = source.indexOf('\n', end)
  return selectLyricText(source, lineStart, lineEnd === -1 ? source.length : lineEnd)
}

export async function suggestLyricLine(workId: string, selection: LyricLineSelection, action: LyricRewriteAction, language: string) {
  const { text } = await rewriteLyricLine(workId, {
    requestId: crypto.randomUUID(), action, line: selection.line, context: selection.context, language,
  })
  if (typeof text !== 'string' || !isLyricLine(text) || action === 'SHORTEN' && text.trim().length >= selection.line.trim().length) {
    throw new Error('未得到可用的单句改写，原歌词已保留。')
  }
  return text
}

export function lyricRewriteError(error: unknown) {
  if (error instanceof ApiError && error.status === 405) return '当前后端版本不支持选句润色，请更新后端后重试。'
  return error instanceof Error ? error.message : '改写暂不可用，原歌词已保留。'
}
