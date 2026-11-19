import { afterEach, describe, expect, it, vi } from 'vitest'
import { setAnalyticsEnabled, trackPageView } from '../src/shared/analytics/track'
import { ApiError, resetCreatorSession, retryAfterSeconds } from '../src/shared/http/api-client'
import { readNativeLoginStatus, registerWithEmail, resetEmailPassword, sendLoginCode, signInWithEmail, signInWithEmailCode, signInWithPhone } from '../src/modules/identity/api/native-login'
import { validNewPassword, loginCodeKey, nativeLoginError, resendSeconds } from '../src/modules/identity/model/native-login'
import { callingCode, countryOptions, internationalPhone, internationalSmsPhone, phoneCountries, smsRegionAvailable } from '../src/modules/identity/model/international-phone'

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
  it('starts an authenticated page view only after phone sign-in succeeds', async () => {
    vi.stubGlobal('location', { pathname: '/create' })
    vi.stubGlobal('document', { referrer: '', visibilityState: 'visible' })
    const beacon = vi.fn(() => true)
    vi.stubGlobal('navigator', { sendBeacon: beacon })
    setAnalyticsEnabled(true)
    try {
      trackPageView('create')
      beacon.mockClear()
      mockServer({ sent: true })
      await expect(signInWithPhone('+8613800123456', '123456')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
      expect(beacon).not.toHaveBeenCalled()
      mockServer({ authenticated: true })
      await signInWithPhone('+8613800123456', '123456')
      expect(beacon).toHaveBeenCalledTimes(1)
      const payload = await (beacon.mock.calls[0] as unknown as [string, Blob])[1].text()
      expect(JSON.parse(payload)).toMatchObject({ event: 'pageview', pagePath: '/create' })
      expect(payload).not.toContain('+8613800123456')
    } finally {
      setAnalyticsEnabled(false)
    }
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
    const status = {emailPasswordEnabled:false,emailCodeEnabled:true,passwordResetEnabled:false,phoneEnabled:true,phoneInternationalEnabled:true,phoneAllowedRegions:['CN','GB','JE'],authenticated:false}
    mockServer(status)
    await expect(readNativeLoginStatus()).resolves.toEqual(status)
    const {phoneInternationalEnabled:_,...missingCapability} = status
    mockServer(missingCapability)
    await expect(readNativeLoginStatus()).rejects.toMatchObject({code:'INVALID_RESPONSE'})
    const {phoneAllowedRegions:__,...legacyStatus} = status
    mockServer(legacyStatus)
    await expect(readNativeLoginStatus()).resolves.toEqual(legacyStatus)
    mockServer({...status,phoneAllowedRegions:['CN','invalid']})
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
    ['GB','07400 123456','+447400123456'],
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
  it('uses the actual mobile region even when +44 is shared or an international prefix is pasted', () => {
    expect(internationalSmsPhone('07400 123456','GB')).toEqual({number:'+447400123456',region:'GB'})
    expect(internationalSmsPhone('07797 123456','GB')).toEqual({number:'+447797123456',region:'JE'})
    expect(internationalSmsPhone('07911 123456','GB')).toEqual({number:'+447911123456',region:'GG'})
    expect(internationalSmsPhone('+44 7797 123456','CN')).toEqual({number:'+447797123456',region:'JE'})
    expect(internationalSmsPhone('(213) 373-4253','US')).toBeUndefined()
  })
  it('opens only server-listed regions and fails closed for international numbers on older servers', () => {
    const allowed = ['CN','GB','JE']
    expect(smsRegionAvailable(true,allowed,'GB')).toBe(true)
    expect(smsRegionAvailable(true,allowed,'JE')).toBe(true)
    expect(smsRegionAvailable(true,allowed,'GG')).toBe(false)
    expect(smsRegionAvailable(true,allowed,'FR')).toBe(false)
    expect(smsRegionAvailable(false,allowed,'GB')).toBe(false)
    expect(smsRegionAvailable(true,undefined,'CN')).toBe(true)
    expect(smsRegionAvailable(true,undefined,'GB')).toBe(false)
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

it.each(['12345678', 'abcdefgh', 'ABCDEFGH', 'aaaaaaaa', '1'.repeat(128), 'abcd1234'])('accepts length-only password %s', value => {
  expect(validNewPassword(value)).toBe(true)
})
it.each(['', '1234567', 'a'.repeat(129)])('rejects password outside length bounds', value => {
  expect(validNewPassword(value)).toBe(false)
})
