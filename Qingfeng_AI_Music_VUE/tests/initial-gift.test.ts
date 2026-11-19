// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { takeInitialGift } from '../src/modules/gift/model/initial-gift'

const token = 'A'.repeat(43)
const gift = { token, title: 'Song', recipient: null, message: null, lyrics: 'line', audioOrigin: 'GENERATED',
  createdAt: '2026-11-18T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', hasCover: false }

function install(value: unknown, path = `/gift/${token}`) {
  window.history.replaceState({}, '', path)
  const element = document.createElement('script')
  element.type = 'application/json'
  element.id = 'qf-initial-gift'
  element.textContent = JSON.stringify(value)
  document.body.appendChild(element)
}

afterEach(() => { document.body.replaceChildren(); window.history.replaceState({}, '', '/') })

describe('initial share response', () => {
  it('reads the matching initial response once', () => {
    install(gift)
    expect(takeInitialGift(token)?.title).toBe('Song')
    expect(takeInitialGift(token)).toBeUndefined()
  })

  it('ignores bootstrap data for a different token, an expired link or an embedded view', () => {
    install({ ...gift, token: 'B'.repeat(43) })
    expect(takeInitialGift(token)).toBeUndefined()
    install({ ...gift, expiresAt: '2000-01-01T00:00:00Z' })
    expect(takeInitialGift(token)).toBeUndefined()
    install(gift, '/agent')
    expect(takeInitialGift(token)).toBeUndefined()
  })
})
