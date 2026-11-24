// @vitest-environment happy-dom
import { createApp, h, nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { expect, it, vi } from 'vitest'
import Gate from '../src/modules/beta-access/BetaAccessGate.vue'
import { locale, setLocale } from '../src/shared/i18n'
import { sitePage } from '../src/shared/i18n/site-seo'
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

it('automatically opens the homepage invite with the localized product name', async () => {
 const show = vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function(this: HTMLDialogElement) { this.open = true })
 const host = document.createElement('div'); document.body.append(host)
 const previous = locale.value
 setLocale('zh-CN')
 const app = createApp(Gate, { homeEntry: true }); app.mount(host)
 try {
  await nextTick()
  expect(show).toHaveBeenCalled()
  expect(host.querySelector('h1')?.textContent).toBe('青风音乐｜把你的歌词做成歌')
  expect(host.querySelector('.beta-access-intro')?.textContent).toContain('青风音乐（Cheerful Music）')
  setLocale('en')
  await nextTick()
  expect(host.querySelector('h1')?.textContent).toBe('Everyone Can Be a Creator')
  expect(host.querySelector('.beta-access-brand')?.textContent).toBe('Cheerful Music')
  expect(host.querySelector('.beta-access-intro')?.textContent).toBe(sitePage('/en/')!.description)
  expect(host.querySelector('.beta-access-intro')?.closest('[data-nosnippet]')).toBeNull()
  await flushPromises()
  expect(host.querySelector('[data-nosnippet] #beta-invite-code')).not.toBeNull()
 } finally { app.unmount(); host.remove(); setLocale(previous); vi.restoreAllMocks() }
})

it('keeps the public preview readable and still requires an invitation for the app', async () => {
 const show = vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function(this: HTMLDialogElement) { this.open = true })
 const close = vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(function(this: HTMLDialogElement) { this.open = false })
 const host = document.createElement('div'); document.body.append(host)
 const app = createApp({
  render: () => h(Gate, { publicPreview: true }, {
   default: () => h('p', 'Protected app'),
   'public-preview': ({ openInvite }: { openInvite: () => void }) => h('button', { onClick: openInvite }, 'Open invite'),
  }),
 })
 app.mount(host)
 try {
  await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
  expect(host.textContent).not.toContain('Protected app')
  expect(show).not.toHaveBeenCalled()
  host.querySelector('button')?.click()
  await nextTick()
  const dialog = host.querySelector('dialog')!
  expect(dialog.open).toBe(true)
  dialog.dispatchEvent(new Event('cancel', { cancelable: true }))
  await nextTick()
  expect(close).toHaveBeenCalled()
  expect(dialog.open).toBe(false)
  expect(host.textContent).toContain('Open invite')
  expect(host.textContent).not.toContain('Protected app')
 } finally { app.unmount(); host.remove(); vi.restoreAllMocks() }
})
