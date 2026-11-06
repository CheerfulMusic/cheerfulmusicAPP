import type { CodeChannel, CodePurpose } from '../api/native-login'

export function loginCodeKey(channel: CodeChannel, identifier: string, purpose: CodePurpose) {
  return `${channel}:${purpose}:${identifier}`
}

export function resendSeconds(availableAt: number, now = Date.now()) {
  return Math.max(0, Math.ceil((availableAt - now) / 1000))
}

/** Do not display upstream messages: provider details and internal URLs are not user copy. */
export function nativeLoginError(code?: string, status?: number): string {
  if (code === 'LOGIN_INVALID_CREDENTIALS') return '邮箱或密码不正确，请重新输入。'
  if (code === 'LOGIN_ACCOUNT_EXISTS') return '该账号已注册，请返回登录；忘记密码可选择找回密码。'
  if (code === 'LOGIN_CODE_INVALID') return '验证码不正确或已过期，请检查后重试。'
  if (code === 'LOGIN_FLOW_EXPIRED') return '验证已过期，请重新发送验证码。'
  if (code === 'LOGIN_PASSWORD_REJECTED') return '密码不符合要求，请使用至少8位字符并包含字母和数字。'
  if (code === 'LOGIN_ACCOUNT_CONFLICT') return '该账号已有登录记录，请使用原有方式登录。'
  if (code === 'LOGIN_REGION_UNAVAILABLE') return '该地区短信暂未开通，请使用邮箱登录。'
  if (code === 'LOGIN_RATE_LIMITED' || status === 429) return '操作过于频繁，请稍后重试。'
  if (code === 'INVALID_LOGIN_INPUT') return '请检查邮箱、手机号及填写的信息。'
  if (code === 'LOGIN_UNAVAILABLE' || status === 503) return '当前登录方式暂不可用，请稍后重试。'
  return '暂时无法完成操作，请检查网络后重试。'
}
