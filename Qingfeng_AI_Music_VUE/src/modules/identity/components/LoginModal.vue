<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { t } from '../../../shared/i18n'
import { hideLoginDialog, loginDialogOpen } from '../../../shared/identity/login-dialog'
import { accountState, refreshAccountState } from '../../../shared/identity/account-state'
import { logtoState, refreshLogtoState } from '../../../shared/identity/logto-state'
import { beginGoogleLogin } from '../api/google-login'
import { beginLogtoLogin } from '../api/logto-login'
import { validateLoginIdentifier } from '../model/login-identifier'
import GoogleMark from './GoogleMark.vue'

type Method = 'email' | 'phone'
const route = useRoute()
const dialog = ref<HTMLDialogElement>()
const closeButton = ref<HTMLButtonElement>()
const method = ref<Method>('email')
const email = ref('')
const phone = ref('')
const channelNotice = ref('')
const inputError = ref(false)
const checkingLogto = ref(false)
const logtoReady = ref(false)
const openingLogto = ref(false)
const logtoError = ref('')
const openingApple = ref(false)
const appleError = ref('')
const checkingGoogle = ref(false)
const googleReady = ref(false)
const googleStatusError = ref(false)
const openingGoogle = ref(false)
const googleError = ref('')
let returnFocus: HTMLElement | null = null
let statusEpoch = 0
const channelReady = computed(() => logtoReady.value && (method.value === 'email' ? logtoState.value.emailEnabled : logtoState.value.phoneEnabled) === true)
const appleReady = computed(() => logtoReady.value && logtoState.value.appleEnabled === true)
const channelStatus = computed(() => {
  if (channelNotice.value) return channelNotice.value
  if (checkingLogto.value) return '正在检查登录服务…'
  if (!logtoReady.value) return 'Logto 登录暂不可用，请稍后重试。'
  if (!channelReady.value) return method.value === 'email' ? '当前邮箱验证码登录尚未启用。' : '当前手机验证码登录尚未启用。'
  return '下一步将在 Logto 登录页完成验证码验证。'
})

watch(loginDialogOpen, async isOpen => {
  if (!isOpen) { if (dialog.value?.open) dialog.value.close(); return }
  returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  method.value = 'email'
  email.value = ''; phone.value = ''
  channelNotice.value = ''
  inputError.value = false
  logtoError.value = ''
  openingLogto.value = false
  openingApple.value = false
  appleError.value = ''
  checkingLogto.value = true
  logtoReady.value = false
  googleError.value = ''
  openingGoogle.value = false
  checkingGoogle.value = true
  googleReady.value = false
  googleStatusError.value = false
  const epoch = ++statusEpoch
  await nextTick()
  if (!loginDialogOpen.value || !dialog.value) return
  dialog.value.showModal()
  closeButton.value?.focus()
  await Promise.all([
    refreshLogtoState().then(status => {
      if (epoch === statusEpoch && loginDialogOpen.value) logtoReady.value = status.enabled
    }).catch(() => { if (epoch === statusEpoch) logtoError.value = '暂时无法确认 Logto 登录服务，请稍后重试。' })
      .finally(() => { if (epoch === statusEpoch) checkingLogto.value = false }),
    refreshAccountState().then(status => {
      if (epoch === statusEpoch && loginDialogOpen.value) googleReady.value = status.enabled
    }).catch(() => { if (epoch === statusEpoch) googleStatusError.value = true })
      .finally(() => { if (epoch === statusEpoch) checkingGoogle.value = false }),
  ])
})
watch(() => route.fullPath, () => { if (loginDialogOpen.value) hideLoginDialog() })

