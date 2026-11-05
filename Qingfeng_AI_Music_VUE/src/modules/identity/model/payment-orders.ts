/** Local recovery hints only. The server enforces order ownership and confirms payment. */
const prefix = 'qingfeng.paymentOrders.'
const validId = (id: unknown): id is string => typeof id === 'string' && /^[a-f0-9-]{36}$/i.test(id)
export function readPendingOrders(storage: Storage, creatorId: string, provider = 'airwallex'): string[] {
  if (!creatorId) throw new Error('Missing identity')
  const raw = storage.getItem(prefix + (provider === 'airwallex' ? '' : provider + '.') + creatorId)
  if (!raw) return []
  const ids: unknown = JSON.parse(raw)
  if (!Array.isArray(ids) || !ids.every(validId)) throw new Error('Invalid saved orders')
  return [...new Set(ids)]
}
export function savePendingOrders(storage: Storage, creatorId: string, ids: string[], provider = 'airwallex') {
  if (!creatorId || !ids.every(validId)) throw new Error('Invalid saved orders')
  storage.setItem(prefix + (provider === 'airwallex' ? '' : provider + '.') + creatorId, JSON.stringify([...new Set(ids)]))
}
