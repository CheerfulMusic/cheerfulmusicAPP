// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { setLocale } from '../src/shared/i18n'
import { hideLoginDialog, showLoginDialog } from '../src/shared/identity/login-dialog'

const auth = vi.hoisted(() => ({ status: vi.fn(), send: vi.fn(), signIn: vi.fn() }))
vi.mock('/brand/logo-symbol.svg', () => ({ default: '/brand/logo-symbol.svg' }))
vi.mock('vue-router', () => ({ useRoute: () => ({ fullPath: '/' }) }))
vi.mock('../src/shared/identity/account-state', () => ({ refreshAccountState: async () => ({ enabled: false, authenticated: false }) }))
vi.mock('../src/shared/identity/logto-state', () => ({ refreshLogtoState: async () => ({ enabled: false, authenticated: false }), resetLogtoState: () => {} }))
vi.mock('../src/modules/identity/api/google-login', () => ({ beginGoogleLogin: async () => '' }))
vi.mock('../src/modules/identity/api/native-login', () => ({
  readNativeLoginStatus: auth.status,
  sendLoginCode: auth.send,
  signInWithPhone: auth.signIn,
}))

import LoginModal from '../src/modules/identity/components/LoginModal.vue'

let wrapper: VueWrapper | undefined
beforeEach(() => {
  setLocale('zh-CN')
  auth.status.mockResolvedValue({
    emailPasswordEnabled: true, passwordResetEnabled: true, emailCodeEnabled: true,
    phoneEnabled: true, phoneInternationalEnabled: true, phoneAllowedRegions: ['CN', 'GB', 'JE'], authenticated: false,
  })
  auth.send.mockResolvedValue({ sent: true, resendAfter: 60 })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false; this.dispatchEvent(new Event('close')) } })
})
afterEach(() => { hideLoginDialog(); wrapper?.unmount(); wrapper = undefined; vi.clearAllMocks() })

async function phoneDialog() {
  wrapper = mount(LoginModal)
  showLoginDialog()
  await flushPromises()
  await wrapper.findAll('.method-switch button')[1]!.trigger('click')
  return wrapper
}

describe('international SMS sign-in', () => {
  it('sends for approved GB and blocks a Guernsey number entered under the same +44 selector', async () => {
    const dialog = await phoneDialog()
    await dialog.find('.country-selector select').setValue('GB')
    await dialog.find('#login-phone').setValue('07400 123456')
    await dialog.find('.send-code').trigger('click')
    await flushPromises()
    expect(auth.send).toHaveBeenCalledWith('phone', '+447400123456', 'sign-in')

    await dialog.find('#login-phone').setValue('07911 123456')
    expect(dialog.find('.send-code').attributes('disabled')).toBeDefined()
    expect(dialog.find('#login-feedback').text()).toContain('该地区短信暂未开通')
    await dialog.find('.send-code').trigger('click')
    expect(auth.send).toHaveBeenCalledTimes(1)
  })

  it('keeps other regions visible but closed despite a legacy global international switch', async () => {
    const dialog = await phoneDialog()
    await dialog.find('.country-selector select').setValue('FR')
    await dialog.find('#login-phone').setValue('06 12 34 56 78')
    expect(dialog.find('.send-code').attributes('disabled')).toBeDefined()
    expect(dialog.find('#login-feedback').text()).toContain('该地区短信暂未开通')

    await dialog.find('.country-selector select').setValue('JE')
    await dialog.find('#login-phone').setValue('07797 123456')
    expect(dialog.find('.send-code').attributes('disabled')).toBeUndefined()
    await dialog.find('.send-code').trigger('click')
    await flushPromises()
    expect(auth.send).toHaveBeenCalledWith('phone', '+447797123456', 'sign-in')
  })
})