function close() { hideLoginDialog() }
function onClosed() {
  statusEpoch++
  email.value = ''; phone.value = ''
  channelNotice.value = ''
  inputError.value = false
  openingLogto.value = false
  openingApple.value = false
  openingGoogle.value = false
  hideLoginDialog()
  if (returnFocus?.isConnected) returnFocus.focus()
  returnFocus = null
}
function onBackdropClick(event: MouseEvent) { if (event.target === dialog.value) close() }
function chooseMethod(next: Method) { method.value = next; channelNotice.value = ''; inputError.value = false }
async function continueWithIdentifier() {
  if (openingLogto.value || openingGoogle.value) return
  const result = validateLoginIdentifier(method.value, method.value === 'email' ? email.value : phone.value)
  if (!result.valid) { inputError.value = true; channelNotice.value = result.message; return }
  inputError.value = false
  if (checkingLogto.value) return
  if (!logtoReady.value) { channelNotice.value = 'Logto 登录暂不可用，请稍后重试。'; return }
  if (!channelReady.value) {
    channelNotice.value = method.value === 'email' ? '当前邮箱验证码登录尚未启用。' : '当前手机验证码登录尚未启用。'
    return
  }
  openingLogto.value = true
  channelNotice.value = ''
  logtoError.value = ''
  const epoch = statusEpoch
  try {
    const url = await beginLogtoLogin({ method: method.value, loginHint: result.value })
    if (epoch === statusEpoch && loginDialogOpen.value) window.location.assign(url)
  }
  catch { if (epoch === statusEpoch) { channelNotice.value = '暂时无法开始验证码登录，请稍后重试。'; openingLogto.value = false } }
}
async function continueWithLogto() {
  if (openingLogto.value || openingGoogle.value || checkingLogto.value || !logtoReady.value) return
  openingLogto.value = true
  logtoError.value = ''
  const epoch = statusEpoch
  try {
    const url = await beginLogtoLogin()
    if (epoch === statusEpoch && loginDialogOpen.value) window.location.assign(url)
  }
  catch { if (epoch === statusEpoch) { logtoError.value = '暂时无法开始 Logto 登录，请稍后重试。'; openingLogto.value = false } }
}
async function continueWithGoogle() {
  if (openingGoogle.value || openingLogto.value || checkingGoogle.value || !googleReady.value || !accountState.value.enabled) return
  openingGoogle.value = true
  googleError.value = ''
  const epoch = statusEpoch
  try {
    const url = await beginGoogleLogin()
    if (epoch === statusEpoch && loginDialogOpen.value) window.location.assign(url)
  }
  catch { if (epoch === statusEpoch) { googleError.value = '暂时无法开始 Google 登录，请稍后重试。'; openingGoogle.value = false } }
}
async function continueWithApple() {
  if (openingLogto.value || openingGoogle.value || checkingLogto.value || !appleReady.value) return
  openingLogto.value = true
  openingApple.value = true
  appleError.value = ''
  const epoch = statusEpoch
  try {
    const url = await beginLogtoLogin({ method: 'apple' })
    if (epoch === statusEpoch && loginDialogOpen.value) window.location.assign(url)
  }
  catch { if (epoch === statusEpoch) { appleError.value = '暂时无法开始 Apple 登录，请稍后重试。'; openingLogto.value = false; openingApple.value = false } }
}
</script>

