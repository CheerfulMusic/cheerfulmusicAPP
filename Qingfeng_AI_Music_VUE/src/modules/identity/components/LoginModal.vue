<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { locale, t } from '../../../shared/i18n'
import { ApiError, ensureCreator, resetCreatorSession } from '../../../shared/http/api-client'
import { hideLoginDialog, loginDialogOpen } from '../../../shared/identity/login-dialog'
import { refreshAccountState } from '../../../shared/identity/account-state'
import { refreshLogtoState, resetLogtoState } from '../../../shared/identity/logto-state'
import { beginGoogleLogin } from '../api/google-login'
import { readNativeLoginStatus, registerWithEmail, resetEmailPassword, sendLoginCode, signInWithEmail, signInWithEmailCode, signInWithPhone, type CodePurpose, type NativeLoginStatus } from '../api/native-login'
import { callingCode, countryOptions, internationalPhone, type PhoneCountry } from '../model/international-phone'
import { loginCodeKey, nativeLoginError, resendSeconds } from '../model/native-login'
import { validateLoginIdentifier } from '../model/login-identifier'
import GoogleMark from './GoogleMark.vue'

type EmailMode = 'sign-in' | 'register' | 'reset' | 'code'
type Field = 'identifier' | 'password' | 'code' | ''
const route = useRoute()
const dialog = ref<HTMLDialogElement>(), closeButton = ref<HTMLButtonElement>(), form = ref<HTMLFormElement>()
const method = ref<'email' | 'phone'>('email'), emailMode = ref<EmailMode>('sign-in')
const email = ref(''), phone = ref(''), country = ref<PhoneCountry>('CN'), password = ref(''), code = ref('')
const showPassword = ref(false), statusLoading = ref(false), googleReady = ref(false), statusFailed = ref(false)
const nativeStatus = ref<NativeLoginStatus>(), busy = ref(false), sending = ref(false), openingGoogle = ref(false)
const error = ref(''), notice = ref(''), fieldError = ref<Field>(''), sentCodeKey = ref('')
const now = ref(Date.now()), resendAt = ref<Record<string, number>>({})
let ticker: ReturnType<typeof setInterval> | undefined
let epoch = 0, returnFocus: HTMLElement | null = null
const countries = computed(() => countryOptions(locale.value))
const selectedCallingCode = computed(() => callingCode(country.value))
const passwordAvailable = computed(() => nativeStatus.value?.emailPasswordEnabled === true)
const emailUsesCode = computed(() => emailMode.value === 'code' || !passwordAvailable.value)
const codePurpose = computed<CodePurpose>(() => method.value === 'phone' || emailUsesCode.value ? 'sign-in' : emailMode.value === 'reset' ? 'reset' : 'register')
const needsCode = computed(() => method.value === 'phone' || emailUsesCode.value || emailMode.value !== 'sign-in')
const needsPassword = computed(() => method.value === 'email' && !emailUsesCode.value)
const normalizedIdentifier = computed(() => {
  if (method.value === 'phone') return internationalPhone(phone.value, country.value) || ''
  const value = validateLoginIdentifier('email', email.value)
  return value.valid ? value.value : ''
})
const codeKey = computed(() => normalizedIdentifier.value ? loginCodeKey(method.value, normalizedIdentifier.value, codePurpose.value) : '')
const remaining = computed(() => resendSeconds(resendAt.value[codeKey.value] || 0, now.value))
const locked = computed(() => busy.value || sending.value || openingGoogle.value)
const phoneRegionUnavailable = computed(() => method.value === 'phone' && nativeStatus.value?.phoneEnabled === true && !nativeStatus.value.phoneInternationalEnabled
  && (selectedCallingCode.value !== '+86' || !!normalizedIdentifier.value && !normalizedIdentifier.value.startsWith('+86')))
