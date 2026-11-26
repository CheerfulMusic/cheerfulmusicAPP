<script setup lang="ts">
import { t } from '../../../shared/i18n/index'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { platform } from '../../../platform'
import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import { usePlayer } from '../../../shared/player/store'
import { beginGoogleLogin } from '../api/google-login'
import { accountLoggingOut, accountState, logoutAccount, refreshAccountState } from '../../../shared/identity/account-state'
import { logtoState, refreshLogtoState } from '../../../shared/identity/logto-state'
import { maskedPhoneLabel } from '../../../shared/identity/phone-label'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { googleLoginFeedback } from '../model/google-login'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import GoogleMark from '../components/GoogleMark.vue'
import CreditsPanel from '../components/CreditsPanel.vue'
const props = defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ close: []; works: [] }>()
const router = useRouter(), player = usePlayer(), busy = ref(true), error = ref('')
const route = useRoute()
const googleStatus = accountState
const googleBusy = ref(false), googleError = ref(''), googleNotice = ref(''), googleNoticeKind = ref<'success' | 'info'>('info')
const logtoError = ref(''), logtoNotice = ref('')
const inviteLink = ref(''), inviteNotice = ref('')
const accountBusy = computed(() => busy.value || googleBusy.value || accountLoggingOut.value)
let pageReady = false
const accountInitials = computed(() => {
  const name = googleStatus.value.name?.trim()
  if (name) return name.split(/\s+/).slice(0, 2).map(part => Array.from(part)[0]).join('').toLocaleUpperCase()
  return Array.from(googleStatus.value.email || '♪')[0]?.toLocaleUpperCase()
})
watch(accountState, (current, previous) => {
  if (current.authenticated === previous.authenticated && current.email === previous.email && current.name === previous.name) return
  reloadForAccountChange()
})
watch(logtoState, (current, previous) => {
  if (current.authenticated === previous.authenticated && current.email === previous.email && current.phoneMasked === previous.phoneMasked && current.name === previous.name) return
  reloadForAccountChange()
})
function reloadForAccountChange() {
  if (!pageReady || busy.value || googleBusy.value || accountLoggingOut.value) return
  busy.value = true
  inviteLink.value = ''
  window.location.replace(props.embedded ? '/agent' : '/account')
}
onMounted(async () => {
  const result = route.query.google
  const logtoResult = route.query.logto
  try {
    if (platform.kind === 'web') await Promise.all([
      refreshAccountState().catch(() => undefined),
      refreshLogtoState().catch(() => { logtoError.value = '暂时无法读取账号状态，请刷新后重试。' }),
    ])
    await ensureCreator()
    if (typeof route.query.invite === 'string') {
      try { await requestJson('app/referrals/accept', 'POST', {code:route.query.invite}); inviteNotice.value = '邀请已记录，首次登录成功后邀请人将获得 19 积分。' }
      catch (e) { inviteNotice.value = e instanceof Error ? e.message : '邀请链接无效' }
    }
    if (googleStatus.value.authenticated || logtoState.value.authenticated) {
      try { const result = await requestJson<{code:string}>('app/referrals'); inviteLink.value = `${window.location.origin}/account?invite=${encodeURIComponent(result.code)}` } catch { /* 登录状态变化时可刷新重试。 */ }
    }
    const feedback = googleLoginFeedback(result, googleStatus.value.authenticated)
    if (feedback?.kind === 'error') googleError.value = feedback.message
    else if (feedback) { googleNotice.value = feedback.message; googleNoticeKind.value = feedback.kind }
    if (route.query.session === 'signed-out') {
      googleNotice.value = '已退出此设备，你的音乐仍保存在原账号中。'
      googleNoticeKind.value = 'info'
    }
    if (result === 'success' && googleStatus.value.authenticated) player.reset()
    if (logtoResult === 'success' && logtoState.value.authenticated) { logtoNotice.value = '登录成功，你的音乐已关联到当前账号。'; player.reset() }
    else if (logtoResult === 'success') logtoError.value = '登录状态尚未确认，请刷新后重试。'
    else if (logtoResult === 'conflict') logtoError.value = '当前作品属于另一个账号。请使用原账号登录查看。'
    else if (logtoResult === 'failed') logtoError.value = '登录未完成，请重试。'
    else if (logtoResult === 'cancelled') logtoNotice.value = '已取消登录，当前作品仍在此设备。'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '暂时无法读取身份状态'
  } finally {
    pageReady = true; busy.value = false
    if ((typeof result === 'string' && googleLoginFeedback(result, false)) || typeof logtoResult === 'string' || route.query.session === 'signed-out') {
      const query = { ...route.query }; delete query.google; delete query.logto; delete query.session
      await router.replace({ query })
    }
  }
})
async function loginWithGoogle() {
  if (accountBusy.value || !googleStatus.value.enabled || googleStatus.value.authenticated) return
  googleBusy.value = true; googleError.value = ''; googleNotice.value = ''
  try { window.location.assign(await beginGoogleLogin()) }
  catch { googleError.value = '暂时无法开始 Google 登录，请稍后重试。'; googleBusy.value = false }
}
async function manageGoogleSession(action: 'switch' | 'logout') {
  if (googleBusy.value || accountLoggingOut.value) return
  if (!googleStatus.value.authenticated && (action === 'switch' || !logtoState.value.authenticated)) return
  googleBusy.value = true; googleError.value = ''; googleNotice.value = ''
  try {
    await logoutAccount()
    if (action === 'switch') window.location.assign(await beginGoogleLogin())
    else window.location.replace(props.embedded ? '/agent' : '/account?session=signed-out')
  } catch {
    googleError.value = action === 'switch' ? '暂时无法切换账号，请稍后重试。' : '暂时无法退出登录，请稍后重试。'
    googleBusy.value = false
  }
}
async function copyInvite() { try { await navigator.clipboard.writeText(inviteLink.value); inviteNotice.value = '邀请链接已复制' } catch { inviteNotice.value = '请手动复制邀请链接' } }
</script>
<template>
  <div class="account-page">
    <a class="button secondary" href="#payment-orders">{{ t('查看我的订单 / 待处理付款') }}</a>

  <header class="account-intro">
    <p class="account-eyebrow"><span></span>{{ t('我的账号') }}</p>
    <h1>{{ t('账号与登录') }}</h1>
    <p>{{ t('登录音乐账号，在不同设备上访问你的作品。') }}</p>
  </header>
  <RouterLink v-if="!props.embedded && (logtoState.authenticated || googleStatus.authenticated)" class="button secondary" to="/profile">{{ t('我的资料') }}</RouterLink>
  <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
  <p v-if="googleError" class="notice error" role="alert">{{ t(googleError) }}</p>
  <p v-if="googleNotice" class="notice" :class="googleNoticeKind" role="status">{{ t(googleNotice) }}</p>
  <p v-if="logtoError" class="notice error" role="alert">{{ t(logtoError) }}</p>
  <p v-if="logtoNotice" class="notice success" role="status">{{ t(logtoNotice) }}</p>
  <section class="account-profile" :class="{ 'is-connected': logtoState.authenticated }" :aria-busy="accountBusy" :aria-label="t('登录/注册')">
    <div class="profile-main profile-sign-in">
      <div class="profile-identity">
        <div class="google-avatar" aria-hidden="true"><NavIcon name="account" /></div>
        <div class="profile-copy">
          <p class="profile-provider">{{ t('音乐账号') }}</p>
          <h2>{{ logtoState.authenticated ? (logtoState.name || t('已连接账号')) : t('登录/注册') }}</h2>
          <p class="profile-description">{{ logtoState.authenticated ? (logtoState.email || maskedPhoneLabel(logtoState.phoneMasked) || t('可以在其他设备找回你的音乐。')) : t('使用邮箱或手机号登录，保留并同步你的音乐作品。') }}</p>
        </div>
      </div>
      <div class="profile-actions"><button v-if="logtoState.authenticated && !googleStatus.authenticated" type="button" class="session-logout" :disabled="googleBusy || accountLoggingOut" @click="manageGoogleSession('logout')">{{ t(accountLoggingOut ? '正在处理…' : '退出此设备') }}</button><span v-if="logtoState.authenticated" class="connection-badge">{{ t('已连接') }}</span><button type="button" class="google-sign-in" @click="showLoginDialog">{{ t('查看登录方式') }}</button></div>
    </div>
    <div class="profile-footer"><NavIcon name="recovery" /><p>{{ t('不同账号的音乐不会自动合并。') }}</p></div>
  </section>
  <section v-if="googleStatus.enabled" class="account-profile" :class="{ 'is-connected': googleStatus.authenticated }" :aria-busy="accountBusy" :aria-label="t(googleStatus.authenticated ? '已连接 Google 账号' : '用 Google 找回你的音乐')">
    <template v-if="googleStatus.authenticated">
      <div class="profile-main">
        <div class="profile-identity">
          <div class="account-avatar" aria-hidden="true">{{ accountInitials }}<span class="avatar-provider"><GoogleMark /></span></div>
          <div class="profile-copy">
            <p class="profile-provider">{{ t('Google 账号') }}</p>
            <h2>{{ googleStatus.name || t('音乐创作者') }}</h2>
            <p v-if="googleStatus.email" class="profile-email">{{ googleStatus.email }}</p>
          </div>
        </div>
        <div class="profile-actions">
          <span class="connection-badge"><svg viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m4 8 2.5 2.5L12 5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>{{ t('已连接') }}</span>
          <button v-if="props.embedded" type="button" class="profile-library-link" @click="emit('works')">{{ t('进入我的音乐') }}<NavIcon name="arrow" /></button><RouterLink v-else class="profile-library-link" to="/works">{{ t('进入我的音乐') }}<NavIcon name="arrow" /></RouterLink>
        </div>
      </div>
      <div class="profile-footer"><NavIcon name="recovery" /><p>{{ t('换个设备，也能继续你的音乐故事。') }}</p><span>Google</span></div>
      <div class="profile-login-tools">
        <div><strong>{{ t('登录管理') }}</strong><span>{{ t('当前设备') }}</span></div>
        <div class="session-tools-actions">
          <button type="button" :disabled="accountBusy" @click="manageGoogleSession('switch')">{{ googleBusy ? t('正在处理…') : t('切换 Google 账号') }}</button>
          <button type="button" class="session-logout" :disabled="accountBusy" @click="manageGoogleSession('logout')">{{ t('退出此设备') }}</button>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="profile-main profile-sign-in">
        <div class="profile-identity">
          <div class="google-avatar" aria-hidden="true"><GoogleMark /></div>
          <div class="profile-copy"><p class="profile-provider">{{ t('Google 账号') }}</p><h2>{{ t('你的音乐，随账号一起收藏。') }}</h2><p class="profile-description">{{ t('已有 Google 账号可在此找回原有作品。') }}</p></div>
        </div>
        <button type="button" class="google-sign-in" :disabled="accountBusy || !googleStatus.enabled" @click="loginWithGoogle"><GoogleMark />{{ googleBusy ? t('正在前往 Google…') : t('使用 Google 账号登录') }}</button>
      </div>
      <div class="profile-footer"><NavIcon name="recovery" /><p>{{ t('首次登录会保留当前作品。不同账号的音乐收藏不会自动合并。') }}</p></div>
    </template>
  </section>
  <section v-if="inviteNotice || inviteLink" id="invite-rewards" class="account-profile" aria-label="邀请好友">
    <h2>邀请好友</h2>
    <p v-if="inviteNotice">{{ inviteNotice }}</p>
    <template v-if="inviteLink"><p>邀请一位新用户首次登录，你将获得 19 积分。</p><label class="field">邀请链接<input :value="inviteLink" readonly @focus="($event.target as HTMLInputElement).select()"></label><button class="button secondary" type="button" @click="copyInvite">复制邀请链接</button></template>
  </section>
  <CreditsPanel :key="`${googleStatus.authenticated}:${googleStatus.email || ''}:${logtoState.authenticated}:${logtoState.email || ''}:${logtoState.phoneMasked || ''}:${logtoState.name || ''}`" />
  <button v-if="props.embedded" type="button" class="account-back" @click="emit('close')">{{ t('返回对话') }}</button><RouterLink v-else class="account-back" to="/works"><NavIcon name="music" />{{ t('← 返回我的音乐') }}</RouterLink>
  </div>
