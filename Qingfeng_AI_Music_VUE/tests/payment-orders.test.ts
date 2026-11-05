import { describe, expect, it } from 'vitest'
import { readPendingOrders, savePendingOrders } from '../src/modules/identity/model/payment-orders'
function storage(): Storage {
  const data = new Map<string,string>()
  return {get length(){return data.size}, clear(){data.clear()}, getItem(k){return data.get(k) ?? null}, key(i){return [...data.keys()][i] ?? null}, removeItem(k){data.delete(k)}, setItem(k,v){data.set(k,v)}}
}
const order = '12345678-1234-1234-1234-123456789abc'
describe('pending payment recovery', () => {
  it('isolates order hints by creator and deduplicates them', () => {
    const s = storage(); savePendingOrders(s, 'creator-a', [order, order])
    expect(readPendingOrders(s, 'creator-a')).toEqual([order])
    expect(readPendingOrders(s, 'creator-b')).toEqual([])
    savePendingOrders(s, 'creator-a', [])
    expect(readPendingOrders(s, 'creator-a')).toEqual([])
  })
  it('refuses invalid ids and corrupt or inaccessible storage', () => {
    const s = storage()
    expect(() => savePendingOrders(s, 'creator-a', ['../../other'])).toThrow()
    s.setItem('qingfeng.paymentOrders.creator-a', '{}')
    expect(() => readPendingOrders(s, 'creator-a')).toThrow()
    expect(() => readPendingOrders(s, '')).toThrow()
  })
})
