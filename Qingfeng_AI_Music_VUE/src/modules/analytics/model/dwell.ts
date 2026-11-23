import { t } from '../../../shared/i18n'

/** 看板里所有停留时长共用的格式化：真实为零才显示「0 秒」。 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return t('{seconds} 秒', { seconds: 0 })
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return t('{seconds} 秒', { seconds })
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return t('{minutes} 分 {seconds} 秒', { minutes, seconds: seconds % 60 })
  return t('{hours} 小时 {minutes} 分', { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
}

/** 页面与用户的停留单元格：没有样本时显示「—」，不能伪装成 0 秒。 */
export function dwellLabel(avgMs: number, samples: number): string {
  return Number.isFinite(samples) && samples > 0 && Number.isFinite(avgMs) ? formatDuration(avgMs) : '—'
}

/** 下钻缓存的键：时间范围变化后必须重新取数。 */
export function pageUsersKey(days: number, pagePath: string): string {
  return `${days}:${pagePath || '/'}`
}

export type AccountType = 'GOOGLE' | 'OIDC_EMAIL' | 'OIDC_PHONE' | 'AUTHING' | 'GUEST'

/** 身份标签：游客也带 creator_id，必须显式标出来，不能混在登录用户里。 */
export function accountTypeLabel(type: string | null | undefined): string {
  switch (type) {
    case 'GOOGLE': return 'Google'
    case 'OIDC_EMAIL': return t('邮箱登录')
    case 'OIDC_PHONE': return t('手机登录')
    case 'AUTHING': return t('账号登录')
    default: return t('游客')
  }
}

export function isGuest(type: string | null | undefined): boolean {
  return !type || type === 'GUEST'
}

export interface AccountRow {
  creatorId: string | null; accountType?: string | null; name?: string | null; email?: string | null; phoneMasked?: string | null
}

/** 主显示名：昵称 > 邮箱 > 手机掩码 > 「未登录游客」。 */
export function accountName(row: AccountRow): string {
  const name = row.name?.trim()
  if (name) return name
  if (row.email && row.email.trim()) return row.email
  if (row.phoneMasked && row.phoneMasked.trim()) return row.phoneMasked
  return isGuest(row.accountType) ? t('未登录游客') : t('未命名用户')
}

/** 副标题：已有昵称时补邮箱，手机号单独补掩码；游客没有可展示的账号信息。 */
export function accountDetail(row: AccountRow): string {
  const name = row.name?.trim()
  if (row.email && row.email.trim()) return name ? row.email : ''
  if (row.phoneMasked && row.phoneMasked.trim() && name) return row.phoneMasked
  return ''
}

export type UserFilter = 'all' | 'signed' | 'guest'

/** 看板按身份筛选；游客是真实访问，可以隐藏但不能被当成登录用户统计。 */
export function filterUsers<T extends { accountType?: string | null }>(rows: readonly T[], filter: UserFilter): T[] {
  if (filter === 'all') return [...rows]
  return rows.filter(row => filter === 'guest' ? isGuest(row.accountType) : !isGuest(row.accountType))
}

/** 游客只存在于浏览器会话里，没有可还原的账号；这里只用于说明文案。 */
export function guestHint(guests: number): string {
  return guests > 0 ? `${guests} 位游客只创建了浏览器会话，未绑定 Google / 邮箱 / 手机身份` : ''
}

export interface TrendPoint { d: string; pv: number; uv: number; pageviews: number; avgDurationMs: number }

function isoDay(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * 补齐整段日期：接口只返回有事件的日期，缺的那天补 0。
 * 0 是真实值（当天没有访问），不是编造数据；单点时图表也能画出可见坐标。
 */
export function buildTrend(rows: readonly { d: string; pv: number; uv: number; pageviews: number; avgDurationMs: number }[], days: number, now: Date = new Date()): TrendPoint[] {
  const byDay = new Map<string, TrendPoint>()
  for (const row of rows) {
    byDay.set(row.d, {
      d: row.d,
      pv: Number(row.pv) || 0,
      uv: Number(row.uv) || 0,
      pageviews: Number(row.pageviews) || 0,
      avgDurationMs: Number(row.avgDurationMs) || 0,
    })
  }
  const span = Math.max(1, Math.floor(days))
  const result: TrendPoint[] = []
  for (let offset = span - 1; offset >= 0; offset--) {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - offset)
    const d = isoDay(date)
    result.push(byDay.get(d) ?? { d, pv: 0, uv: 0, pageviews: 0, avgDurationMs: 0 })
  }
  return result
}

/** 占比用于进度条，始终以最大页面的 PV 为参照，避免全为 0 时除零。 */
export function shareOf(value: number, total: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(total) || total <= 0) return 0
  return Math.max(0, Math.min(1, value / total))
}

const PAGE_NAMES: Record<string, string> = {
  '/': '首页',
  '/create': '创作页',
  '/works': '我的音乐',
  '/works/:id': '作品详情',
  '/account': '账号页',
  '/recharge': '充值页',
  '/gift/:token': '分享页',
  '/analytics': '数据看板',
  '/agent': 'AI 工作台',
  '/discover': '广场',
  '/en/discover': '广场',
  '/profile': '个人资料',
  '/create/photo': '照片创作',
  '/help': '创作帮助',
  '/legal/terms': '服务条款',
  '/legal/privacy': '隐私政策',
  '/legal/terms.html': '服务条款',
  '/legal/privacy.html': '隐私政策',
  '/payment-preview': '支付预览',
}

/** 页面路径仅用于查询，展示时使用可翻译的页面名称。 */
export function pageName(path: string): string {
  const clean = (path || '/').split(/[?#]/)[0]!.replace(/\/$/, '') || '/'
  if (/^\/artist\/[^/]+$/.test(clean)) return t('音乐人主页')
  if (/^\/works\/[^/]+$/.test(clean)) return t('作品详情')
  if (/^\/gift\/[^/]+$/.test(clean)) return t('分享页')
  return t(Object.hasOwn(PAGE_NAMES, clean) ? PAGE_NAMES[clean]! : '其他页面')
}

/** 头像首字母：优先昵称/邮箱首字符，游客用「游」。 */
export function avatarText(row: AccountRow): string {
  const name = row.name?.trim()
  if (name) return [...name][0]!.toUpperCase()
  if (row.email && row.email.trim()) return row.email.trim()[0]!.toUpperCase()
  if (row.phoneMasked && row.phoneMasked.trim()) return t('手')
  return isGuest(row.accountType) ? t('游') : t('访')
}
