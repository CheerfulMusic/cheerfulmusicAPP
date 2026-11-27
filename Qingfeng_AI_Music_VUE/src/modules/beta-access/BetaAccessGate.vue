<script setup lang="ts">
import FilingLink from "../../shared/ui/FilingLink.vue"
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { locale, t } from '../../shared/i18n'
import { homeSeo, siteName } from '../../shared/i18n/home-seo'
import { discoverPaths, discoverSeo, landingLinks } from '../../shared/i18n/site-seo'
import { platform } from '../../platform'
import LanguageSwitcher from '../../shared/i18n/LanguageSwitcher.vue'
import HomePublicInfo from '../home/components/HomePublicInfo.vue'
import { enterInvite, InviteError, readInviteStatus, type InviteStatus } from './api'

const props = defineProps<{ publicAccess?: boolean; publicPreview?: boolean; homeBrand?: string; discoverEntry?: boolean }>()
const dialog = ref<HTMLDialogElement>(), input = ref<HTMLInputElement>()
const everAdmitted = ref(false), admitted = ref(false), checking = ref(false), submitting = ref(false)
const status = ref<InviteStatus>(), code = ref(''), error = ref('')
const busy = computed(() => checking.value || submitting.value)
const actionRequiresInvite = ref(false)
const accessible = computed(() => admitted.value || (props.publicAccess && !actionRequiresInvite.value))
const showPreview = computed(() => props.publicPreview && !accessible.value)
const englishWebHome = computed(() => platform.kind === 'web' && locale.value === 'en' && Boolean(props.homeBrand) && !props.discoverEntry)
const entryCopy = computed(() => {
  if (props.discoverEntry) return discoverSeo[locale.value]
  if (props.homeBrand) return homeSeo[locale.value]
})
const entryLinks = computed(() => landingLinks(locale.value, props.discoverEntry ? discoverPaths[locale.value] : '/')
  .filter(link => !link.lang))
let disposed = false, lastCheck = 0, expiryTimer: ReturnType<typeof setTimeout> | undefined

async function show() {
  await nextTick()
  if (disposed || accessible.value || dialog.value?.open ||
      (!actionRequiresInvite.value && (checking.value || props.publicPreview))) return
  dialog.value?.showModal()
}
function apply(value: InviteStatus) {
  if (disposed) return
  status.value = value; admitted.value = value.admitted
  clearTimeout(expiryTimer)
  if (value.admitted) {
    actionRequiresInvite.value = false; everAdmitted.value = true; dialog.value?.close(); code.value = ''; error.value = ''
    if (value.enabled) expiryTimer = setTimeout(required, Math.min(2147000000, Math.max(1000, Date.parse(value.expiresAt) - Date.now() + 1000)))
  }
}
async function check() {
  if (busy.value) return
  checking.value = true; error.value = ''; lastCheck = Date.now()
  try {
    apply(await readInviteStatus())
  } catch {
    if (disposed) return
    status.value = undefined
    admitted.value = false
    error.value = '暂时无法验证内测资格，请检查网络后重试。'
  } finally {
    checking.value = false
  }
  if (!accessible.value) {
    await show()
    if (dialog.value?.open) input.value?.focus({ preventScroll: true })
  }
}
async function submit() {
  if (busy.value || !status.value) return
  if (!code.value.trim()) { error.value = '请输入内测邀请码。'; input.value?.focus(); return }
  submitting.value = true; error.value = ''
  try {
    await enterInvite(code.value, status.value.csrfToken)
    const confirmed = await readInviteStatus()
    if (!confirmed.admitted) { error.value = '浏览器未能保存验证状态，请允许 Cookie 后重试。'; return }
    apply(confirmed)
  } catch (cause) {
    error.value = cause instanceof InviteError && cause.code === 'INVALID' ? '邀请码不正确，请检查后重试。'
      : cause instanceof InviteError && cause.code === 'RATE_LIMITED' ? '尝试次数较多，请一分钟后再试。' : '暂时无法验证内测资格，请检查网络后重试。'
  } finally { submitting.value = false }
}
function required() { actionRequiresInvite.value = true; admitted.value = false; void show(); if (!busy.value) void check() }
function openInvite() { actionRequiresInvite.value = true; void show(); if (!status.value && !busy.value) void check() }
function cancelInvite() {
  if (!props.publicPreview) return
  actionRequiresInvite.value = false
  dialog.value?.close()
}
function focus() { if (!props.publicAccess && !busy.value && Date.now() - lastCheck > 60000) void check() }
watch(() => [props.publicAccess, props.publicPreview], ([value, preview]) => {
  actionRequiresInvite.value = false
  if (value || preview) dialog.value?.close()
  if (!value) void check()
})
onMounted(() => {
  if (!props.publicAccess) void check()
  window.addEventListener('qingfeng:invite-required', required)
  window.addEventListener('focus', focus)
})
onBeforeUnmount(() => { disposed = true; clearTimeout(expiryTimer); window.removeEventListener('qingfeng:invite-required', required); window.removeEventListener('focus', focus) })
</script>

