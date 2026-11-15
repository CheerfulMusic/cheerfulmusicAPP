export type PaymentCurrency = 'USD' | 'CNY' | 'GBP'
export type StripePaymentMethodId = 'card' | 'apple_pay' | 'wechat_pay'
export type CreditPack = { id: string; title: string; amount: number; currency: string; credits: number; paymentMethod?: StripePaymentMethodId; acceptance?: boolean; expiresAt?: string; maxPurchases?: number }
export type CurrencyOption = { currency: PaymentCurrency; enabled: boolean; reason: string }
export type StripePaymentMethod = {
  id: StripePaymentMethodId
  enabled: boolean
  reason: string
  currencies: PaymentCurrency[]
  currencyOptions: CurrencyOption[]
  packages: CreditPack[]
  requiresCompatibleDevice: boolean
}
export type PaymentCatalog = { enabled: boolean; packages: CreditPack[] }
export type StripeCatalog = PaymentCatalog & { paymentMethods?: StripePaymentMethod[] }

const methodIds: StripePaymentMethodId[] = ['card', 'apple_pay', 'wechat_pay']
const currencies: PaymentCurrency[] = ['USD', 'CNY', 'GBP']
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
export const isStripeMethod = (value: unknown): value is StripePaymentMethodId => methodIds.includes(value as StripePaymentMethodId)
const isCurrency = (value: unknown): value is PaymentCurrency => currencies.includes(value as PaymentCurrency)

export function validCreditPack(value: unknown): value is CreditPack {
  return object(value) && typeof value.id === 'string' && !!value.id.trim()
    && typeof value.title === 'string' && typeof value.currency === 'string' && !!value.currency.trim()
    && typeof value.amount === 'number' && Number.isFinite(value.amount) && value.amount > 0
    && Number.isSafeInteger(Math.round(value.amount * 100))
    && Math.abs(value.amount * 100 - Math.round(value.amount * 100)) < 0.000001
    && Number.isSafeInteger(value.credits) && Number(value.credits) > 0
    && (value.paymentMethod === undefined || isStripeMethod(value.paymentMethod))
    && (value.acceptance === true
      ? value.credits === 1 && value.maxPurchases === 1 && typeof value.expiresAt === 'string'
        && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value.expiresAt) && Number.isFinite(Date.parse(value.expiresAt))
      : (value.acceptance === undefined || value.acceptance === false) && value.expiresAt === undefined && value.maxPurchases === undefined)
}

export function selectableCreditPack(pack: CreditPack | undefined, now = Date.now()): pack is CreditPack {
  return validCreditPack(pack) && (pack.acceptance !== true || Date.parse(pack.expiresAt!) > now)
}

/** New method metadata is optional only for old servers; a partial new contract fails closed. */
export function validStripeMethods(value: unknown): value is StripePaymentMethod[] {
  if (!Array.isArray(value) || value.length !== methodIds.length) return false
  const seen = new Set<StripePaymentMethodId>()
  for (const method of value) {
    if (!object(method) || !isStripeMethod(method.id) || seen.has(method.id)
      || typeof method.enabled !== 'boolean' || typeof method.reason !== 'string'
      || typeof method.requiresCompatibleDevice !== 'boolean'
      || !Array.isArray(method.currencies) || !method.currencies.every(isCurrency)
      || new Set(method.currencies).size !== method.currencies.length
      || !Array.isArray(method.currencyOptions) || !method.currencyOptions.every(option => object(option)
        && isCurrency(option.currency) && typeof option.enabled === 'boolean' && typeof option.reason === 'string')
      || new Set(method.currencyOptions.map(option => (option as CurrencyOption).currency)).size !== method.currencyOptions.length
      || !Array.isArray(method.packages) || !method.packages.every(pack => validCreditPack(pack)
        && (method.currencies as PaymentCurrency[]).includes(pack.currency as PaymentCurrency)
        && (pack.paymentMethod === undefined || pack.paymentMethod === method.id))) return false
    if (new Set(method.packages.map(pack => (pack as CreditPack).id)).size !== method.packages.length) return false
    const supported = method.currencyOptions.filter(option => (option as CurrencyOption).enabled).map(option => (option as CurrencyOption).currency)
    if (supported.length !== method.currencies.length || !supported.every(currency => (method.currencies as PaymentCurrency[]).includes(currency))) return false
    seen.add(method.id)
  }
  return true
}

/** A legacy card catalog does not establish wallet activation or WeChat currency support. */
export function stripeMethods(catalog: StripeCatalog): StripePaymentMethod[] {
  if (catalog.paymentMethods !== undefined) {
    if (!validStripeMethods(catalog.paymentMethods)) return []
    return catalog.paymentMethods.map(method => ({ ...method, enabled: catalog.enabled && method.enabled }))
  }
  return methodIds.map(id => ({
    id,
    enabled: id === 'card' && catalog.enabled,
    reason: id === 'card' ? (catalog.enabled ? '' : 'PAYMENTS_CLOSED') : 'METHOD_METADATA_UNAVAILABLE',
    currencies: id === 'card' ? ['USD'] : [],
    currencyOptions: [{ currency: 'USD', enabled: id === 'card', reason: id === 'card' ? '' : 'METHOD_METADATA_UNAVAILABLE' }],
    packages: id === 'card' ? catalog.packages.filter(pack => pack.currency === 'USD' && validCreditPack(pack)) : [],
    requiresCompatibleDevice: id === 'apple_pay',
  }))
}

export function preferredCurrency(method: StripePaymentMethod | undefined, current: PaymentCurrency): PaymentCurrency {
  if (method?.currencies.includes(current)) return current
  return method?.currencies[0] ?? 'USD'
}

/** The browser sends identifiers only; the server owns the amount and order snapshot. */
export function stripeOrderRequest(method: StripePaymentMethod | undefined, pack: CreditPack | undefined, currency: PaymentCurrency) {
  if (!method?.enabled || !selectableCreditPack(pack) || !method.currencies.includes(currency)
    || pack.currency !== currency || !method.packages.some(offer => offer.id === pack.id && offer.currency === pack.currency
      && offer.amount === pack.amount && offer.credits === pack.credits)) throw new Error('Payment selection is not available')
  return { packageId: pack.id, paymentMethod: method.id }
}

export function paymentCurrencySymbol(currency: string): string {
  return currency === 'USD' ? '$' : currency === 'CNY' ? '¥' : currency === 'GBP' ? '£' : currency
}
