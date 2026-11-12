<script setup lang="ts">
import { defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { locale } from '../../../shared/i18n'
import { uploadForm, currentCreatorId } from '../../../shared/http/api-client'
import { useAnalyticsConsent } from '../../../shared/analytics/useAnalyticsConsent'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { loadCovers, type CoverState } from '../../works/api/covers'
import type { AudioAsset } from '../../works/api/detail'
const RechargePage = defineAsyncComponent(() => import('../../identity/pages/RechargePage.vue').then(module => module.default))
const IdentityPage = defineAsyncComponent(() => import('../../identity/pages/IdentityPage.vue').then(module => module.default))
const CoverEditor = defineAsyncComponent(() => import('../../works/components/CoverEditor.vue').then(module => module.default))
const props = defineProps<{ kind: 'recharge' | 'account' | 'privacy' | 'upload_cover' | 'upload_audio'; workId?: string }>()
const emit = defineEmits<{ close: []; refresh: []; send: [text: string] }>()
const dialog = ref<HTMLDialogElement>(), error = ref(''), busy = ref(false), notice = ref(''), uncertain = ref(false), covers = ref<CoverState>()
const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
const { isAccepted, isUndecided, accept, reject, withdraw } = useAnalyticsConsent()
const titles = { privacy: '隐私设置', recharge: '充值积分', account: '账号与付款', upload_cover: '作品封面', upload_audio: '导入作品音频' }
const enTitles = { privacy: 'Privacy settings', recharge: 'Recharge credits', account: 'Account and payments', upload_cover: 'Song artwork', upload_audio: 'Import song audio' }
let alive = true
const owner = currentCreatorId()
const current = () => alive && owner === currentCreatorId()
function close() { if (busy.value) return; dialog.value?.close(); emit('close') }
async function loadCover() {
  if (!props.workId) return
  try { const value = await loadCovers(props.workId); if (current()) covers.value = value }
  catch (cause) { if (current()) error.value = cause instanceof Error ? cause.message : '无法读取作品封面' }
}
function updated(state: CoverState) { if (current()) { covers.value = state; emit('refresh') } }
function send(text: string) { close(); emit('send', text) }
function login() { close(); showLoginDialog() }
async function importAudio(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || !props.workId || busy.value || uncertain.value) return
  if (file.size > 20 * 1024 * 1024 || !/\.(mp3|wav)$/i.test(file.name)) { error.value = tr('请选择 20MB 以内的 MP3 或 WAV 音频。', 'Choose an MP3 or WAV file under 20MB.'); return }
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const data = new FormData(); data.append('file', file, file.name)
    const asset = await uploadForm<AudioAsset>(`app/works/${encodeURIComponent(props.workId)}/audio`, data)
    if (!current()) return
    notice.value = tr('音频已上传。查看音频版本后，选择要使用的版本。', 'Audio uploaded. View the audio versions and choose one to use.')
    if (!asset.id) throw new Error('上传结果待核对')
    uncertain.value = true; emit('refresh')
  } catch (cause) {
    if (current()) { uncertain.value = true; error.value = cause instanceof Error ? cause.message : '上传结果待核对，请先查看音频版本，避免重复上传。' }
  } finally { if (current()) busy.value = false }
}
onMounted(async () => {
  await nextTick(); dialog.value?.showModal?.()
  if (props.kind === 'upload_cover') await loadCover()
})
onBeforeUnmount(() => { alive = false; dialog.value?.close() })
</script>
<template>
  <dialog ref="dialog" class="agent-native-dialog" aria-labelledby="agent-native-title" @cancel.prevent="close" @close="$emit('close')">
    <header><h2 id="agent-native-title">{{ tr(titles[kind], enTitles[kind]) }}</h2><button type="button" :disabled="busy" :aria-label="tr('关闭并返回对话', 'Close and return to chat')" @click="close">×</button></header>
    <RechargePage v-if="kind === 'recharge'" embedded @login="login" @orders="send('查看我的订单')"/>
    <IdentityPage v-else-if="kind === 'account'" embedded @close="close" @works="send('查看我的作品')"/>
    <section v-else-if="kind === 'privacy'" class="agent-import-audio">
      <p>{{ tr('匿名分析仅收集页面路径和停留时长，不包含歌词、邮箱或手机号。拒绝不会影响创作功能。', 'Anonymous analytics collects page paths and visit duration, without lyrics, email addresses or phone numbers. Declining does not affect creation features.') }}</p>
      <p role="status">{{ isAccepted ? tr('当前已同意分析', 'Analytics accepted') : isUndecided ? tr('尚未选择', 'No choice made yet') : tr('当前已拒绝分析', 'Analytics declined') }}</p>
      <button v-if="!isAccepted" type="button" @click="accept">{{ tr('同意匿名分析', 'Accept analytics') }}</button>
      <button v-if="isAccepted" type="button" @click="withdraw">{{ tr('撤回同意', 'Withdraw consent') }}</button><button v-else type="button" @click="reject">{{ tr('拒绝分析', 'Decline analytics') }}</button>
    </section>
    <template v-else-if="kind === 'upload_cover' && workId">
      <p v-if="error" role="alert">{{ error }} <button type="button" @click="loadCover">{{ tr('重新读取', 'Reload') }}</button></p>
      <CoverEditor v-if="covers" :work-id="workId" :state="covers" @updated="updated" @close="close"/>
      <p v-else-if="!error" role="status">{{ tr('正在读取封面…', 'Loading artwork…') }}</p>
    </template>
    <section v-else-if="kind === 'upload_audio'" class="agent-import-audio">
      <p>{{ tr('将音频保存到这首作品。支持 20MB 以内的 MP3、WAV；导入音频会标记为非 AI 生成。', 'Save an MP3 or WAV file under 20MB to this song. Imported audio is labelled as not AI generated.') }}</p>
      <label>{{ tr('选择音频文件', 'Choose audio') }} <input type="file" accept="audio/mpeg,audio/wav,.mp3,.wav" :disabled="busy || uncertain" @change="importAudio"/></label>
      <p v-if="busy" role="status">{{ tr('正在上传…', 'Uploading…') }}</p><p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <button v-if="uncertain" type="button" :disabled="busy" @click="send(`查看作品 ${workId} 的音频版本`)">{{ tr('查看音频版本', 'View audio versions') }}</button>
    </section>
  </dialog>
</template>
<style scoped>
.agent-native-dialog{width:min(1100px,calc(100vw - 32px));max-height:88dvh;border:1px solid #efd9cc;border-radius:20px;padding:20px;background:#fffaf7;color:#59463d;box-sizing:border-box}.agent-native-dialog::backdrop{background:#2b201c66}.agent-native-dialog>header{display:flex;align-items:center;justify-content:space-between;gap:16px;border-bottom:1px solid #efded3;padding-bottom:12px}.agent-native-dialog h2{font-size:20px;margin:0}.agent-native-dialog>header button{border:0;background:transparent;font-size:28px;color:#886c5e;cursor:pointer}.agent-import-audio{padding:20px 0;line-height:1.8}.agent-import-audio label{display:grid;gap:10px}.agent-native-dialog :deep(.recharge){padding:24px 0}.agent-native-dialog :deep(.account-page){padding:24px 0}.agent-native-dialog :deep(.summary){position:static}
</style>