<template>
  <!-- Keep an open editor mounted if an invitation expires; never resend its API requests. -->
  <div v-if="everAdmitted || publicAccess" v-show="!showPreview" :inert="!accessible || undefined"><slot /></div>
  <div v-else-if="!showPreview" class="beta-access-screen">
    <p v-if="checking" class="beta-access-checking" role="status">{{ t('加载中…') }}</p>
  </div>
  <slot v-if="showPreview" name="public-preview" :open-invite="openInvite" />
  <dialog ref="dialog" class="beta-access-dialog" aria-labelledby="beta-access-title" aria-describedby="beta-access-description" @cancel.prevent="cancelInvite" @close="!accessible && show()">
    <section class="beta-access-card" :aria-busy="busy">
      <div class="beta-access-top" :data-nosnippet="entryCopy ? '' : undefined"><span class="beta-access-label">{{ t('内测体验') }}</span><LanguageSwitcher/></div>
      <img class="beta-access-logo" src="/brand/logo-symbol.svg" alt="" width="56" height="56">
      <template v-if="homeBrand && locale === 'zh-CN'">
        <h1 id="beta-access-title" class="beta-access-brand">{{ homeBrand }}</h1>
        <p class="beta-access-headline">{{ homeSeo[locale].tagline }}</p>
      </template>
      <template v-else>
        <p class="beta-access-brand">{{ englishWebHome ? homeSeo.en.searchName : homeBrand ?? siteName }}</p>
        <h1 id="beta-access-title">{{ entryCopy?.heading ?? t('让灵感，先在这里发声。') }}</h1>
      </template>
      <p id="beta-access-description" class="beta-access-intro">
        <template v-if="englishWebHome">{{ homeSeo.en.searchDescription }}</template>
        <span v-else-if="entryCopy && homeBrand && !discoverEntry" data-nosnippet>{{ entryCopy.description }}</span>
        <template v-else>{{ entryCopy?.description ?? t('青风音乐正在内测，输入邀请码即可开始体验。') }}</template>
      </p>
      <p v-if="englishWebHome" class="beta-access-intro"><span data-nosnippet>{{ homeSeo.en.description }}</span></p>
      <div data-nosnippet>
        <p v-if="homeBrand" class="beta-access-prompt">{{ homeSeo[locale].invitePrompt }}</p>
        <p v-if="checking" class="beta-access-checking" role="status">{{ t('正在验证内测资格…') }}</p>
        <form v-else-if="status" @submit.prevent="submit">
          <label for="beta-invite-code">{{ t('内测邀请码') }}</label>
          <input id="beta-invite-code" ref="input" v-model="code" type="text" maxlength="128" autocomplete="off" autocapitalize="characters" spellcheck="false" :placeholder="t('请输入邀请码')" :disabled="submitting" :aria-invalid="!!error || undefined" :aria-describedby="error ? 'beta-access-error' : undefined">
          <p v-if="error" id="beta-access-error" class="beta-access-error" role="alert">{{ t(error) }}</p>
          <button class="beta-access-primary" type="submit" :disabled="submitting">{{ t(submitting ? '正在验证…' : '开启我的音乐之旅') }}<span v-if="!submitting" aria-hidden="true">↗</span></button>
        </form>
        <div v-else-if="error"><p class="beta-access-error" role="alert">{{ t(error) }}</p><button class="beta-access-primary" type="button" @click="check">{{ t('重新验证') }}</button></div>
        <p class="beta-access-note">{{ t('邀请码由内测邀请人提供。') }}</p>
      </div>
      <HomePublicInfo v-if="homeBrand && !accessible && (status || error || actionRequiresInvite) && platform.kind === 'web'" show-steps />
      <div :data-nosnippet="entryCopy ? '' : undefined">
        <nav v-if="entryCopy && platform.kind === 'web'" class="beta-access-guides" :aria-label="locale === 'en' ? 'Music guides' : '音乐使用指南'">
          <a v-for="link in entryLinks" :key="link.href" :href="link.href">{{ link.text }}</a>
        </nav>
        <button v-if="publicPreview" type="button" class="button secondary" @click="cancelInvite">{{ t('关闭') }}</button>
        <footer class="beta-access-filing"><FilingLink /></footer>
      </div>
    </section>
  </dialog>
