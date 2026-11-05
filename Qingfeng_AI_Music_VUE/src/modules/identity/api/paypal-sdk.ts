import { loadCoreSdkScript } from '@paypal/paypal-js/sdk-v6'

export type PaypalSdkConfig = { enabled: boolean; clientId: string; environment: 'sandbox' | 'production' }
export type PaypalSdkOrder = { orderId: string; paypalOrderId: string }
export type PaypalCheckoutHandlers = {
  onApproved: (localOrderId: string) => Promise<void>
  onCancel?: () => void
  onError?: () => void
}
export type PaypalCheckout = { start: (createOrder: () => Promise<PaypalSdkOrder>) => Promise<void> }

/** Prepare PayPal's official SDK; this does not create an order or capture funds. */
export async function preparePaypalCheckout(config: PaypalSdkConfig, handlers: PaypalCheckoutHandlers): Promise<PaypalCheckout | null> {
  if (!config.enabled || !config.clientId || !['sandbox', 'production'].includes(config.environment)) return null
  const paypal = await loadCoreSdkScript({ environment: config.environment })
  if (!paypal) return null
  const instance = await paypal.createInstance({ clientId: config.clientId, components: ['paypal-payments'], pageType: 'checkout' })
  if (!(await instance.findEligibleMethods()).isEligible('paypal')) return null

  let pending: Promise<PaypalSdkOrder> | null = null
  let busy = false
  const session = instance.createPayPalOneTimePaymentSession({
    onApprove: async data => {
      const order = await pending
      if (!order || !order.orderId || !order.paypalOrderId || data.orderId !== order.paypalOrderId) {
        handlers.onError?.()
        throw new Error('PayPal order does not match the server order')
      }
      await handlers.onApproved(order.orderId)
    },
    onCancel: () => handlers.onCancel?.(),
    onError: () => handlers.onError?.(),
  })
  return {
    async start(createOrder) {
      if (busy) throw new Error('PayPal checkout is already in progress')
      busy = true
      try {
        pending = Promise.resolve().then(createOrder).then(order => {
          if (!order.orderId || !/^[A-Za-z0-9]{1,32}$/.test(order.paypalOrderId)) throw new Error('Invalid PayPal order')
          return order
        })
        await session.start({ presentationMode: 'popup' }, pending.then(order => ({ orderId: order.paypalOrderId })))
      } finally { busy = false }
    },
  }
}
