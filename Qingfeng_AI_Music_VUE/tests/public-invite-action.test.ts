// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import Gate from '../src/modules/beta-access/BetaAccessGate.vue'
vi.mock('../src/modules/beta-access/api', () => ({
 readInviteStatus: vi.fn(async () => ({ enabled: true, admitted: false, csrfToken: 'test', expiresAt: '' })),
 enterInvite: vi.fn(), InviteError: class extends Error {}
}))
it('opens the invite input when a protected action is requested on a public page', async () => {
 const show = vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function(this: HTMLDialogElement) { this.open = true })
 const host = document.createElement('div'); document.body.append(host)
 const app = createApp(Gate, { publicAccess: true }); app.mount(host)
 try {
  await nextTick(); expect(show).not.toHaveBeenCalled()
  window.dispatchEvent(new Event('qingfeng:invite-required'))
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
  expect(host.querySelector('dialog')?.open).toBe(true)
  expect(host.querySelector('input')).not.toBeNull()
 } finally { app.unmount(); host.remove(); vi.restoreAllMocks() }
})

vi.mock('/brand/logo-symbol.svg', () => ({ default: '/brand/logo-symbol.svg' }))