const channelReady = computed(() => method.value === 'phone' ? nativeStatus.value?.phoneEnabled === true && !phoneRegionUnavailable.value
  : emailMode.value === 'reset' ? nativeStatus.value?.passwordResetEnabled === true && nativeStatus.value.emailCodeEnabled
  : emailUsesCode.value || emailMode.value !== 'sign-in' ? nativeStatus.value?.emailCodeEnabled === true : passwordAvailable.value)
const submitLabel = computed(() => method.value === 'phone' || emailUsesCode.value ? '登录 / 注册' : emailMode.value === 'register' ? '注册并开始创作' : emailMode.value === 'reset' ? '重设密码' : '登录')
const title = computed(() => emailMode.value === 'reset' && needsPassword.value ? '重设密码' : '欢迎来到 Cheerful Music')

watch(codeKey, () => { code.value = ''; sentCodeKey.value = '' })
watch(loginDialogOpen, async open => {
  if (!open) { if (dialog.value?.open) dialog.value.close(); return }
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  method.value = 'email'; emailMode.value = 'sign-in'
  email.value = ''; phone.value = ''; password.value = ''; code.value = ''; country.value = 'CN'
  showPassword.value = false; error.value = ''; notice.value = ''; fieldError.value = ''; sentCodeKey.value = ''
  const version = ++epoch
  await nextTick()
  if (!loginDialogOpen.value || version !== epoch || !dialog.value) return
  dialog.value.showModal(); closeButton.value?.focus()
  now.value = Date.now()
  ticker = setInterval(() => { now.value = Date.now() }, 1000)
  await loadStatus(version)
})
watch(() => route.fullPath, () => { if (loginDialogOpen.value) hideLoginDialog() })
onBeforeUnmount(() => { if (ticker) clearInterval(ticker) })

