<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../../shared/i18n'
import LanguageSwitcher from '../../shared/i18n/LanguageSwitcher.vue'
import { enterInvite, InviteError, readInviteStatus, type InviteStatus } from './api'

const props = defineProps<{ publicAccess?: boolean }>()
const dialog = ref<HTMLDialogElement>(), input = ref<HTMLInputElement>()
const everAdmitted = ref(false), admitted = ref(false), checking = ref(true), submitting = ref(false)
const status = ref<InviteStatus>(), code = ref(''), error = ref('')
const busy = computed(() => checking.value || submitting.value)
const accessible = computed(() => admitted.value || props.publicAccess)
let disposed = false, lastCheck = 0, expiryTimer: ReturnType<typeof setTimeout> | undefined

async function show() { await nextTick(); if (!disposed && !accessible.value && !dialog.value?.open) dialog.value?.showModal() }
async function apply(value: InviteStatus) {
  if (disposed) return
  status.value = value; admitted.value = value.admitted
  clearTimeout(expiryTimer)
  if (value.admitted) {
    everAdmitted.value = true; dialog.value?.close(); code.value = ''; error.value = ''
    if (value.enabled) expiryTimer = setTimeout(required, Math.min(2147000000, Math.max(1000, Date.parse(value.expiresAt) - Date.now() + 1000)))
  } else if (!props.publicAccess) { await show(); await nextTick(); input.value?.focus() }
}
async function check() {
  if (busy.value && !checking.value) return
  checking.value = true; error.value = ''; lastCheck = Date.now()
  try { await apply(await readInviteStatus()) }
  catch { admitted.value = false; error.value = '暂时无法验证内测资格，请检查网络后重试。'; await show() }
  finally { checking.value = false; await nextTick(); if (!accessible.value) input.value?.focus() }
}
async function submit() {
  if (busy.value || !status.value) return
  if (!code.value.trim()) { error.value = '请输入内测邀请码。'; input.value?.focus(); return }
  submitting.value = true; error.value = ''
  try {
    await enterInvite(code.value, status.value.csrfToken)
    const confirmed = await readInviteStatus()
    if (!confirmed.admitted) { error.value = '浏览器未能保存验证状态，请允许 Cookie 后重试。'; return }
    await apply(confirmed)
  } catch (cause) {
    error.value = cause instanceof InviteError && cause.code === 'INVALID' ? '邀请码不正确，请检查后重试。'
      : cause instanceof InviteError && cause.code === 'RATE_LIMITED' ? '尝试次数较多，请一分钟后再试。' : '暂时无法验证内测资格，请检查网络后重试。'
  } finally { submitting.value = false }
}
function required() { admitted.value = false; void show(); if (!busy.value) void check() }
function focus() { if (!props.publicAccess && !busy.value && Date.now() - lastCheck > 60000) void check() }
watch(() => props.publicAccess, value => {
  if (value) dialog.value?.close()
  else { void show(); void check() }
})
onMounted(() => { if (!props.publicAccess) { void show(); void check() } else checking.value = false; window.addEventListener('qingfeng:invite-required', required); window.addEventListener('focus', focus) })
onBeforeUnmount(() => { disposed = true; clearTimeout(expiryTimer); window.removeEventListener('qingfeng:invite-required', required); window.removeEventListener('focus', focus) })
</script>

