import { getJson } from '../../../shared/http/api-client'

export interface AnalyticsTotals {
  pv: number; pageviews: number; uv: number; sessions: number; avgDurationMs: number; totalDurationMs: number
  creations?: number
}
export interface AnalyticsDaily { d: string; pv: number; pageviews: number; uv: number; avgDurationMs: number }
export interface AnalyticsPageRow { pagePath: string; pv: number; avgDurationMs: number; samples: number }
export interface AnalyticsUserRow {
  creatorId: string; accountType: string; name: string | null; email: string | null; phoneMasked: string | null
  pv: number; pageviews: number; avgDurationMs: number; samples: number; firstSeen: string; lastSeen: string; works?: number
}
export interface AnalyticsRechargeRow {
  creatorId: string; accountType: string; name: string | null; email: string | null; phoneMasked: string | null
  packageId: string; amount: number; currency: string; credits: number; status: string; createdAt: string
}
export interface AnalyticsRecharge { orders: number; amount: number; credits: number }
export interface AnalyticsCountryRow { country: string; visitors: number; hits: number }
export interface AnalyticsSummary {
  allowed: boolean; days?: number; totals?: AnalyticsTotals; daily?: AnalyticsDaily[]
  pages?: AnalyticsPageRow[]; users?: AnalyticsUserRow[]; creations?: number
  recharge?: AnalyticsRecharge; recharges?: AnalyticsRechargeRow[]; error?: string
  countries?: AnalyticsCountryRow[]
}
export interface AnalyticsPageUserRow {
  creatorId: string; accountType: string; name: string | null; email: string | null; phoneMasked: string | null
  avgDurationMs: number; samples: number
}
export interface AnalyticsPageUsers {
  allowed: boolean; days?: number; pagePath?: string; users?: AnalyticsPageUserRow[]; error?: string
}

export const analyticsAccess = () => getJson<{ allowed: boolean; viewer?: string }>('app/analytics/access')
export const analyticsSummary = (days: number) => getJson<AnalyticsSummary>(`app/analytics/summary?days=${days}`)
export const analyticsPageUsers = (pagePath: string, days: number) =>
  getJson<AnalyticsPageUsers>(`app/analytics/page-users?days=${days}&pagePath=${encodeURIComponent(pagePath)}`)

export interface AnalyticsUserPageRow { pagePath: string; avgDurationMs: number; samples: number }
export interface AnalyticsUserPages {
  allowed: boolean; days?: number; creatorId?: string; pages?: AnalyticsUserPageRow[]; error?: string
}
export const analyticsUserPages = (creatorId: string, days: number) =>
  getJson<AnalyticsUserPages>(`app/analytics/user-pages?days=${days}&creatorId=${encodeURIComponent(creatorId)}`)