async function loadStatus(version = epoch) {
  statusLoading.value = true; statusFailed.value = false; nativeStatus.value = undefined; googleReady.value = false
  const results = await Promise.allSettled([readNativeLoginStatus(), refreshAccountState()])
  if (version !== epoch || !loginDialogOpen.value) return
  const [native, google] = results
  if (native.status === 'fulfilled') nativeStatus.value = native.value
  if (google.status === 'fulfilled') googleReady.value = google.value.enabled
  statusFailed.value = results.some(result => result.status === 'rejected')
  statusLoading.value = false
}
function close() { hideLoginDialog() }
function onClosed() {
  epoch++
  if (ticker) { clearInterval(ticker); ticker = undefined }
  password.value = ''; code.value = ''; email.value = ''; phone.value = ''
  error.value = ''; notice.value = ''; sentCodeKey.value = ''; fieldError.value = ''
  hideLoginDialog()
  if (returnFocus?.isConnected) returnFocus.focus()
  returnFocus = null
}
function onBackdropClick(event: MouseEvent) { if (event.target === dialog.value) close() }
function changeMethod(next: 'email' | 'phone') {
  if (locked.value) return
  method.value = next; emailMode.value = 'sign-in'; clearSensitive()
}
function changeMode(next: EmailMode) {
  if (locked.value || next === 'reset' && !nativeStatus.value?.passwordResetEnabled) return
  emailMode.value = next; clearSensitive()
}
function clearSensitive() {
  password.value = ''; code.value = ''; sentCodeKey.value = ''; showPassword.value = false
  error.value = ''; notice.value = ''; fieldError.value = ''
}
function clearError() { error.value = ''; fieldError.value = '' }
function identifierEdited() { clearError(); notice.value = '' }
function invalid(message: string, field: Field) {
  error.value = message; fieldError.value = field
  void nextTick(() => form.value?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus())
}
function checkIdentifier() {
  if (normalizedIdentifier.value) return true
  invalid(method.value === 'phone' ? '请选择国家或地区，并输入有效的手机号。' : '请输入有效的电子邮箱地址。', 'identifier')
  return false
}
function showFailure(failure: unknown) {
  const api = failure instanceof ApiError ? failure : undefined
  error.value = nativeLoginError(api?.code, api?.status)
  if (api?.code === 'LOGIN_FLOW_EXPIRED') { code.value = ''; sentCodeKey.value = '' }
  if (api?.status === 429 && codeKey.value) resendAt.value = { ...resendAt.value, [codeKey.value]: Date.now() + (api.retryAfter || 60) * 1000 }
}
async function sendCode() {
  if (locked.value || remaining.value || !channelReady.value || !checkIdentifier()) return
  const version = epoch, key = codeKey.value, identifier = normalizedIdentifier.value
  sending.value = true; error.value = ''; notice.value = ''
  try {
    const result = await sendLoginCode(method.value, identifier, codePurpose.value)
    resendAt.value = { ...resendAt.value, [key]: Date.now() + Math.max(1, result.resendAfter) * 1000 }
    now.value = Date.now()
    if (version !== epoch || !loginDialogOpen.value || key !== codeKey.value) return
    sentCodeKey.value = key
    notice.value = method.value === 'phone' ? '验证码已发送，请查收短信。' : '验证码已发送，请查收邮箱（包括垃圾邮件）。'
    await nextTick(); form.value?.querySelector<HTMLInputElement>('#login-code')?.focus()
  } catch (failure) { if (version === epoch && loginDialogOpen.value) showFailure(failure) }
  finally { sending.value = false }
}
async function refreshAccountAfterSessionChange() {
  resetLogtoState()
  resetCreatorSession()
  await Promise.allSettled([refreshAccountState(), refreshLogtoState()])
  resetCreatorSession()
  await ensureCreator().catch(() => undefined)
  window.dispatchEvent(new Event('qingfeng:identity-changed'))
}
async function submit() {
  if (locked.value || !channelReady.value || !checkIdentifier()) return
  if (needsCode.value && (sentCodeKey.value !== codeKey.value || !/^\d{6}$/.test(code.value.trim()))) {
    invalid('请先发送验证码，再输入收到的6位验证码。', 'code'); return
  }
  if (needsPassword.value) {
    if (!password.value || password.value.length > 128) { invalid('请输入密码。', 'password'); return }
    if (emailMode.value !== 'sign-in' && (password.value.length < 8 || !/[A-Za-z]/.test(password.value) || !/\d/.test(password.value))) {
      invalid('密码不符合要求，请使用至少8位字符并包含字母和数字。', 'password'); return
    }
  }
  const version = epoch, identifier = normalizedIdentifier.value
  busy.value = true; error.value = ''; notice.value = ''
  try {
    if (method.value === 'phone') await signInWithPhone(identifier, code.value.trim())
    else if (emailUsesCode.value) await signInWithEmailCode(identifier, code.value.trim())
    else if (emailMode.value === 'reset') {
      await resetEmailPassword(identifier, password.value, code.value.trim())
      await refreshAccountAfterSessionChange()
      if (version === epoch && loginDialogOpen.value) { emailMode.value = 'sign-in'; clearSensitive(); notice.value = '密码已重设，请使用新密码登录。' }
      return
    } else if (emailMode.value === 'register') await registerWithEmail(identifier, password.value, code.value.trim())
    else await signInWithEmail(identifier, password.value)
    // The session cookie has already changed. Refresh shared state even if the dialog was closed meanwhile.
    await refreshAccountAfterSessionChange()
    if (version === epoch && loginDialogOpen.value) close()
  } catch (failure) { if (version === epoch && loginDialogOpen.value) showFailure(failure) }
  finally { busy.value = false; password.value = '' }
}
async function continueWithGoogle() {
  if (locked.value || statusLoading.value || !googleReady.value) return
  openingGoogle.value = true; error.value = ''
  const version = epoch
  try {
    const url = await beginGoogleLogin()
    if (version === epoch && loginDialogOpen.value) window.location.assign(url)
  } catch { if (version === epoch && loginDialogOpen.value) error.value = '暂时无法开始 Google 登录，请稍后重试。' }
  finally { openingGoogle.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="login-modal" aria-modal="true" aria-labelledby="login-title" aria-describedby="login-description login-bonus" @click="onBackdropClick" @close="onClosed">
    <div class="login-card">
      <svg class="corner-wave wave-top" viewBox="0 0 240 110" aria-hidden="true"><path d="M0 60C62-2 105 11 157 63S221 98 240 74V110H0Z" fill="#ff756b" opacity=".1"/><path d="M0 85C57 17 98 31 150 73S213 120 240 91" fill="none" stroke="white" stroke-width="2" opacity=".8"/></svg>
      <svg class="corner-wave wave-bottom" viewBox="0 0 240 170" aria-hidden="true"><path d="M0 0C83 37 42 96 144 117S209 153 240 170H0Z" fill="#ff776e" opacity=".08"/><path d="M0 88C81 39 106 156 240 170H0Z" fill="#ff766c" opacity=".13"/><path d="M0 80C84 30 111 149 240 161" fill="none" stroke="white" stroke-width="2"/></svg>
      <header class="login-brand"><img src="/brand/logo-symbol.svg" alt="" width="35" height="35"><strong>Cheerful Music</strong></header>
      <button ref="closeButton" class="close-button" type="button" :aria-label="t('关闭登录弹窗')" @click="close"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg></button>
      <div class="login-content">
        <h2 id="login-title">{{ t(title) }}</h2>
        <p id="login-description" class="login-description">{{ t('登录账号，开启你的音乐之旅') }}</p>
        <p id="login-bonus" class="login-bonus"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 11h16v10H4zM2 7h20v4H2zM12 7v14M12 7H8.5A2.5 2.5 0 1 1 11 4.5L12 7Zm0 0h3.5A2.5 2.5 0 1 0 13 4.5L12 7Z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg><span>{{ t('新用户首次注册赠送100积分，每个账号限领一次。') }}</span></p>

        <div class="social-options" :aria-label="t('社交账号登录')">
          <button class="social-button" type="button" :disabled="statusLoading || !googleReady || locked" :aria-busy="openingGoogle" @click="continueWithGoogle"><GoogleMark/><span>{{ t(openingGoogle ? '正在前往 Google…' : '使用 Google 继续') }}</span><small v-if="statusLoading">{{ t('检查中…') }}</small><small v-else-if="!googleReady">{{ t('暂不可用') }}</small></button>
          <button class="social-button coming-soon" type="button" disabled><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M18.7 19.5c-.8 1.2-1.7 2.5-3.1 2.5-1.3 0-1.8-.8-3.3-.8s-2 .8-3.3.8c-1.3.1-2.3-1.3-3.2-2.6-1.7-2.5-3-7-.3-10.1a4.9 4.9 0 0 1 4.1-2.5c1.3 0 2.5.9 3.3.9.8 0 2.3-1.1 3.9-.9a4.8 4.8 0 0 1 3.7 2c-.1.1-2.2 1.3-2.2 3.8 0 3.1 2.7 4.1 2.7 4.1 0 .1-.4 1.5-1.4 3ZM14 5.1A5.4 5.4 0 0 0 15.3 1a5.5 5.5 0 0 0-3.6 1.9 5.2 5.2 0 0 0-1.3 4c1.4.1 2.8-.4 3.6-1.8Z"/></svg><span>{{ t('使用 Apple 继续') }}</span><small>{{ t('待开放') }}</small></button>
          <button class="social-button coming-soon" type="button" disabled><svg viewBox="0 0 28 24" fill="currentColor" aria-hidden="true"><path d="M11 2C5.5 2 1 5.6 1 10c0 2.5 1.5 4.8 3.7 6.2L4 19l3.4-1.5c1.2.4 2.4.5 3.6.5h.5a6.6 6.6 0 0 1-.5-2.5c0-4.1 4-7.5 9-7.5h.6C19.6 4.6 15.7 2 11 2Zm-3.5 6a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4Zm7 0a1.2 1.2 0 1 1 0-2.4 1.2 1.2 0 0 1 0 2.4ZM27 15.5c0-3.6-3.5-6.5-7.5-6.5S12 11.9 12 15.5s3.5 6.5 7.5 6.5a9 9 0 0 0 2.8-.4L25 23l-.5-2.3c1.6-1.2 2.5-3 2.5-5.2Zm-10-1a1 1 0 1 1 0-2 1 1 0 0 1 0 2Zm5 0a1 1 0 1 1 0-2 1 1 0 0 1 0 2Z"/></svg><span>{{ t('使用微信继续') }}</span><small>{{ t('待开放') }}</small></button>
        </div>

        <div class="divider"><span>{{ t('或使用邮箱 / 手机号登录') }}</span></div>
        <div class="method-switch" role="group" :aria-label="t('登录方式')">
          <button type="button" :aria-pressed="method === 'email'" :disabled="locked" @click="changeMethod('email')">{{ t('邮箱登录') }}</button>
          <button type="button" :aria-pressed="method === 'phone'" :disabled="locked" @click="changeMethod('phone')">{{ t('手机号登录') }}</button>
        </div>
        <p v-if="method === 'email' && emailMode !== 'sign-in' && emailMode !== 'code'" class="mode-caption">{{ t(emailMode === 'register' ? '验证邮箱，创建你的音乐账号' : '验证邮箱，设置新的登录密码') }}</p>
        <form ref="form" class="login-form" novalidate :aria-busy="busy" @submit.prevent="submit">
          <template v-if="method === 'email'">
            <label class="field-heading" for="login-email">{{ t('电子邮箱') }}</label>
            <div class="pill-input"><input id="login-email" v-model="email" type="email" inputmode="email" autocomplete="email" maxlength="254" :disabled="locked" :aria-invalid="fieldError === 'identifier'" aria-describedby="login-feedback" :placeholder="t('请输入邮箱地址')" @input="identifierEdited"></div>
          </template>
          <template v-else>
            <label class="field-heading" for="login-phone">{{ t('手机号') }}</label>
            <div class="pill-input phone-input"><div class="country-selector"><select v-model="country" :disabled="locked" :aria-label="t('国家或地区及区号')" @change="identifierEdited"><option v-for="item in countries" :key="item.country" :value="item.country">{{ item.name }} {{ item.code }}</option></select><span aria-hidden="true">{{ country }} {{ selectedCallingCode }} <svg viewBox="0 0 12 12" fill="none"><path d="m3 4.5 3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg></span></div><input id="login-phone" v-model="phone" type="tel" inputmode="tel" autocomplete="tel-national" maxlength="32" :disabled="locked" :aria-invalid="fieldError === 'identifier'" aria-describedby="login-feedback" :placeholder="t('请输入手机号')" @input="identifierEdited"></div>
          </template>

          <template v-if="needsCode">
            <label class="field-heading" for="login-code">{{ t('验证码') }}</label>
            <div class="pill-input code-input"><input id="login-code" v-model="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" :disabled="locked" :aria-invalid="fieldError === 'code'" aria-describedby="login-feedback" :placeholder="t('请输入6位验证码')" @input="clearError"><button type="button" class="send-code" :disabled="locked || remaining > 0 || !channelReady || statusLoading" :aria-busy="sending" @click="sendCode">{{ sending ? t('发送中…') : remaining > 0 ? t('{seconds}秒后重发', {seconds: remaining}) : t('发送验证码') }}</button></div>
          </template>

          <template v-if="needsPassword">
            <div class="field-heading password-heading"><label for="login-password">{{ t(emailMode === 'sign-in' ? '密码' : '设置密码') }}</label><button v-if="emailMode === 'sign-in' && nativeStatus?.passwordResetEnabled" type="button" :disabled="locked" @click="changeMode('reset')">{{ t('忘记密码？') }}</button></div>
            <div class="pill-input"><input id="login-password" v-model="password" :type="showPassword ? 'text' : 'password'" :autocomplete="emailMode === 'sign-in' ? 'current-password' : 'new-password'" maxlength="128" :disabled="locked" :aria-invalid="fieldError === 'password'" :aria-describedby="emailMode === 'sign-in' ? 'login-feedback' : 'password-hint login-feedback'" :placeholder="t(emailMode === 'sign-in' ? '请输入密码' : '至少8位，包含字母和数字')" @input="clearError"><button class="password-visibility" type="button" :aria-label="t(showPassword ? '隐藏密码' : '显示密码')" :aria-pressed="showPassword" @click="showPassword = !showPassword"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" stroke="currentColor" stroke-width="1.6"/><circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.6"/><path v-if="!showPassword" d="m4 3 16 18" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button></div>
            <p v-if="emailMode !== 'sign-in'" id="password-hint" class="password-hint">{{ t('使用8至128位字符，包含字母和数字。') }}</p>
          </template>
          <div id="login-feedback" class="form-feedback" :class="{'has-error':error}" :role="error ? 'alert' : 'status'" aria-live="polite"><p v-if="error">{{ t(error) }}</p><p v-else-if="statusLoading">{{ t('正在检查登录服务…') }}</p><p v-else-if="phoneRegionUnavailable">{{ t('该地区短信暂未开通，请使用邮箱登录。') }}</p><p v-else-if="!channelReady">{{ t('当前登录方式暂不可用，请稍后重试。') }}</p><p v-else-if="notice">{{ t(notice) }}</p><p v-else-if="method === 'phone' || emailUsesCode">{{ t('未注册的账号验证后将自动创建。') }}</p></div>
          <button class="continue-button" type="submit" :disabled="locked || statusLoading || !channelReady">{{ t(busy ? '正在处理…' : submitLabel) }}</button>
        </form>
        <div v-if="method === 'email'" class="register-option"><template v-if="emailMode === 'sign-in' && passwordAvailable"><span>{{ t('还没有账号？') }}</span><button type="button" :disabled="locked" @click="changeMode('register')">{{ t('立即注册') }} <span aria-hidden="true">→</span></button></template><template v-else-if="passwordAvailable"><span>{{ t('已有账号？') }}</span><button type="button" :disabled="locked" @click="changeMode('sign-in')">{{ t('返回登录') }} <span aria-hidden="true">→</span></button></template></div>
        <button v-if="method === 'email' && passwordAvailable && emailMode === 'sign-in' && nativeStatus?.emailCodeEnabled" type="button" class="alternate-mode" :disabled="locked" @click="changeMode('code')">{{ t('使用验证码登录') }}</button>
        <button v-if="statusFailed" type="button" class="retry-status" :disabled="statusLoading || locked" @click="loadStatus()">{{ t('重新检查登录服务') }}</button>
        <p class="legal-note"><RouterLink to="/legal/terms" target="_blank" rel="noopener">用户服务条款（含积分与退款规则）</RouterLink> · <RouterLink to="/legal/privacy" target="_blank" rel="noopener">隐私政策</RouterLink></p>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.login-modal { width:min(580px,calc(100% - 32px)); max-width:none; max-height:calc(100svh - 32px); margin:auto; padding:0; overflow-y:auto; overscroll-behavior:contain; border:1px solid #f3e8e8; border-radius:24px; background:#fffdfd; color:#282525; box-shadow:0 28px 110px #21171745; scrollbar-width:thin; }
.login-modal::backdrop { background:#25222a75; backdrop-filter:blur(4px); }
.login-card { position:relative; overflow:hidden; padding:24px 38px 25px; background:linear-gradient(125deg,#fffafa 0%,#fff 48%,#fff8fa 100%); }
.login-card > :not(.corner-wave) { position:relative; z-index:1; }.corner-wave { position:absolute; pointer-events:none; width:220px; }.wave-top { right:-5px; top:24px; }.wave-bottom { bottom:0; left:0; width:260px; }
.login-brand { display:flex; align-items:center; gap:10px; width:fit-content; margin-left:-9px; }.login-brand img { display:block; width:35px; height:35px; }.login-brand strong { color:#171616; font-size:19px; letter-spacing:-.04em; font-weight:750; }
.login-card > .close-button { position:absolute; top:24px; right:25px; display:grid; place-items:center; width:32px; height:32px; padding:6px; border:0; border-radius:50%; background:transparent; color:#807b78; }.close-button:hover { background:#f7edeb; color:#3b3330; }.close-button svg { width:23px; height:23px; }
.login-content { margin-top:25px; text-align:center; }.login-content h2 { margin:0; color:#171616; font-size:clamp(22px,4vw,29px); font-weight:760; letter-spacing:-.055em; line-height:1.4; }.login-description { margin:8px 0 18px; color:#868181; font-size:14px; line-height:1.6; }
.login-bonus { display:flex; align-items:center; justify-content:center; gap:9px; margin:0 -12px 19px; padding:10px 12px; border-radius:15px; background:#ffeceb; color:#dc4c35; font-size:12px; line-height:1.6; font-weight:650; }.login-bonus svg { width:20px; height:20px; flex:none; }
.social-options { display:grid; gap:10px; }.social-button { position:relative; display:flex; align-items:center; width:100%; min-height:44px; padding:10px 17px; border:1px solid #dcdadb; border-radius:17px; background:#ffffff91; color:#262323; box-shadow:inset 0 0 0 1px #f4f1f1; }.social-button:not(:disabled):hover { border-color:#eea597; background:#fff5f1; }.social-button svg { flex:none; width:23px; height:23px; }.social-button span { flex:1; padding:0 35px 0 12px; font-size:14px; font-weight:650; line-height:1.5; }.social-button small { position:absolute; right:13px; color:#8e8783; font-size:10px; }.social-button:disabled { background:#f6f5f5; color:#928d8b; cursor:not-allowed; }.social-button.coming-soon svg { opacity:.6; }
.divider { display:flex; align-items:center; gap:13px; margin:21px 0 16px; color:#999393; font-size:12px; line-height:1.5; }.divider::before,.divider::after { content:''; height:1px; flex:1; background:#ede9e9; }.divider span { flex:none; }
.method-switch { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:3px; margin-bottom:17px; padding:3px; border-radius:99px; background:#f1eff0; }.method-switch button { min-height:36px; padding:7px 10px; border:1px solid transparent; border-radius:99px; background:transparent; color:#7d7777; font-size:14px; font-weight:650; line-height:1.4; }.method-switch button[aria-pressed=true] { border-color:#ffe0dc; background:#fff7f5; color:#db553b; box-shadow:0 2px 8px #b7603c13; }
.mode-caption { margin:-3px 0 14px; font-size:13px; color:#7a6b65; }.login-form { text-align:left; }.field-heading { display:block; margin:14px 0 7px; color:#4b4542; font-size:12px; font-weight:650; line-height:1.5; }.login-form > .field-heading:first-child { margin-top:0; }.password-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; }.password-heading button { padding:0; color:#e45a42; font-size:12px; font-weight:650; }
.pill-input { display:flex; align-items:center; min-width:0; min-height:43px; overflow:hidden; border:1px solid #dcdadb; border-radius:18px; background:#ffffff91; box-shadow:inset 0 0 0 1px #f5f3f3; }.pill-input:focus-within { border-color:#e7a191; box-shadow:0 0 0 3px #fb7b641a; }.pill-input:has(input[aria-invalid=true]) { border-color:#d9503b; }.pill-input input { width:100%; min-width:0; padding:11px 15px; border:0; outline:0; background:transparent; color:#38312e; font:inherit; font-size:13px; opacity:1; }.pill-input input::placeholder { color:#a8a2a0; opacity:1; }.pill-input input:disabled { color:#8d827d; }
.password-visibility { display:grid; place-items:center; flex:none; width:40px; height:40px; padding:10px; margin-right:4px; color:#aaa39f; }.password-visibility svg { width:21px; height:21px; }.password-hint { margin:6px 2px 0; color:#a09189; font-size:10px; line-height:1.6; }
.country-selector { position:relative; flex:none; align-self:stretch; min-width:105px; border-right:1px solid #ece4e0; }.country-selector select { position:absolute; inset:0; z-index:1; width:100%; cursor:pointer; opacity:0; font-size:16px; }.country-selector span { display:flex; align-items:center; justify-content:center; gap:5px; height:100%; padding:0 10px; color:#72655f; font-size:12px; }.country-selector svg { width:12px; height:12px; }.country-selector:focus-within { outline:2px solid #e68e78; outline-offset:-3px; border-radius:16px 0 0 16px; }
.send-code { flex:none; min-width:94px; max-width:48%; margin-right:5px; padding:8px 10px; border-radius:13px; color:#de593d; font-size:12px; font-weight:650; line-height:1.5; }.send-code:not(:disabled):hover { background:#fff0eb; }.send-code:disabled { color:#aaa09a; cursor:not-allowed; }
.form-feedback { min-height:15px; margin:7px 2px 0; color:#8f857e; font-size:10px; line-height:1.5; }.form-feedback p { margin:0; }.form-feedback.has-error { color:#bb4131; }.continue-button { width:100%; min-height:46px; margin-top:9px; padding:10px 18px; border:0; border-radius:20px; background:linear-gradient(100deg,#ff5235,#ff432d); color:#fff; font-size:16px; font-weight:650; line-height:1.5; box-shadow:0 5px 12px #ff573015; }.continue-button:not(:disabled):hover { background:#eb432d; }.continue-button:disabled { background:#ee9b8c; cursor:not-allowed; box-shadow:none; }
.register-option { display:flex; justify-content:center; align-items:center; gap:7px; margin-top:16px; color:#8b817b; font-size:13px; line-height:1.5; }.register-option:empty { display:none; }.register-option button { padding:0; color:#e65639; font-weight:650; }.register-option button span { padding-left:5px; }.register-option button:disabled { opacity:.55; }.alternate-mode,.retry-status { margin-top:12px; padding:0; color:#c8513c; font-size:11px; text-decoration:underline; text-underline-offset:3px; }.legal-note { margin:19px 0 0; color:#aaa09a; font-size:10px; line-height:1.7; }
.login-modal button:focus-visible { outline:3px solid #f0ae99; outline-offset:3px; }.login-modal button:disabled { -webkit-tap-highlight-color:transparent; }
@media(max-width:620px) { .login-modal { width:calc(100% - 24px); max-height:calc(100svh - 24px); border-radius:21px; }.login-card { padding:21px 25px 21px; }.login-brand { margin-left:-5px; gap:8px; }.login-brand strong { font-size:18px; }.login-brand img { width:31px; height:31px; }.login-card > .close-button { top:20px; right:17px; }.login-content { margin-top:22px; }.login-content h2 { font-size:23px; }.login-description { font-size:12px; }.login-bonus { margin-inline:-5px; padding:9px 9px; font-size:11px; gap:7px; }.login-bonus svg { width:18px; height:18px; }.social-button { padding-inline:13px; }.social-button span { font-size:13px; }.pill-input input { font-size:16px; padding:11px 13px; }.field-heading { font-size:12px; }.continue-button { min-height:46px; }.wave-top { width:160px; }.wave-bottom { width:220px; } }
@media(max-width:360px) { .login-card { padding-inline:19px; }.login-content h2 { font-size:20px; }.login-bonus { align-items:flex-start; font-size:10px; }.social-button span { padding-left:6px; padding-right:34px; font-size:12px; }.social-button small { font-size:9px; right:9px; }.country-selector { min-width:92px; }.send-code { min-width:88px; padding-inline:7px; font-size:11px; }.divider { font-size:11px; gap:9px; }.method-switch button { font-size:13px; } }
@media(prefers-reduced-motion:reduce) { .login-modal { scroll-behavior:auto; } }
</style>
