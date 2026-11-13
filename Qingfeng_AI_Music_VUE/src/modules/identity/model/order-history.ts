export type Provider = 'airwallex' | 'paypal' | 'stripe'
export type OrderStatus = 'CREATING' | 'CREATION_UNKNOWN' | 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED' | 'UNKNOWN'
export const isPendingOrder = (status: OrderStatus) => status === 'CREATING' || status === 'CREATION_UNKNOWN' || status === 'PENDING' || status === 'UNKNOWN'
export type Order = { orderId: string; status: OrderStatus; credits?: number; createdAt?: string; provider: Provider; amount?: number; currency?: string; packageId?: string; paymentMethod?: 'card' | 'apple_pay' | 'wechat_pay' }

export function trustedCheckoutUrl(value: string, provider: Provider): string {
  const url = new URL(value)
  const allowed = provider === 'paypal'
    ? ['www.paypal.com', 'www.sandbox.paypal.com'].includes(url.hostname)
    : provider === 'stripe'
      ? url.hostname === 'checkout.stripe.com'
      : url.hostname === 'airwallex.com' || url.hostname.endsWith('.airwallex.com')
  if (url.protocol !== 'https:' || url.username || url.password || !allowed || url.port) throw new Error('Invalid checkout URL')
  return url.href
}

/** Merge only within a provider; the same identifier can exist at another provider. */
export function mergeOrderHistory(orders: Order[], updates: Order[]): Order[] {
  const merged = new Map<string, Order>()
  for (const order of [...orders, ...updates]) {
    const key = `${order.provider}:${order.orderId}`
    merged.set(key, { ...merged.get(key), ...order })
  }
  return [...merged.values()].sort((a, b) => (Date.parse(b.createdAt || '') || 0) - (Date.parse(a.createdAt || '') || 0))
}

/** Order history uses only the persisted payment snapshot, never today's catalog or FX rate. */
export function orderAmount(order: Order): string {
  if (typeof order.amount !== 'number' || !Number.isFinite(order.amount) || order.amount <= 0 || !order.currency) return ''
  if (order.currency === 'CNY') return `CNY ¥${order.amount.toFixed(2)}`
  if (order.currency === 'USD') return `USD $${order.amount.toFixed(2)}`
  return `${order.currency} ${order.amount.toFixed(2)}`
}
