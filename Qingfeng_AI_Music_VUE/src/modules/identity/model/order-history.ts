export type Provider = 'airwallex' | 'paypal'
export type OrderStatus = 'CREATING' | 'PENDING' | 'PAID' | 'CANCELLED' | 'FAILED' | 'UNKNOWN'
export const isPendingOrder = (status: OrderStatus) => status === 'CREATING' || status === 'PENDING' || status === 'UNKNOWN'
export type Order = { orderId: string; status: OrderStatus; credits?: number; createdAt?: string; provider: Provider }

export function trustedCheckoutUrl(value: string, provider: Provider): string {
  const url = new URL(value)
  const allowed = provider === 'paypal' ? ['www.paypal.com', 'www.sandbox.paypal.com'].includes(url.hostname) : url.hostname === 'airwallex.com' || url.hostname.endsWith('.airwallex.com')
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
