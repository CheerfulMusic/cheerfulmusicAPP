/** Return URL is only a hint; ownership and payment are verified by the server. */
export function paypalReturn(search: string): { orderId: string; approved: boolean } | null {
  const query = new URLSearchParams(search)
  const approved = query.getAll('paypal_order')
  const cancelled = query.getAll('paypal_cancel')
  if (approved.length + cancelled.length !== 1) return null
  const orderId = (approved[0] || cancelled[0] || '')
  if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(orderId)) return null
  return {orderId, approved: approved.length === 1}
}

/** Only attempt capture for an approved return that belongs to the active account's known orders. */
export function canCapturePaypal(hint: ReturnType<typeof paypalReturn>, orderIds: string[], sameIdentity: boolean): boolean {
  return !!hint && hint.approved && sameIdentity && orderIds.includes(hint.orderId)
}
