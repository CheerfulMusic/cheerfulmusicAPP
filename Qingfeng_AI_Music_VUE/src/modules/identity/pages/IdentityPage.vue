<script setup lang="ts">
import { t } from '../../../shared/i18n/index'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { platform } from '../../../platform'
import { ensureCreator, requestJson, resetCreatorSession } from '../../../shared/http/api-client'
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
const router = useRouter(), player = usePlayer(), busy = ref(true), configured = ref(false), secret = ref(''), restoreCode = ref(''), error = ref(''), notice = ref('')
const route = useRoute()
const googleStatus = accountState
const googleBusy = ref(false), googleError = ref(''), googleNotice = ref(''), googleNoticeKind = ref<'success' | 'info'>('info')
const logtoError = ref(''), logtoNotice = ref('')
const inviteLink = ref(''), inviteNotice = ref('')
const accountBusy = computed(() => busy.value || googleBusy.value || accountLoggingOut.value)
let identityEpoch = 0, pageReady = false, recoveryRefreshPending = false, restoringIdentity = false
const accountInitials = computed(() => {
  const name = googleStatus.value.name?.trim()
  if (name) return name.split(/\s+/).slice(0, 2).map(part => Array.from(part)[0]).join('').toLocaleUpperCase()
  return Array.from(googleStatus.value.email || '♪')[0]?.toLocaleUpperCase()
})
watch(accountState, (current, previous) => {
  if (current.authenticated === previous.authenticated && current.email === previous.email && current.name === previous.name) return
  identityEpoch++
  // Never leave a previous account's one-time recovery code visible after a tab changes identity.
  secret.value = ''; restoreCode.value = ''; configured.value = false
  notice.value = ''; error.value = ''; googleNotice.value = ''; googleError.value = ''
  if (pageReady && !googleBusy.value && !accountLoggingOut.value && !restoringIdentity) {
    recoveryRefreshPending = true
    finishRecoveryRefresh()
  }
})
watch(logtoState, (current, previous) => {
  if (current.authenticated === previous.authenticated && current.email === previous.email && current.phoneMasked === previous.phoneMasked && current.name === previous.name) return
  identityEpoch++
  secret.value = ''; restoreCode.value = ''; configured.value = false
  if (pageReady && !accountLoggingOut.value && !restoringIdentity) { recoveryRefreshPending = true; finishRecoveryRefresh() }
})
function finishRecoveryRefresh() {
  if (!recoveryRefreshPending || busy.value) return
  busy.value = true
  window.location.replace('/account')
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
      try { await requestJson('app/referrals/accept', 'POST', {code:route.query.invite}); inviteNotice.value = '邀请已记录，首次登录成功后邀请人将获得 20 积分。' }
      catch (e) { inviteNotice.value = e instanceof Error ? e.message : '邀请链接无效' }
    }
    if (googleStatus.value.authenticated || logtoState.value.authenticated) {
      try { const result = await requestJson<{code:string}>('app/referrals'); inviteLink.value = `${window.location.origin}/account?invite=${encodeURIComponent(result.code)}` } catch { /* 登录状态变化时可刷新重试。 */ }
    }
    const recoveryEpoch = identityEpoch
    const recovery = await requestJson<{ configured: boolean }>('app/identity/recovery')
    if (recoveryEpoch === identityEpoch) configured.value = recovery.configured
    else recoveryRefreshPending = true
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
    else if (logtoResult === 'conflict') logtoError.value = '当前作品属于另一个账号。请先保存找回码，再切换账号。'
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
    finishRecoveryRefresh()
  }
})
async function loginWithGoogle() {
  if (accountBusy.value || !googleStatus.value.enabled || googleStatus.value.authenticated) return
  googleBusy.value = true; googleError.value = ''; googleNotice.value = ''
  try { window.location.assign(await beginGoogleLogin()) }
  catch { googleError.value = '暂时无法开始 Google 登录，请稍后重试。'; googleBusy.value = false }
}
async function manageGoogleSession(action: 'switch' | 'logout') {
  if (accountBusy.value || !googleStatus.value.authenticated) return
  googleBusy.value = true; googleError.value = ''; googleNotice.value = ''
  try {
    await logoutAccount()
    configured.value = false; secret.value = ''; restoreCode.value = ''
    if (action === 'switch') window.location.assign(await beginGoogleLogin())
    else window.location.replace('/account?session=signed-out')
  } catch {
    googleError.value = action === 'switch' ? '暂时无法切换账号，请稍后重试。' : '暂时无法退出登录，请稍后重试。'
    googleBusy.value = false
  }
}
async function issue() {
  if (accountBusy.value) return
  if (configured.value && !window.confirm(t('重新生成会立即停用之前的找回码，是否继续？'))) return
  busy.value = true; error.value = ''; notice.value = ''
  const requestEpoch = identityEpoch
  try {
    await ensureCreator()
    const result = await requestJson<{code: string}>('app/identity/recovery', 'POST', {})
    if (requestEpoch !== identityEpoch) return
    secret.value = result.code; configured.value = true
    notice.value = '请保存到自己的密码管理器或下载留存。找回码仅显示这一次，使用后失效。'
  } catch (e) {
    if (requestEpoch === identityEpoch) error.value = e instanceof Error ? e.message : '生成找回码失败'
  } finally { busy.value = false; finishRecoveryRefresh() }
}
function download() {
  const url = URL.createObjectURL(new Blob([`${t('青风音乐作品找回码')}\n${secret.value}\n\n${t('请保密保存。此码可找回我的音乐，使用后失效。重新生成会停用旧码。')}\n`], {type:'text/plain;charset=utf-8'}))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = t('青风音乐-作品找回码.txt'); anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
async function copy() { try { await navigator.clipboard.writeText(secret.value); notice.value = '找回码已复制，请保密保存。' } catch { notice.value = '请手动复制，或下载找回码文件。' } }
async function copyInvite() { try { await navigator.clipboard.writeText(inviteLink.value); inviteNotice.value = '邀请链接已复制' } catch { inviteNotice.value = '请手动复制邀请链接' } }
async function recover() {
  if (accountBusy.value) return
  if (!restoreCode.value.trim() || !window.confirm(t('将打开找回码对应的音乐收藏。当前作品不会合并，请先保存自己的找回码，是否继续？'))) return
  busy.value = true; restoringIdentity = true; error.value = ''
  try { await ensureCreator(); await requestJson('public/session/recover', 'POST', {code:restoreCode.value.trim()}); resetCreatorSession(); player.reset(); secret.value = ''; restoreCode.value = ''; await Promise.all([refreshAccountState().catch(() => {}), refreshLogtoState().catch(() => {})]); await router.replace('/works') }
  catch (e) { error.value = e instanceof Error ? e.message : '恢复失败，请检查找回码' } finally { restoringIdentity = false; busy.value = false; finishRecoveryRefresh() }
}
</script>
<template>
  <div class="account-page">
    <a class="button secondary" href="#payment-orders">{{ t('查看我的订单 / 待处理付款') }}</a>

  <header class="account-intro">
    <p class="account-eyebrow"><span></span>{{ t('我的账号') }}</p>
    <h1>{{ t('账号与登录') }}</h1>
    <p>{{ t('登录音乐账号，或使用找回码找回音乐。') }}</p>
  </header>
  <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
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
      <div class="profile-actions"><span v-if="logtoState.authenticated" class="connection-badge">{{ t('已连接') }}</span><button type="button" class="google-sign-in" @click="showLoginDialog">{{ t('查看登录方式') }}</button></div>
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
          <RouterLink class="profile-library-link" to="/works">{{ t('进入我的音乐') }}<NavIcon name="arrow" /></RouterLink>
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
      <p v-if="!googleStatus.enabled && !busy" class="google-unavailable" role="status">{{ t('Google 登录暂不可用，你仍可使用作品找回码。') }}</p>
      <div class="profile-footer"><NavIcon name="recovery" /><p>{{ t('首次登录会保留当前作品。不同账号的音乐收藏不会自动合并。') }}</p></div>
    </template>
  </section>
  <section v-if="inviteNotice || inviteLink" class="account-profile" aria-label="邀请好友">
    <h2>邀请好友</h2>
    <p v-if="inviteNotice">{{ inviteNotice }}</p>
    <template v-if="inviteLink"><p>邀请一位新用户首次登录，你将获得 20 积分。</p><label class="field">邀请链接<input :value="inviteLink" readonly @focus="($event.target as HTMLInputElement).select()"></label><button class="button secondary" type="button" @click="copyInvite">复制邀请链接</button></template>
  </section>
  <CreditsPanel :key="`${googleStatus.authenticated}:${googleStatus.email || ''}:${logtoState.authenticated}:${logtoState.email || ''}:${logtoState.phoneMasked || ''}:${logtoState.name || ''}`" />
  <section class="recovery-section" :aria-label="t('备用找回方式')">
    <div class="recovery-section-heading"><h2>{{ t('备用找回方式') }}</h2><p>{{ t('多一种找回方式，多一份安心。') }}</p></div>
    <div class="account-recovery-grid">
      <section class="recovery-card">
        <div class="recovery-card-heading"><span class="recovery-icon"><NavIcon name="recovery" /></span><span class="recovery-state" :class="{ configured }">{{ t(configured ? '已设置' : '尚未设置') }}</span></div>
        <h3>{{ t('保存作品找回码') }}</h3>
        <p class="recovery-description">{{ configured ? t('你已设置找回码。如未保存，可以重新生成并使旧码失效。') : t('尚未设置找回码，请在清理浏览器或更换设备前保存。') }}</p>
        <button class="button secondary recovery-issue" :disabled="accountBusy" @click="issue">{{ configured ? t('重新生成找回码') : t('生成找回码') }}<NavIcon name="arrow" /></button>
        <template v-if="secret"><label class="field">{{ t('找回码（仅本次显示）') }}<textarea :value="secret" readonly rows="2" @focus="($event.target as HTMLTextAreaElement).select()"></textarea></label><div class="actions"><button class="button secondary" @click="copy">{{ t('复制') }}</button><button class="button secondary" @click="download">{{ t('下载留存') }}</button></div></template>
        <p class="recovery-footnote">{{ t('找回码可访问你的私人作品，请只交给自己保管。礼物分享链接不能替代找回码。') }}</p>
      </section>
      <section class="recovery-card">
        <div class="recovery-card-heading"><span class="recovery-icon"><NavIcon name="guide" /></span><span class="recovery-card-label">{{ t('从找回码继续') }}</span></div>
        <h3>{{ t('找回已有作品') }}</h3>
        <form @submit.prevent="recover"><label class="field recovery-code-field"><span class="sr-only">{{ t('输入之前保存的找回码') }}</span><input v-model="restoreCode" class="recovery-input" :disabled="accountBusy" maxlength="100" autocomplete="off" spellcheck="false" :placeholder="t('粘贴你的作品找回码')"></label><button class="button secondary recovery-submit" :disabled="accountBusy || !restoreCode.trim()">{{ t('找回我的音乐') }}<NavIcon name="arrow" /></button></form>
        <p class="recovery-footnote">{{ t('找回码只能使用一次，恢复后请生成并保存新的找回码。多个身份的作品不会自动合并。') }}</p>
      </section>
    </div>
  </section>
  <RouterLink class="account-back" to="/works"><NavIcon name="music" />{{ t('← 返回我的音乐') }}</RouterLink>
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
.google-unavailable { margin:0; padding:0 30px 20px; color:var(--warning); font-size:12px; line-height:1.8; }
.recovery-section { margin-top: 34px; }
.recovery-section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin-bottom: 15px; }
.recovery-section-heading h2 { margin: 0; color: var(--ink); font-size: 15px; font-weight: 550; }
.recovery-section-heading p { margin: 0; color: var(--muted); font-size: 11px; }
.account-recovery-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.recovery-card { min-width: 0; padding: 23px 25px 20px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); }
.recovery-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 17px; }
.recovery-icon { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid var(--accent-border); border-radius: 11px; color: var(--accent-text); background: var(--accent-soft); }
.recovery-icon svg { width: 19px; height: 19px; }
.recovery-state, .recovery-card-label { color: var(--muted); font-size: 10px; }
.recovery-state { padding: 3px 8px; border: 1px solid var(--line); border-radius: 5px; background: var(--surface-raised); }
.recovery-state.configured { border-color: var(--success-border); color: var(--success); background: var(--success-soft); }
.recovery-card h3 { color:var(--ink); margin: 0 0 9px; font-size: 18px; font-weight: 600; letter-spacing: -.3px; }
.recovery-card .recovery-description { min-height: 44px; margin: 0 0 16px; color: var(--muted); font-size: 12px; line-height: 1.8; }
.account-page .recovery-card .button { min-height: 42px; border-color: var(--line); background: #fff8f6; padding: 10px 15px; color: var(--accent-text); font-size: 12px; gap: 14px; }
.account-page .recovery-card .button:hover { border-color: var(--accent-border); background: var(--accent-soft); }
.recovery-card .button svg { width: 16px; height: 16px; }
.recovery-card .recovery-footnote { margin: 19px 0 0; padding-top: 15px; border-top: 1px solid var(--line); color: var(--muted); font-size: 10px; line-height: 1.8; }
.account-page .recovery-code-field { margin: 15px 0 13px; }
.account-page .recovery-code-field input { margin-top: 0; min-height: 44px; font-size: 13px; }
.recovery-submit { width: 100%; justify-content: space-between !important; }
.recovery-card .field textarea { min-height: 80px; }
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
  .recovery-section { margin-top: 29px; }
  .recovery-section-heading { align-items: flex-start; flex-direction: column; gap: 3px; margin-bottom: 14px; }
  .account-recovery-grid { grid-template-columns: minmax(0, 1fr); gap: 15px; }
  .recovery-card { padding: 22px 21px 19px; }
  .recovery-card .recovery-description { min-height: 0; }
  .account-page .recovery-code-field input { font-size: 16px; }
}
</style>