<template>
  <dialog ref="dialog" class="login-modal" aria-modal="true" aria-labelledby="login-title" aria-describedby="login-description login-bonus" @click="onBackdropClick" @close="onClosed">
    <div class="login-card">
      <button ref="closeButton" class="close-button" type="button" :aria-label="t('关闭登录弹窗')" @click="close"><svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg></button>
      <h2 id="login-title">{{ t('欢迎来到 Cheerful Music') }}</h2>
      <p id="login-description" class="login-description">{{ t('登录账号，开启你的音乐创作之旅') }}</p>
      <p id="login-bonus" class="login-bonus">{{ t('新用户首次登录赠送100积分，每个账号限领一次。') }}</p>

      <div class="social-options" :aria-label="t('社交账号登录')">
        <button class="social-button" type="button" :disabled="checkingGoogle || !googleReady || !accountState.enabled || openingGoogle || openingLogto" :aria-busy="openingGoogle" @click="continueWithGoogle"><GoogleMark /><span>{{ openingGoogle ? t('正在前往 Google…') : t('使用 Google 继续') }}</span><small v-if="checkingGoogle">{{ t('检查中…') }}</small><small v-else-if="googleStatusError">{{ t('状态未确认') }}</small><small v-else-if="!googleReady">{{ t('当前环境未启用') }}</small></button>
        <button class="social-button" type="button" :disabled="checkingLogto || !appleReady || openingLogto || openingGoogle" :aria-busy="openingApple" @click="continueWithApple"><svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M18.7 19.5c-.8 1.2-1.7 2.5-3.1 2.5-1.3 0-1.8-.8-3.3-.8s-2 .8-3.3.8c-1.3.1-2.3-1.3-3.2-2.6-1.7-2.5-3-7-.3-10.1a4.9 4.9 0 0 1 4.1-2.5c1.3 0 2.5.9 3.3.9.8 0 2.3-1.1 3.9-.9a4.8 4.8 0 0 1 3.7 2c-.1.1-2.2 1.3-2.2 3.8 0 3.1 2.7 4.1 2.7 4.1 0 .1-.4 1.5-1.4 3ZM14 5.1A5.4 5.4 0 0 0 15.3 1a5.5 5.5 0 0 0-3.6 1.9 5.2 5.2 0 0 0-1.3 4c1.4.1 2.8-.4 3.6-1.8Z"/></svg><span>{{ openingApple ? t('正在前往 Apple…') : t('使用 Apple 继续') }}</span><small v-if="checkingLogto">{{ t('检查中…') }}</small><small v-else-if="!appleReady">{{ t('当前环境未启用') }}</small></button>
      </div>
      <p v-if="googleError" class="login-error" role="alert">{{ t(googleError) }}</p>
      <p v-if="appleError" class="login-error" role="alert">{{ t(appleError) }}</p>

      <div class="divider"><span>{{ t(method === 'email' ? '或使用邮箱' : '或使用手机') }}</span></div>
      <div class="method-switch" role="group" :aria-label="t('验证码方式')">
        <button type="button" :aria-pressed="method === 'email'" @click="chooseMethod('email')">{{ t('邮箱') }}</button>
        <button type="button" :aria-pressed="method === 'phone'" @click="chooseMethod('phone')">{{ t('手机') }}</button>
      </div>
      <div class="field-heading"><label :for="method === 'email' ? 'login-email' : 'login-phone'">{{ method === 'email' ? t('电子邮箱') : t('手机号') }}</label><span :class="{ 'is-ready': channelReady }">{{ t(checkingLogto ? '检查中…' : channelReady ? '验证码登录可用' : '验证码登录未启用') }}</span></div>
      <div v-if="method === 'email'" class="pill-input"><input id="login-email" v-model="email" type="email" inputmode="email" autocomplete="email" :aria-invalid="inputError" aria-describedby="login-channel-status" :placeholder="t('请输入邮箱地址')" @input="inputError=false; channelNotice=''" @keydown.enter.prevent="continueWithIdentifier"></div>
      <div v-else class="pill-input"><span class="country-code">+86</span><input id="login-phone" v-model="phone" type="tel" inputmode="tel" autocomplete="tel-national" :aria-invalid="inputError" aria-describedby="login-channel-status" :placeholder="t('请输入手机号')" @input="inputError=false; channelNotice=''" @keydown.enter.prevent="continueWithIdentifier"></div>
      <button class="continue-button" type="button" :disabled="openingLogto" :aria-busy="openingLogto" aria-describedby="login-channel-status" @click="continueWithIdentifier">{{ openingLogto ? t('正在前往登录…') : method === 'email' ? t('使用邮箱继续') : t('使用手机继续') }}</button>
      <p id="login-channel-status" class="channel-status" :class="{ 'is-error': inputError }" role="status">{{ t(channelStatus) }}</p>

      <div class="logto-option"><button type="button" :disabled="checkingLogto || !logtoReady || openingLogto" @click="continueWithLogto">{{ openingLogto ? t('正在前往登录…') : t('继续使用 Logto') }} <span aria-hidden="true">↗</span></button><p v-if="checkingLogto">{{ t('正在检查登录服务…') }}</p><p v-else-if="!logtoReady">{{ t('Logto 登录暂不可用，请稍后重试。') }}</p><p v-else>{{ t('将前往 Logto 登录页，使用当前已配置的方式。') }}</p></div>
      <p v-if="logtoError" class="login-error" role="alert">{{ t(logtoError) }}</p>
      <p class="legal-note">{{ t('服务协议与隐私政策将在正式开放前提供。') }}</p>
    </div>
  </dialog>
</template>