<template>
  <!-- Keep an open editor mounted if an invitation expires; never resend its API requests. -->
  <div v-if="everAdmitted || publicAccess" :inert="!accessible || undefined"><slot /></div>
  <div v-else class="beta-access-screen" aria-hidden="true"></div>
  <dialog ref="dialog" class="beta-access-dialog" aria-labelledby="beta-access-title" aria-describedby="beta-access-description" @cancel.prevent @close="!accessible && show()">
    <section class="beta-access-card" :aria-busy="busy">
      <div class="beta-access-top"><span class="beta-access-label">{{ t('内测体验') }}</span><LanguageSwitcher/></div>
      <img class="beta-access-logo" src="/brand/logo-symbol.svg" alt="" width="56" height="56">
      <p class="beta-access-brand">Cheerful Music</p>
      <h1 id="beta-access-title">{{ t('让灵感，先在这里发声。') }}</h1>
      <p id="beta-access-description">{{ t('青风音乐正在内测，输入邀请码即可开始体验。') }}</p>
      <p v-if="checking" class="beta-access-checking" role="status">{{ t('正在验证内测资格…') }}</p>
      <form v-else-if="status" @submit.prevent="submit">
        <label for="beta-invite-code">{{ t('内测邀请码') }}</label>
        <input id="beta-invite-code" ref="input" v-model="code" type="text" maxlength="128" autocomplete="off" autocapitalize="characters" spellcheck="false" :placeholder="t('请输入邀请码')" :disabled="submitting" :aria-invalid="!!error || undefined" :aria-describedby="error ? 'beta-access-error' : undefined">
        <p v-if="error" id="beta-access-error" class="beta-access-error" role="alert">{{ t(error) }}</p>
        <button class="beta-access-primary" type="submit" :disabled="submitting">{{ t(submitting ? '正在验证…' : '开启我的音乐之旅') }}<span v-if="!submitting" aria-hidden="true">↗</span></button>
      </form>
      <div v-else-if="error"><p class="beta-access-error" role="alert">{{ t(error) }}</p><button class="beta-access-primary" type="button" @click="check">{{ t('重新验证') }}</button></div>
      <p class="beta-access-note">{{ t('邀请码由内测邀请人提供。') }}</p>
    </section>
  </dialog>
</template>

<style scoped>
.beta-access-screen { position:fixed; inset:0; background:#fffaf8; }
.beta-access-dialog { width:min(464px,calc(100% - 32px)); max-width:none; max-height:calc(100dvh - 32px); padding:0; border:1px solid #f2ded5; border-radius:28px; color:#282320; background:#fffaf8; color-scheme:light; box-shadow:0 24px 100px #6e3e1e1a; }
.beta-access-dialog::backdrop { background:radial-gradient(ellipse at 75% 20%,#ffe3d9ed,#fff8f3f5 65%); backdrop-filter:blur(16px); }
.beta-access-card { padding:26px 34px 28px; }
.beta-access-top { display:flex; align-items:center; justify-content:space-between; gap:14px; margin-bottom:30px; }
.beta-access-label { font-size:12px; color:#ae432f; background:#ffede5; padding:5px 12px; border-radius:20px; }
.beta-access-logo { display:block; margin-bottom:10px; }
.beta-access-brand { font-size:16px; font-weight:750; margin:0 0 26px; }
.beta-access-card h1 { font-size:27px; line-height:1.4; letter-spacing:-.6px; margin:0 0 14px; }
#beta-access-description { font-size:14px; line-height:1.8; color:#7e6b60; margin:0 0 28px; }
.beta-access-card label { display:block; font-size:13px; color:#51443c; margin-bottom:8px; }
.beta-access-card input { display:block; width:100%; min-height:52px; padding:13px 15px; border:1px solid #e4d5cb; border-radius:12px; background:white; color:#282320; font-size:16px; }
.beta-access-card input:focus { outline:2px solid #ff9f8b; outline-offset:2px; }
.beta-access-primary { display:flex; align-items:center; justify-content:center; gap:22px; width:100%; min-height:51px; margin-top:18px; padding:12px 18px; border:0; border-radius:28px; background:#ff432d; color:#fff; font-weight:700; }
.beta-access-primary:hover { background:#df3d29; }
.beta-access-primary:focus-visible { outline:3px solid #ff9f8b; outline-offset:3px; }
.beta-access-primary:disabled { opacity:.65; cursor:wait; }
.beta-access-primary span { font-size:20px; }
.beta-access-error { color:#b53725; font-size:13px; line-height:1.7; margin:12px 0 0; }
.beta-access-checking { font-size:14px; color:#7e6b60; padding:12px 0; }
.beta-access-note { color:#958173; text-align:center; font-size:12px; margin:24px 0 0; }
.beta-access-top :deep(.language-switch button) { color:#645249; }
.beta-access-top :deep(.language-switch > span) { color:#a39185; }
@media(max-width:480px) { .beta-access-card { padding:22px 24px 25px; } .beta-access-card h1 { font-size:24px; } }
</style>
