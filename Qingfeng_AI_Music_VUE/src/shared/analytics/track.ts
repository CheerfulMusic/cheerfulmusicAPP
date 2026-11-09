import { apiUrl } from '../http/api-client'

/**
 * 站内用户行为采集，移植自 tk-tracking v1.1/v1.2 的前端方案：
 * 匿名 visitor/session 标识、pageview 自动上报、sendBeacon 优先 + fetch keepalive 回退。
 *
 * 与原实现的差异（青风音乐适配）：
 * - 只上报脱敏后的 pathname，绝不带上 query/hash（OAuth code、礼物 token 都不会进日志）。
 * - 事件名与 payload 键白名单由后端二次校验，不采集故事、歌词、邮箱、手机号。
 * - 尊重 navigator.doNotTrack，并提供 setAnalyticsEnabled(false) 供同意管理接入后调用。
 * - 任何异常都静默吞掉，埋点失败绝不影响业务与播放。
 */

export const ANALYTICS_EVENTS = [
  'pageview', 'gift_view', 'creation_start', 'lyrics_generated', 'song_generated',
  'checkout_start', 'purchase', 'audio_play', 'gift_download',
] as const
export type AnalyticsEvent = typeof ANALYTICS_EVENTS[number]
export type AnalyticsPayload = Record<string, string | number | boolean>

const VID_KEY = 'qf.analytics.vid'
const VID_AT_KEY = 'qf.analytics.vid.at'
const SID_KEY = 'qf.analytics.sid'
const SID_AT_KEY = 'qf.analytics.sid.at'
const OPT_OUT_KEY = 'qf.analytics.optout'
const YEAR_MS = 365 * 24 * 60 * 60 * 1000
const SESSION_MS = 30 * 60 * 1000
const ALLOWED = new Set<string>(ANALYTICS_EVENTS)

function local(key: string): string {
  try { return localStorage.getItem(key) || '' } catch { return '' }
}
function setLocal(key: string, value: string): void {
  try { localStorage.setItem(key, value) } catch { /* 私密模式下存储不可用，退化为一次性标识。 */ }
}
function session(key: string): string {
  try { return sessionStorage.getItem(key) || '' } catch { return '' }
}
function setSession(key: string, value: string): void {
  try { sessionStorage.setItem(key, value) } catch { /* 同上。 */ }
}
function uuid(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  } catch { /* 退化标识。 */ }
  return 'v' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12)
}

/** 一年有效的匿名访客标识；localStorage 不可用时退化为本次调用生成的一次性值。 */
export function visitorId(): string {
  const saved = local(VID_KEY), at = Number(local(VID_AT_KEY)) || 0
  if (saved && Date.now() - at < YEAR_MS) return saved
  const id = uuid()
  setLocal(VID_KEY, id)
  setLocal(VID_AT_KEY, String(Date.now()))
  return id
}

/** 30 分钟有效的会话标识；sessionStorage 关闭标签即失效。 */
export function sessionId(): string {
  const saved = session(SID_KEY), at = Number(session(SID_AT_KEY)) || 0
  if (saved && Date.now() - at < SESSION_MS) return saved
  const id = uuid()
  setSession(SID_KEY, id)
  setSession(SID_AT_KEY, String(Date.now()))
  return id
}

/** 只保留路径，并把礼物 token / 作品 id 替换为占位符。 */
export function sanitizePagePath(path: string): string {
  const clean = path.split('?')[0].split('#')[0].slice(0, 512)
  if (!clean.startsWith('/')) return ''
  return clean
    .replace(/\/gift\/[A-Za-z0-9_-]{20,}/g, '/gift/:token')
    .replace(/\/works\/[A-Za-z0-9_-]{8,}/g, '/works/:id')
}

/** 来源只保留 origin + pathname，去掉 query/hash，避免把授权码写进库。 */
export function sanitizeReferrer(referrer: string): string {
  if (!referrer) return ''
  try {
    const url = new URL(referrer)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return ''
    return (url.origin + url.pathname).slice(0, 512)
  } catch { return '' }
}

export function analyticsEnabled(): boolean {
  if (local(OPT_OUT_KEY) === '1') return false
  try {
    const nav = navigator as Navigator & { doNotTrack?: string }
    if (nav.doNotTrack === '1') return false
    if ((globalThis as unknown as { doNotTrack?: string }).doNotTrack === '1') return false
  } catch { /* 无 navigator 的环境按可用处理。 */ }
  return true
}

/** 接入 Cookie 同意组件后，未获同意时调用 false；再次同意调用 true。 */
export function setAnalyticsEnabled(value: boolean): void {
  if (!value) { setLocal(OPT_OUT_KEY, '1'); return }
  try { localStorage.removeItem(OPT_OUT_KEY) } catch { /* 忽略。 */ }
}

export function track(event: AnalyticsEvent | string, payload?: AnalyticsPayload): void {
  try {
    if (!analyticsEnabled() || !ALLOWED.has(event)) return
    const body = JSON.stringify({
      event,
      pagePath: sanitizePagePath(location.pathname),
      referrer: sanitizeReferrer(document.referrer || ''),
      visitorId: visitorId(),
      sessionId: sessionId(),
      ...(payload ? { payload } : {}),
    })
    const url = apiUrl('public/events')
    if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function'
      && navigator.sendBeacon(url, new Blob([body], { type: 'application/json' }))) return
    void fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body,
      keepalive: true, credentials: 'same-origin',
    }).catch(() => {})
  } catch { /* 埋点失败绝不影响业务。 */ }
}

export function trackPageView(route: string): void {
  track('pageview', { route })
}