<style scoped>
.login-modal { width: min(490px, calc(100% - 28px)); max-width: none; max-height: min(94svh, 900px); margin: auto; padding: 0; overflow-y: auto; border: 1px solid #f0e5e1; border-radius: 27px; background: #fff; color: #272422; box-shadow: 0 32px 100px #21171747; }
.login-modal::backdrop { background: #191b21a8; backdrop-filter: blur(5px); }
.login-card { position: relative; padding: 33px 38px 18px; text-align: center; }
.close-button { position: absolute; top: 16px; right: 16px; display: grid; place-items: center; width: 34px; height: 34px; padding: 8px; border: 0; border-radius: 50%; background: #f8f6f4; color: #847c76; }
.close-button:hover { background: #f1e9e5; color: #332a26; }
.close-button svg { width: 18px; height: 18px; }
.login-card h2 { margin: 0; color: #272320; font-size: clamp(24px, 5vw, 29px); font-weight: 750; letter-spacing: -.045em; line-height: 1.35; }
.login-description { margin: 7px 0 16px; color: #938983; font-size: 13px; line-height: 1.55; }
.login-bonus { margin: 0 0 16px; padding: 9px 12px; border-radius: 12px; background: #fff2ed; color: #af523c; font-size: 12px; line-height: 1.6; }
.social-options { display: grid; gap: 8px; }
.social-button { display: flex; align-items: center; width: 100%; min-height: 52px; padding: 10px 17px; border: 1px solid #e8e3e0; border-radius: 14px; background: #fff; color: #423d39; text-align: left; opacity: 1; }
.social-button:not(:disabled):hover { border-color: #e9b5a6; background: #fffaf8; }
.social-button:disabled { background: #faf9f8; color: #877e79; }
.social-button svg { width: 20px; height: 20px; flex: none; }
.social-button span { flex: 1; padding-left: 13px; font-size: 14px; font-weight: 650; }
.social-button small { color: #af9d96; font-size: 11px; }
.divider { position: relative; margin: 14px 0 11px; color: #a59a94; font-size: 12px; }
.divider::before { position: absolute; top: 50%; left: 0; right: 0; height: 1px; background: #eee9e6; content: ''; }
.divider span { position: relative; padding: 0 13px; background: #fff; }
.method-switch { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; margin-bottom: 13px; padding: 4px; border-radius: 99px; background: #f7f5f3; }
.method-switch button { min-height: 36px; border: 0; border-radius: 99px; background: transparent; color: #8d817b; font-size: 13px; font-weight: 650; }
.method-switch button[aria-pressed=true] { background: #fff; color: #e64b37; box-shadow: 0 2px 10px #42241a12; }
.field-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 8px; text-align: left; }
.field-heading label { color: #524842; font-size: 13px; font-weight: 650; }
.field-heading span { padding: 3px 7px; border: 1px solid #f2d5ca; border-radius: 99px; background: #fff2ed; color: #af523c; font-size: 11px; font-weight: 650; }
.field-heading span.is-ready { border-color: #b7dec9; background: #effaf3; color: #217b49; }
.pill-input { display: flex; align-items: center; min-width: 0; min-height: 50px; overflow: hidden; border: 1px solid #e6e1dd; border-radius: 99px; background: #faf9f8; }
.pill-input:focus-within { border-color: #e26b56; background: #fff; box-shadow: 0 0 0 3px #ff432d1c; }
.pill-input:has(input[aria-invalid=true]) { border-color: #d86655; }
.pill-input input { width: 100%; min-width: 0; padding: 12px 17px; border: 0; outline: 0; background: transparent; color: #514a46; font-size: 14px; opacity: 1; }
.pill-input input::placeholder { color: #aaa19c; opacity: 1; }
.country-code { flex: none; padding: 0 14px 0 17px; border-right: 1px solid #e7ded9; color: #6f635d; font-size: 12px; }
.continue-button { width: 100%; min-height: 50px; margin-top: 14px; border: 0; border-radius: 99px; background: #f34c36; color: #fff; font-size: 14px; font-weight: 700; }
.continue-button:hover { background: #df3e2c; }
.continue-button:disabled { cursor: wait; opacity: .7; }
.channel-status { min-height: 18px; margin: 9px 0 0; color: #ab796b; font-size: 10px; line-height: 1.55; }
.channel-status.is-error { color: #b63c2e; }
.logto-option { margin-top: 13px; }
.logto-option button { padding: 3px 8px; color: #c74735; font-size: 11px; font-weight: 700; text-decoration: underline; text-underline-offset: 3px; }
.logto-option button:not(:disabled):hover { color: #a83426; }
.logto-option button:disabled { opacity: .5; }
.logto-option p { margin: 2px 0 0; color: #9c8175; font-size: 10px; line-height: 1.5; }
.login-error { margin: 11px 0 0; color: #b63c2e; font-size: 11px; line-height: 1.5; }
.legal-note { margin: 11px 0 0; color: #aaa19d; font-size: 10px; line-height: 1.6; }
.login-modal button:focus-visible { outline: 3px solid #f1aa96; outline-offset: 3px; }
@media (max-width: 520px) { .login-modal { width: min(100% - 20px, 490px); max-height: calc(100svh - 20px); border-radius: 21px; } .login-card { padding: 34px 22px 20px; } .close-button { top: 12px; right: 12px; } }
@media (max-width: 350px) { .login-card { padding-inline: 17px; } }
</style>