</template>
<style scoped>
.account-page { width: 100%; max-width: 960px; margin: 8px auto 0; }
.account-intro { margin-bottom: 28px; }
.account-intro .account-eyebrow { display: flex; align-items: center; gap: 9px; margin: 0 0 14px; color: var(--muted); font-size: 12px; font-weight: 500; letter-spacing: .04em; }
.account-eyebrow span { width: 18px; height: 2px; border-radius: 2px; background: var(--accent); }
.account-intro h1 { margin-bottom: 10px; font-size: clamp(25px, 2.6vw, 31px); letter-spacing: -.8px; }
.account-intro > p:last-child { margin: 0; color: var(--muted); font-size: 13px; }
.account-profile { overflow: hidden; border: 1px solid var(--line); border-radius: 20px; background: radial-gradient(ellipse at 0 0, #fff0e9, transparent 65%), var(--surface); box-shadow: var(--brand-shadow); }
.profile-main { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 29px 30px 27px; }
.profile-identity { display: flex; align-items: center; min-width: 0; gap: 20px; }
.account-avatar { position: relative; display: grid; place-items: center; width: 68px; height: 68px; flex: 0 0 auto; border: 1px solid #ffa1838c; border-radius: 50%; background: linear-gradient(145deg, #ff997b, #e35740 65%, #b7352b); box-shadow: 0 0 0 5px #ff6a4210; color: #fff3e9; font-size: 24px; font-weight: 650; letter-spacing: -.6px; }
.avatar-provider { position: absolute; right: -2px; bottom: -2px; display: grid; place-items: center; width: 24px; height: 24px; border: 3px solid var(--surface); border-radius: 50%; background: white; }
.avatar-provider svg { width: 14px; height: 14px; }
.profile-copy { min-width: 0; }
.profile-copy .profile-provider { margin: 0 0 3px; color: var(--muted); font-size: 11px; }
.profile-copy h2 { color:var(--ink); margin: 0; font-size: 23px; line-height: 1.35; letter-spacing: -.5px; font-weight: 650; }
.profile-copy .profile-email { margin: 5px 0 0; color: var(--muted); font-size: 13px; overflow-wrap: anywhere; }
.profile-actions { display: flex; align-items: center; gap: 22px; flex-shrink: 0; }
.connection-badge { display: inline-flex; align-items: center; gap: 5px; color: var(--success); border: 1px solid var(--success-border); border-radius: 24px; background: var(--success-soft); padding: 4px 9px; font-size: 11px; white-space: nowrap; }
.connection-badge svg { width: 14px; height: 14px; }
.profile-library-link, .google-sign-in { display: inline-flex; align-items: center; justify-content: center; gap: 11px; min-height: 44px; padding: 11px 17px; border: 1px solid #dfcbd1; border-radius: 24px; background: #fff; color: var(--ink); font-size: 12px; font-weight: 600; white-space: nowrap; transition: background .15s, transform .15s; }
.profile-library-link:hover, .google-sign-in:hover { background: var(--accent-soft); transform: translateY(-1px); }
.profile-library-link svg { width: 17px; height: 17px; }
.profile-footer { display: flex; align-items: center; gap: 9px; padding: 13px 30px; border-top: 1px solid var(--line); background: #fff8f5; }
.profile-footer > svg { width: 15px; height: 15px; color: var(--success); flex-shrink: 0; }
.profile-footer p { margin: 0; color: var(--muted); font-size: 11px; }
.profile-footer > span { margin-left: auto; color: var(--muted); font-size: 12px; letter-spacing: -.3px; }
.profile-login-tools { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 15px 30px; border-top: 1px solid var(--line); background: var(--surface-raised); }
.profile-login-tools > div:first-child { display: flex; align-items: center; gap: 10px; }
.profile-login-tools strong { color: var(--ink); font-size: 12px; font-weight: 500; }
.profile-login-tools span { color: var(--muted); font-size: 10px; }
.session-tools-actions { display: flex; align-items: center; gap: 9px; }
.session-tools-actions button { padding: 7px 11px; border: 1px solid var(--line); border-radius: 20px; color: var(--ink); font-size: 11px; background: var(--surface); }
.session-tools-actions button:hover { color: var(--accent-text); background: var(--accent-soft); }
.session-tools-actions .session-logout { color: var(--accent-text); background: transparent; border-color: transparent; }
.session-tools-actions button:disabled { opacity: .5; cursor: not-allowed; }
.google-avatar { display: grid; place-items: center; width: 64px; height: 64px; flex-shrink: 0; border: 1px solid var(--line); border-radius: 18px; background: #fff; }
.google-avatar svg { width: 30px; height: 30px; }
.profile-sign-in .profile-copy h2 { font-size: 20px; }
.profile-copy .profile-description { max-width: 360px; margin: 6px 0 0; color: #aeb5c1; font-size: 12px; }
.google-sign-in { flex-shrink: 0; }
.google-sign-in > svg { width: 18px; height: 18px; }
.google-sign-in:disabled { opacity: .6; transform: none; cursor:not-allowed; }
.account-back { display: flex; align-items: center; gap: 8px; width: fit-content; margin: 23px 0 0; color: var(--muted); font-size: 12px; }
.account-back:hover { color: var(--accent-text); }
.account-back svg { width: 16px; height: 16px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 800px) {
  .profile-main { padding: 24px; }
  .profile-actions { gap: 13px; }
  .profile-footer { padding-inline: 24px; }
  .profile-login-tools { padding-inline: 24px; }
  .profile-sign-in { align-items: flex-start; flex-direction: column; }
  .profile-sign-in .google-sign-in { margin-left: 84px; }
}
@media (max-width: 640px) {
  .account-page { margin-top: 0; }
  .account-intro { margin-bottom: 23px; }
  .account-intro .account-eyebrow { margin-bottom: 12px; font-size: 11px; }
  .account-intro h1 { font-size: 25px; line-height: 1.5; }
  .account-intro > p:last-child { font-size: 12px; }
  .account-profile { border-radius: 17px; }
  .profile-main { display: grid; grid-template-columns: minmax(0, 1fr); gap: 22px; padding: 24px 21px 21px; }
  .profile-identity { gap: 16px; }
  .account-avatar { width: 54px; height: 54px; font-size: 21px; }
  .profile-copy h2 { font-size: 21px; }
  .profile-copy .profile-email { font-size: 12px; }
  .profile-actions { width: 100%; justify-content: space-between; }
  .profile-library-link { min-height: 42px; padding-inline: 16px; }
  .profile-footer { padding: 12px 21px; align-items: flex-start; }
  .profile-footer > svg { margin-top: 3px; }
  .profile-footer > span { display: none; }
  .profile-login-tools { padding: 15px 21px; align-items: flex-start; flex-direction: column; gap: 11px; }
  .session-tools-actions { width: 100%; }
  .session-tools-actions button { min-height: 40px; font-size: 12px; }
  .google-avatar { width: 54px; height: 54px; border-radius: 15px; }
  .profile-sign-in .profile-copy h2 { font-size: 18px; }
  .profile-sign-in .google-sign-in { width: 100%; margin-left: 0; }
}
</style>
