import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ load: vi.fn(), create: vi.fn(), eligible: vi.fn(), session: vi.fn(), start: vi.fn() }))
vi.mock('@paypal/paypal-js/sdk-v6', () => ({ loadCoreSdkScript: mocks.load }))
import { preparePaypalCheckout } from '../src/modules/identity/api/paypal-sdk'

describe('PayPal checkout adapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.load.mockResolvedValue({ createInstance: mocks.create })
    mocks.create.mockResolvedValue({ findEligibleMethods: mocks.eligible, createPayPalOneTimePaymentSession: mocks.session })
    mocks.eligible.mockResolvedValue({ isEligible: () => true })
    mocks.session.mockReturnValue({ start: mocks.start })
    mocks.start.mockImplementation(async (_options, order) => { await order })
  })
  it('does not load SDK when payment is disabled', async () => {
    expect(await preparePaypalCheckout({ enabled: false, clientId: '', environment: 'sandbox' }, { onApproved: vi.fn() })).toBeNull()
    expect(mocks.load).not.toHaveBeenCalled()
  })
  it('creates only on explicit start and only captures a matching server order', async () => {
    const capture = vi.fn().mockResolvedValue(undefined)
    const create = vi.fn().mockResolvedValue({ orderId: 'local-order', paypalOrderId: 'PAYPAL123' })
    const checkout = await preparePaypalCheckout({ enabled: true, clientId: 'public-id', environment: 'sandbox' }, { onApproved: capture })
    expect(create).not.toHaveBeenCalled()
    await checkout!.start(create)
    const callbacks = mocks.session.mock.calls[0][0]
    await expect(callbacks.onApprove({ orderId: 'DIFFERENT' })).rejects.toThrow()
    expect(capture).not.toHaveBeenCalled()
    await callbacks.onApprove({ orderId: 'PAYPAL123' })
    expect(capture).toHaveBeenCalledWith('local-order')
  })
  it('does not offer ineligible payment method', async () => {
    mocks.eligible.mockResolvedValue({ isEligible: () => false })
    expect(await preparePaypalCheckout({ enabled: true, clientId: 'public-id', environment: 'sandbox' }, { onApproved: vi.fn() })).toBeNull()
    expect(mocks.session).not.toHaveBeenCalled()
  })
})
