import { describe, expect, it } from 'vitest'
import { validateLoginIdentifier } from '../src/modules/identity/model/login-identifier'

describe('Logto login identifier', () => {
  it.each([
    ['email', '', '请输入电子邮箱。'],
    ['email', 'not-an-email', '请输入有效的电子邮箱地址。'],
    ['email', `${'a'.repeat(250)}@example.com`, '请输入有效的电子邮箱地址。'],
    ['phone', ' ', '请输入手机号。'],
    ['phone', '1234', '请输入有效的中国大陆手机号。'],
    ['phone', '+1 650 555 1234', '请输入有效的中国大陆手机号。'],
  ] as const)('rejects invalid %s input', (method, value, message) => {
    expect(validateLoginIdentifier(method, value)).toEqual({ valid: false, message })
  })
  it('normalizes valid input for Logto login_hint', () => {
    expect(validateLoginIdentifier('email', ' Singer@Example.COM ')).toEqual({ valid: true, value: 'singer@example.com' })
    expect(validateLoginIdentifier('phone', '138 0012 3456')).toEqual({ valid: true, value: '+8613800123456' })
  })
})
