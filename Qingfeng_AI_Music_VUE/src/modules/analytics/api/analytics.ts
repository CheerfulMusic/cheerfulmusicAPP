import { getJson } from '../../../shared/http/api-client'

export interface AnalyticsTotals {
  pv: number; uv: number; sessions: number; avgDurationMs: number; totalDurationMs: number
}
export interface AnalyticsDaily { d: string; pv: number; uv: number; avgDurationMs: number }
export interface AnalyticsPageRow { pagePath: string; pv: number; avgDurationMs: number }
export interface AnalyticsUserRow {
  creatorId: string; account: string | null; pv: number; avgDurationMs: number; lastSeen: string
}
export interface AnalyticsSummary {
  allowed: boolean; days?: number; totals?: AnalyticsTotals; daily?: AnalyticsDaily[]
  pages?: AnalyticsPageRow[]; users?: AnalyticsUserRow[]; error?: string
}

export const analyticsAccess = () => getJson<{ allowed: boolean; viewer?: string }>('app/analytics/access')
export const analyticsSummary = (days: number) => getJson<AnalyticsSummary>(`app/analytics/summary?days=${days}`)