</template>

<style>
.beta-access-entry { display:grid; min-height:100dvh; place-items:center; }
.beta-access-entry .beta-access-dialog { max-height:none; }
.beta-access-filing { margin-top: 12px; text-align: center; color: #68594f; }
.beta-access-screen { position:fixed; inset:0; display:grid; place-items:center; background:#fffaf8; }
.beta-access-dialog { width:min(464px,calc(100% - 32px)); max-width:none; max-height:calc(100dvh - 32px); padding:0; border:1px solid #f2ded5; border-radius:28px; color:#282320; background:#fffaf8; color-scheme:light; box-shadow:0 24px 100px #6e3e1e1a; }
.beta-access-entry,.beta-access-dialog::backdrop { background:radial-gradient(ellipse at 75% 20%,#ffe3d9ed,#fff8f3f5 65%); }
.beta-access-dialog::backdrop { backdrop-filter:blur(16px); }
.beta-access-card { padding:26px 34px 28px; }
.beta-access-top { display:flex; align-items:center; justify-content:space-between; gap:14px; margin-bottom:30px; }
.beta-access-label { font-size:12px; color:#ae432f; background:#ffede5; padding:5px 12px; border-radius:20px; }
.beta-access-logo { display:block; margin-bottom:10px; }
.beta-access-brand { font-size:16px; font-weight:750; margin:0 0 26px; }
.beta-access-card h1:not(.beta-access-brand),.beta-access-headline { font-size:27px; line-height:1.4; font-weight:700; letter-spacing:-.6px; margin:0 0 14px; }
.beta-access-intro { font-size:14px; line-height:1.8; color:#7e6b60; margin:0 0 28px; }
.beta-access-prompt { font-size:14px; margin:0 0 12px; }
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
.beta-access-guides { display:flex; flex-wrap:wrap; justify-content:center; gap:8px 14px; margin-top:14px; font-size:13px; }
.beta-access-guides a { color:#914030; text-underline-offset:3px; }
.beta-access-top .language-switch button,.beta-access-top .language-switch a { color:#645249; }
.beta-access-top .language-switch > span { color:#a39185; }
@media(max-width:480px) { .beta-access-card { padding:22px 24px 25px; } .beta-access-card h1:not(.beta-access-brand),.beta-access-headline { font-size:24px; } }
</style>
