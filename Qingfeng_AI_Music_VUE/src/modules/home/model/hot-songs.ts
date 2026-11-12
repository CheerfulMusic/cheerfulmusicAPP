import { readonly, ref } from 'vue'
import { ensureCreator, ApiError, requestJson } from '../../../shared/http/api-client'
import { locale } from '../../../shared/i18n'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import { showLoginDialog } from '../../../shared/identity/login-dialog'

export type HotStat = { trackId: string; plays: number; likes: number; favorites: number; comments: number }
export type HotKind = 'like' | 'favorite'
export type HotReactions = { liked: string[]; favorited: string[] }

const EMPTY: Omit<HotStat, 'trackId'> = { plays: 0, likes: 0, favorites: 0, comments: 0 }
const stats = ref<Record<string, HotStat>>({})
const mine = ref<HotReactions>({ liked: [], favorited: [] })
const loading = ref(false), failure = ref(''), loginHint = ref('')
const busy = ref<Record<string, HotKind[]>>({})
const playedKey = 'qingfeng.hot-played'
let inflight: Promise<void> | undefined, hintTimer = 0, loaded = false

export const hotLoading = readonly(loading)
export const hotFailure = readonly(failure)
export const hotLoginHint = readonly(loginHint)

export const signedIn = () => accountState.value.authenticated || logtoState.value.authenticated

export function hotStat(trackId: string): HotStat {
  return stats.value[trackId] || { trackId, ...EMPTY }
}

export function isLiked(trackId: string): boolean { return mine.value.liked.includes(trackId) }
export function isFavorited(trackId: string): boolean { return mine.value.favorited.includes(trackId) }
export function isPending(trackId: string, kind: HotKind): boolean { return !!busy.value[trackId]?.includes(kind) }

/** 热度只用于排序：播放权重最低，真实互动权重更高。 */
export function heatOf(stat: HotStat): number {
  return stat.plays + stat.likes * 3 + stat.favorites * 3 + stat.comments * 5
}

/** 全部为 0 时保持原来的编排顺序，避免空榜单把运营选品顺序打乱。 */
export function sortByHeat<T extends { id: string }>(tracks: readonly T[]): T[] {
  return tracks
    .map((track, index) => ({ track, index, heat: heatOf(hotStat(track.id)) }))
    .sort((a, b) => b.heat - a.heat || a.index - b.index)
    .map(item => item.track)
}

export function formatHotCount(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0'
  if (value < 1000) return String(Math.round(value))
  if (locale.value === 'en') return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}K`
  if (value < 10000) return String(Math.round(value))
  return `${(value / 10000).toFixed(1)}万`
}

export function loadHotSongs(force = false): Promise<void> {
  if (inflight) return inflight
  if (loaded && !force) return Promise.resolve()
  loading.value = true
  const request = (async () => {
    try {
      const result = await requestJson<{ songs: HotStat[] }>('public/hot-songs')
      stats.value = Object.fromEntries(result.songs.map(song => [song.trackId, song]))
      failure.value = ''
      loaded = true
    } catch {
      // 首页展示不因计数接口失败而中断；保留上一次成功的数据。
      failure.value = '热歌数据暂时没有更新，刷新页面可以重试。'
    } finally { loading.value = false }
  })().finally(() => { inflight = undefined })
  inflight = request
  return request
}

export async function loadMyReactions(): Promise<void> {
  if (!signedIn()) { mine.value = { liked: [], favorited: [] }; return }
  try {
    const result = await requestJson<HotReactions>('app/hot-songs/reactions')
    mine.value = { liked: [...result.liked], favorited: [...result.favorited] }
  } catch { /* 登录态读取失败时不阻断浏览；点击时仍会再校验。 */ }
}

function alreadyPlayed(trackId: string): boolean {
  try {
    const raw = JSON.parse(sessionStorage.getItem(playedKey) || '[]')
    return Array.isArray(raw) && raw.includes(trackId)
  } catch { return false }
}

function markPlayed(trackId: string): void {
  try {
    const raw = JSON.parse(sessionStorage.getItem(playedKey) || '[]')
    const value = Array.isArray(raw) ? raw.filter((item): item is string => typeof item === 'string') : []
    if (!value.includes(trackId)) sessionStorage.setItem(playedKey, JSON.stringify([...value, trackId]))
  } catch { /* 没有本机会话存储时按每次播放计次。 */ }
}

/** 同一浏览器会话里同一首歌只计一次，暂停后再播不会重复累加。 */
export function recordPlay(trackId: string): void {
  if (!trackId || alreadyPlayed(trackId)) return
  markPlayed(trackId)
  void requestJson<HotStat>(`public/hot-songs/${encodeURIComponent(trackId)}/play`, 'POST')
    .then(stat => { stats.value = { ...stats.value, [trackId]: stat } })
    .catch(() => { /* 播放计数失败不影响试听。 */ })
}

export function noteLoginHint(message: string): void {
  loginHint.value = message
  window.clearTimeout(hintTimer)
  hintTimer = window.setTimeout(() => { loginHint.value = '' }, 4000)
}

/** 未登录只弹登录框，不发起写请求；登录后由页面重新读取关系。 */
export async function toggleReaction(trackId: string, kind: HotKind): Promise<void> {
  if (!signedIn()) { noteLoginHint(kind === 'like' ? '登录后才能点赞' : '登录后才能收藏'); showLoginDialog(); return }
  if (busy.value[trackId]?.includes(kind)) return
  const active = kind === 'like' ? isLiked(trackId) : isFavorited(trackId)
  busy.value = { ...busy.value, [trackId]: [...(busy.value[trackId] || []), kind] }
  try {
    await ensureCreator()
    const result = await requestJson<{ stat: HotStat; liked: boolean; favorited: boolean }>(
      `app/hot-songs/${encodeURIComponent(trackId)}/${kind}`, active ? 'DELETE' : 'POST')
    stats.value = { ...stats.value, [trackId]: result.stat }
    mine.value = {
      liked: result.liked ? [...new Set([...mine.value.liked, trackId])] : mine.value.liked.filter(id => id !== trackId),
      favorited: result.favorited ? [...new Set([...mine.value.favorited, trackId])] : mine.value.favorited.filter(id => id !== trackId),
    }
    failure.value = ''
  } catch (error) {
    if (error instanceof ApiError && [401, 403].includes(error.status)) {
      mine.value = { liked: [], favorited: [] }
      noteLoginHint(kind === 'like' ? '登录后才能点赞' : '登录后才能收藏')
      showLoginDialog()
    } else {
      failure.value = error instanceof Error ? error.message : '互动没有保存成功，请稍后重试。'
    }
  } finally {
    const left = (busy.value[trackId] || []).filter(item => item !== kind)
    busy.value = { ...busy.value, [trackId]: left }
  }
}
