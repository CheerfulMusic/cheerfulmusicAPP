import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, resetCreatorSession, retryAfterSeconds } from '../src/shared/http/api-client'
import { readNativeLoginStatus, registerWithEmail, resetEmailPassword, sendLoginCode, signInWithEmail, signInWithEmailCode, signInWithPhone } from '../src/modules/identity/api/native-login'
import { loginCodeKey, nativeLoginError, resendSeconds } from '../src/modules/identity/model/native-login'
import { callingCode, countryOptions, internationalPhone, phoneCountries } from '../src/modules/identity/model/international-phone'

afterEach(() => { vi.unstubAllGlobals(); resetCreatorSession() })
const reply = (body: unknown) => new Response(JSON.stringify(body), { status: 200 })
function mockServer(result: unknown) {
  const fetch = vi.fn(async (url: string) => url.endsWith('/csrf') ? reply({token:'native-csrf'})
    : url.endsWith('/session') ? reply({creatorId:'existing-creator'}) : reply(result))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

describe('same-site sign-in', () => {
  it.each([
    ['password/sign-in', () => signInWithEmail('singer@example.com', '  Exact password 9  '), { email:'singer@example.com', password:'  Exact password 9  ' }, {authenticated:true}],
    ['email/sign-in', () => signInWithEmailCode('singer@example.com', '123456'), {email:'singer@example.com',code:'123456'}, {authenticated:true}],
    ['register', () => registerWithEmail('singer@example.com','New password 9','123456'), {email:'singer@example.com',password:'New password 9',code:'123456'}, {authenticated:true}],
    ['password/reset', () => resetEmailPassword('singer@example.com','Reset password 9','123456'), {email:'singer@example.com',password:'Reset password 9',code:'123456'}, {reset:true}],
    ['phone/sign-in', () => signInWithPhone('+8613800123456','123456'), {phone:'+8613800123456',code:'123456'}, {authenticated:true}],
  ] as const)('%s keeps credentials in a CSRF-protected POST and preserves the current collection', async (path, operation, body, result) => {
    const fetch = mockServer(result)
    await operation()
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/public/csrf','/api/public/session',`/api/public/auth/native/${path}`])
    expect(fetch).toHaveBeenLastCalledWith(`/api/public/auth/native/${path}`, expect.objectContaining({
      method:'POST',credentials:'same-origin',body:JSON.stringify(body),headers:expect.objectContaining({'X-XSRF-TOKEN':'native-csrf'}),
    }))
  })
  it('sends only the explicit channel, normalized address and purpose', async () => {
    const fetch = mockServer({sent:true,resendAfter:60})
    await expect(sendLoginCode('email','singer@example.com','register')).resolves.toEqual({sent:true,resendAfter:60})
    expect(fetch).toHaveBeenLastCalledWith('/api/public/auth/native/code',expect.objectContaining({body:JSON.stringify({channel:'email',identifier:'singer@example.com',purpose:'register'})}))
  })
  it('does not accept a redirect or incomplete result as successful local sign-in', async () => {
    mockServer({authorizationUrl:'https://provider.example/login'})
    await expect(signInWithEmail('singer@example.com','password')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
    mockServer({sent:true})
    await expect(sendLoginCode('phone','+8613800123456','sign-in')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  })
  it('requires explicit capabilities, including reset and international SMS support', async () => {
    const status = {emailPasswordEnabled:false,emailCodeEnabled:true,passwordResetEnabled:false,phoneEnabled:true,phoneInternationalEnabled:false,authenticated:false}
    mockServer(status)
    await expect(readNativeLoginStatus()).resolves.toEqual(status)
    const {phoneInternationalEnabled:_,...missingCapability} = status
    mockServer(missingCapability)
    await expect(readNativeLoginStatus()).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  })
})

describe('international phone input', () => {
  it('offers the complete country and territory list, with localized region names', () => {
    const english = countryOptions('en'), chinese = countryOptions('zh-CN')
    expect(new Set(english.map(item => item.country))).toEqual(new Set(phoneCountries))
    expect(english.length).toBeGreaterThan(240)
    expect(english.find(item => item.country === 'GB')).toMatchObject({name:'United Kingdom',code:'+44'})
    expect(chinese.find(item => item.country === 'HK')?.name).toContain('香港')
    expect(callingCode('US')).toBe('+1')
  })
  it.each([
    ['CN','138 0012 3456','+8613800123456'],
    ['GB','07911 123456','+447911123456'],
    ['US','(213) 373-4253','+12133734253'],
    ['HK','5123 4567','+85251234567'],
    ['SG','8123 4567','+6581234567'],
    ['JP','090-1234-5678','+819012345678'],
    ['AU','0412 345 678','+61412345678'],
    ['CN','+44 7911 123456','+447911123456'],
    ['CN','0044 7911 123456','+447911123456'],
  ] as const)('normalizes %s input without losing national trunk prefix rules', (country, input, expected) => {
    expect(internationalPhone(input,country)).toBe(expected)
  })
  it.each(['1234','call +44 7911 123456','+44 7911 123456 ext 2','+999123456789','+123456789012345678','13800123456<script>'])('rejects incomplete numbers and embedded text: %s', input => {
    expect(internationalPhone(input,'CN')).toBeUndefined()
  })
})

describe('verification form state', () => {
  it('separates resends by recipient, purpose and channel', () => {
    const key = loginCodeKey('email','singer@example.com','register')
    expect(key).not.toBe(loginCodeKey('email','other@example.com','register'))
    expect(key).not.toBe(loginCodeKey('email','singer@example.com','reset'))
    expect(key).not.toBe(loginCodeKey('email','singer@example.com','sign-in'))
    expect(key).not.toBe(loginCodeKey('phone','+8613800123456','sign-in'))
    expect(resendSeconds(61000,1000)).toBe(60)
    expect(resendSeconds(61000,60999)).toBe(1)
    expect(resendSeconds(61000,62000)).toBe(0)
  })
  it('maps region restrictions clearly and keeps provider diagnostics out of user errors', () => {
    expect(nativeLoginError('LOGIN_REGION_UNAVAILABLE',400)).toBe('该地区短信暂未开通，请使用邮箱登录。')
    const upstream = new ApiError('Logto internal https://provider.example/token secret',503,'LOGIN_UNAVAILABLE')
    expect(nativeLoginError(upstream.code,upstream.status)).toBe('当前登录方式暂不可用，请稍后重试。')
    expect(nativeLoginError('UNKNOWN_INTERNAL_ERROR',500)).not.toMatch(/Logto|provider|token|secret/)
  })
})

 it('guides an existing verified email to login instead of blaming the code', () => {
   expect(nativeLoginError('LOGIN_ACCOUNT_EXISTS', 409)).toContain('已注册')
   expect(nativeLoginError('LOGIN_ACCOUNT_EXISTS', 409)).not.toContain('验证码不正确')
 })

it('honors hour-long retry windows and rejects malformed retry headers', () => {
  expect(retryAfterSeconds('3600')).toBe(3600)
  expect(retryAfterSeconds('60')).toBe(60)
  expect(retryAfterSeconds(null)).toBeUndefined()
  expect(retryAfterSeconds('invalid')).toBeUndefined()
})
