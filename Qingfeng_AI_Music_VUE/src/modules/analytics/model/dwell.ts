/** 看板里所有停留时长共用的格式化：真实为零才显示「0 秒」。 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0 秒'
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分 ${seconds % 60} 秒`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
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
    case 'OIDC_EMAIL': return '邮箱登录'
    case 'OIDC_PHONE': return '手机登录'
    case 'AUTHING': return '账号登录'
    default: return '游客'
  }
}

export function isGuest(type: string | null | undefined): boolean {
  return !type || type === 'GUEST'
}

export interface AccountRow {
  creatorId: string; accountType?: string | null; name?: string | null; email?: string | null; phoneMasked?: string | null
}

/** 主显示名：昵称 > 邮箱 > 手机掩码 > 「未登录游客」。 */
export function accountName(row: AccountRow): string {
  const name = row.name?.trim()
  if (name) return name
  if (row.email && row.email.trim()) return row.email
  if (row.phoneMasked && row.phoneMasked.trim()) return row.phoneMasked
  return '未登录游客'
}

/** 副标题：已有昵称时补邮箱，手机号单独补掩码；游客没有可展示的账号信息。 */
export function accountDetail(row: AccountRow): string {
  const name = row.name?.trim()
  if (row.email && row.email.trim()) return name ? row.email : ''
  if (row.phoneMasked && row.phoneMasked.trim() && name) return row.phoneMasked
  return ''
}

/** 游客只存在于浏览器会话里，没有可还原的账号；这里只用于说明文案。 */
export function guestHint(guests: number): string {
  return guests > 0 ? `${guests} 位游客只创建了浏览器会话，未绑定 Google / 邮箱 / 手机身份` : ''
}
