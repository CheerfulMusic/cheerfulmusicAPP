export type LoginIdentifierMethod = 'email' | 'phone'

export type LoginIdentifierResult =
  | { valid: true; value: string }
  | { valid: false; message: string }

/** Normalize the identifier before it is used as Logto's login_hint. */
export function validateLoginIdentifier(method: LoginIdentifierMethod, rawValue: string): LoginIdentifierResult {
  const value = rawValue.trim()
  if (!value) return { valid: false, message: method === 'email' ? '请输入电子邮箱。' : '请输入手机号。' }
  if (method === 'email') {
    if (value.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value))
      return { valid: false, message: '请输入有效的电子邮箱地址。' }
    return { valid: true, value: value.toLowerCase() }
  }
  const digits = value.replace(/[\s-]/g, '')
  if (!/^1[3-9]\d{9}$/.test(digits)) return { valid: false, message: '请输入有效的中国大陆手机号。' }
  return { valid: true, value: `+86${digits}` }
}
