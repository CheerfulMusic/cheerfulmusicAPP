import { getJson } from '../../../shared/http/api-client'

export interface AnalyticsTotals {
  pv: number; uv: number; sessions: number; avgDurationMs: number; totalDurationMs: number
}
export interface AnalyticsDaily { d: string; pv: number; uv: number; avgDurationMs: number }
export interface AnalyticsPageRow { pagePath: string; pv: number; avgDurationMs: number; samples: number }
export interface AnalyticsUserRow {
  creatorId: string; accountType: string; name: string | null; email: string | null; phoneMasked: string | null
  pv: number; avgDurationMs: number; samples: number; firstSeen: string; lastSeen: string
}
export interface AnalyticsSummary {
  allowed: boolean; days?: number; totals?: AnalyticsTotals; daily?: AnalyticsDaily[]
  pages?: AnalyticsPageRow[]; users?: AnalyticsUserRow[]; error?: string
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
