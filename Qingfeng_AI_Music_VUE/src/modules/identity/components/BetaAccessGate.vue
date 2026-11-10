<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { fetchInviteStatus, submitInviteCode } from '../api/invite-gate'

const required = ref(false)
const checking = ref(true)
const verifying = ref(false)
const code = ref('')
const error = ref('')
const csrfToken = ref('')
const input = ref<HTMLInputElement>()
let statusEpoch = 0

async function refresh() {
  const epoch = ++statusEpoch
  try {
    const status = await fetchInviteStatus()
    if (epoch !== statusEpoch) return
    csrfToken.value = status.csrfToken
    required.value = status.enabled && !status.admitted
    checking.value = false
    error.value = ''
    if (required.value) {
      await nextTick()
      input.value?.focus()
    }
  } catch {
    if (epoch !== statusEpoch) return
    checking.value = false
    required.value = true
    error.value = '暂时无法验证内测资格，请检查网络后重试。'
  }
}

function onInviteRequired() { required.value = true; void refresh() }

async function submit() {
  if (verifying.value) return
  const value = code.value.trim()
  if (!value) { error.value = '请输入内测邀请码。'; return }
  error.value = ''
  verifying.value = true
  try {
    await submitInviteCode(csrfToken.value, value)
    code.value = ''
    await refresh()
    if (!required.value && typeof window !== 'undefined') window.location.reload()
  } catch (failure) {
    error.value = failure instanceof Error && failure.message
      ? failure.message
      : '暂时无法验证内测资格，请检查网络后重试。'
  } finally {
    verifying.value = false
  }
}

onMounted(() => {
  void refresh()
  window.addEventListener('qingfeng:invite-required', onInviteRequired)
  window.addEventListener('focus', refresh)
})
onUnmounted(() => {
  statusEpoch++
  window.removeEventListener('qingfeng:invite-required', onInviteRequired)
  window.removeEventListener('focus', refresh)
})
</script>

<template>
  <div v-if="required" class="beta-access-screen" role="dialog" aria-modal="true"
       aria-labelledby="beta-access-title" aria-describedby="beta-access-description">
    <div class="beta-access-card">
      <div class="beta-access-top">
        <span class="beta-access-label">Cheerful Music</span>
      </div>
      <h1 id="beta-access-title">{{ t('青风音乐正在内测，输入邀请码即可开始体验。') }}</h1>
      <p id="beta-access-description">{{ t('邀请码由内测邀请人提供。') }}</p>
      <p v-if="checking" class="beta-access-checking" role="status">{{ t('正在验证内测资格…') }}</p>
      <form v-else @submit.prevent="submit">
        <label for="beta-invite-code">{{ t('内测邀请码') }}</label>
        <input id="beta-invite-code" ref="input" v-model="code" type="text"
               autocomplete="one-time-code" :placeholder="t('请输入邀请码')"
               :disabled="verifying" :aria-invalid="!!error" aria-describedby="beta-access-error" />
        <p v-if="error" id="beta-access-error" class="beta-access-error" role="alert">{{ t(error) }}</p>
        <button type="submit" :disabled="verifying">
          {{ t(verifying ? '正在验证…' : '开启我的音乐之旅') }}
        </button>
      </form>
      <p class="beta-access-note">{{ locale === 'en' ? 'By continuing, you agree to our' : '继续即表示你同意' }} <a href="/legal/terms.html" target="_blank" rel="noopener noreferrer">{{ locale === 'en' ? 'Terms of Service' : '服务条款' }}</a> {{ locale === 'en' ? 'and' : '与' }} <a href="/legal/privacy.html" target="_blank" rel="noopener noreferrer">{{ locale === 'en' ? 'Privacy Policy' : '隐私政策' }}</a></p>
    </div>
  </div>
</template>

<style scoped>
.beta-access-screen {
  position: fixed;
  inset: 0;
  z-index: 500;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--background, #fffafb);
  color: var(--ink, #1d1b1c);
  font-family: var(--font-sans, system-ui, sans-serif);
}
.beta-access-card { width: min(440px, 100%); }
.beta-access-top { margin-bottom: 32px; }
.beta-access-label { font-size: 12px; letter-spacing: .22em; text-transform: uppercase; color: var(--accent-text, #b93627); }
.beta-access-card h1 { margin: 0 0 12px; font-size: 26px; line-height: 1.5; font-weight: 500; }
.beta-access-card p { margin: 0 0 24px; font-size: 13px; line-height: 1.8; color: var(--muted, #7b7375); }
.beta-access-checking { margin-top: 20px; }
.beta-access-card label { display: block; margin-bottom: 8px; font-size: 12px; color: var(--muted, #7b7375); }
.beta-access-card input {
  display: block;
  width: 100%;
  min-height: 48px;
  padding: 12px 14px;
  border: 1px solid var(--accent-border, #eeb3a6);
  border-radius: 10px;
  background: var(--surface, #fff);
  color: inherit;
  font: inherit;
  letter-spacing: .08em;
}
.beta-access-card input:focus-visible { outline: 2px solid var(--accent, #ff432d); outline-offset: 2px; }
.beta-access-error { margin: 12px 0 0; color: var(--danger, #b42318); font-size: 12px; }
.beta-access-card button {
  display: block;
  width: 100%;
  min-height: 48px;
  margin-top: 18px;
  border: 0;
  border-radius: 24px;
  background: var(--accent, #ff432d);
  color: var(--on-accent, #fff);
  font: inherit;
  cursor: pointer;
}
.beta-access-card button:disabled { opacity: .6; cursor: not-allowed; }
.beta-access-note { margin-top: 28px !important; font-size: 11px !important; }
.beta-access-note a { color: inherit; font-weight: 600; text-decoration: underline; text-underline-offset: 2px; }
</style>
