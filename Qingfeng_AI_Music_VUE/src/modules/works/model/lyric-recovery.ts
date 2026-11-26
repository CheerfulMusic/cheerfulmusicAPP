import { ApiError } from '../../../shared/http/api-client'

export const lyricOutcomeUnknownMessage = '歌词结果尚未确认，请先核对原请求。'
export const lyricNewRequestConfirmation = '原请求结果仍未确认。另起一次编写将创建新请求，并按新请求结算。确定继续吗？'

export function lyricFailureMessage(code?: string | null) {
  switch (code) {
    case 'LYRICS_RESPONSE_FORMAT': return '歌词服务返回的格式异常，可以重新编写。'
    case 'LYRICS_RESPONSE_TRUNCATED': return '歌词服务未返回完整内容，可以重新编写。'
    case 'LYRICS_RESPONSE_INVALID': return '歌名或歌词未通过校验，可以重新编写。'
    case 'LYRICS_STRUCTURE_MISMATCH': return '歌词未符合所选模板，可以重新编写或调整模板。'
    case 'LYRICS_QUALITY_UNVERIFIED': return '本次歌词润色未完成，已有内容会保留。'
  }
}

export function lyricNeedsOriginalCheck(cause: unknown) {
  if (!(cause instanceof ApiError)) return false
  if (lyricFailureMessage(cause.code)) return false
  return cause.status === 0 || cause.status === 408 || cause.status >= 500
    || ['LYRICS_REQUEST_PENDING', 'LYRICS_PROVIDER_TIMEOUT', 'LYRICS_PROVIDER_NETWORK_ERROR',
      'LYRICS_REQUEST_INTERRUPTED', 'LYRICS_REQUEST_TIMEOUT', 'LYRICS_REQUEST_SUPERSEDED', 'INVALID_RESPONSE'].includes(cause.code ?? '')
}
