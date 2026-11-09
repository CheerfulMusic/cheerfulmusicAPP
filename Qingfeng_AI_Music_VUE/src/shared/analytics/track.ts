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
  'pageview', 'page_leave', 'gift_view', 'creation_start', 'lyrics_generated', 'song_generated',
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
// 浏览器禁用存储时的进程内兜底，避免每次调用都生成新访客、也保证开关仍然生效。
let memoryVid = '', memorySid = '', memoryOptOut = false
let pageEnteredAt = 0, currentRoute = '', leaveReported = false, leaveInstalled = false

function local(key: string): string {
  try { return localStorage.getItem(key) || '' } catch { return '' }
}
function setLocal(key: string, value: string): boolean {
  try { localStorage.setItem(key, value); return true } catch { return false }
}
function session(key: string): string {
  try { return sessionStorage.getItem(key) || '' } catch { return '' }
}
function setSession(key: string, value: string): boolean {
  try { sessionStorage.setItem(key, value); return true } catch { return false }
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
  const stored = setLocal(VID_KEY, id) && setLocal(VID_AT_KEY, String(Date.now()))
  if (!stored) { memoryVid ||= id; return memoryVid }
  return id
}

/** 30 分钟有效的会话标识；sessionStorage 关闭标签即失效。 */
export function sessionId(): string {
  const saved = session(SID_KEY), at = Number(session(SID_AT_KEY)) || 0
  if (saved && Date.now() - at < SESSION_MS) return saved
  const id = uuid()
  const stored = setSession(SID_KEY, id) && setSession(SID_AT_KEY, String(Date.now()))
  if (!stored) { memorySid ||= id; return memorySid }
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
  if (memoryOptOut || local(OPT_OUT_KEY) === '1') return false
  try {
    const nav = navigator as Navigator & { doNotTrack?: string }
    if (nav.doNotTrack === '1') return false
    if ((globalThis as unknown as { doNotTrack?: string }).doNotTrack === '1') return false
  } catch { /* 无 navigator 的环境按可用处理。 */ }
  return true
}

/** 接入 Cookie 同意组件后，未获同意时调用 false；再次同意调用 true。 */
export function setAnalyticsEnabled(value: boolean): void {
  memoryOptOut = !value
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

/** 上报上一页停留时长；同一段停留只上报一次，后台切换会重新计时。 */
export function reportPageLeave(): void {
  if (!pageEnteredAt || leaveReported) return
  leaveReported = true
  const durationMs = Math.max(0, Math.min(Date.now() - pageEnteredAt, 86_400_000))
  pageEnteredAt = 0
  track('page_leave', { route: currentRoute, durationMs })
}

export function trackPageView(route: string): void {
  reportPageLeave()
  currentRoute = route
  pageEnteredAt = Date.now()
  leaveReported = false
  track('pageview', { route })
}

/** 页面隐藏 / 关闭时用 sendBeacon 补发停留时长；存储或事件 API 不可用时不抛错。 */
export function installPageLeaveTracking(): void {
  if (leaveInstalled) return
  try {
    if (typeof document === 'undefined' || typeof window === 'undefined') return
    leaveInstalled = true
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') reportPageLeave()
      else if (currentRoute) { pageEnteredAt = Date.now(); leaveReported = false }
    })
    window.addEventListener('pagehide', () => { reportPageLeave() })
  } catch { /* 无 DOM 环境（测试 / 预渲染）不安装。 */ }
}
