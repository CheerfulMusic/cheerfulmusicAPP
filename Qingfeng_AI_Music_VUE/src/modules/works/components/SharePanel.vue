<script setup lang="ts">
import { t, formatDate } from '../../../shared/i18n/index'
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import { createShare, revokeShares, type Revision, type AudioAsset } from '../api/detail'

const props = defineProps<{ open: boolean; workId: string; revision: Revision | null; asset: AudioAsset | null; coverId?: string | null }>()
const emit = defineEmits<{ created: []; close: [] }>()
const dialog = ref<HTMLDialogElement>(), copyButton = ref<HTMLButtonElement>()
const link = ref(''), expires = ref(''), busy = ref(false), error = ref(''), notice = ref('')
let generation = 0
const context = () => JSON.stringify([props.workId, props.revision?.id, props.asset?.id, props.coverId])
watch(context, () => {
  generation++; busy.value = false; link.value = ''; expires.value = ''; notice.value = ''; error.value = ''
}, { flush: 'sync' })

async function create() {
  if (busy.value || !props.open || !props.revision?.confirmed || !props.asset) return
  if (link.value && Date.parse(expires.value) > Date.now()) return
  const turn = ++generation, before = context()
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const share = await createShare(props.workId, props.revision.id, props.asset.id)
    if (turn !== generation || before !== context()) return
    if (!/^[A-Za-z0-9_-]{43}$/.test(share.token) || !Number.isFinite(Date.parse(share.expiresAt))) throw new Error(t('分享链接暂时无法创建，请重试。'))
    link.value = new URL(`/gift/${share.token}`, location.origin).href
    expires.value = share.expiresAt; emit('created')
    await nextTick(); if (props.open) copyButton.value?.focus({ preventScroll: true })
  } catch (cause) {
    if (turn === generation) error.value = cause instanceof Error ? cause.message : '创建分享失败'
  } finally { if (turn === generation) busy.value = false }
}
watch([() => props.open, context], async () => {
  await nextTick()
  if (!props.open) { dialog.value?.close(); return }
  if (!dialog.value?.open) dialog.value?.showModal()
  await create()
}, { immediate: true })
async function copy() {
  if (!link.value) return
  try { await navigator.clipboard.writeText(link.value); notice.value = '已复制分享链接，发给朋友即可收听。' }
  catch { notice.value = '请选中下方链接手动复制。' }
}
async function revoke() {
  if (busy.value || !window.confirm(t('将关闭这个作品创建过的所有礼物链接，是否继续？'))) return
  const turn = ++generation, workId = props.workId
  busy.value = true; error.value = ''
  try {
    await revokeShares(workId)
    if (turn !== generation) return
    link.value = ''; expires.value = ''; notice.value = '此作品的全部分享已撤销，停止新的礼物与音频访问。'
  } catch (cause) { if (turn === generation) error.value = cause instanceof Error ? cause.message : '撤销失败' }
  finally { if (turn === generation) busy.value = false }
}
onBeforeUnmount(() => { generation++; dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="song-share-dialog" aria-labelledby="song-share-title" @cancel.prevent="emit('close')" @close="open && emit('close')">
    <section class="song-share-content" :aria-busy="busy">
      <button class="song-share-close" type="button" :aria-label="t('关闭分享')" @click="emit('close')">×</button>
      <span class="song-share-eyebrow">CHEERFUL MUSIC</span>
      <h2 id="song-share-title">{{ t('分享你的歌曲') }}</h2>
      <p>{{ t('朋友打开链接即可查看封面、歌词并试听，无需登录或邀请码。') }}</p>
      <p v-if="busy" role="status">{{ t('正在创建分享链接…') }}</p>
      <p v-else-if="!revision?.confirmed || !asset" role="status">{{ t('正在读取歌曲对应的歌词，请稍候…') }}</p>
      <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
      <template v-if="link">
        <label class="field">{{ t('歌曲分享链接') }}<input :value="link" readonly @focus="($event.target as HTMLInputElement).select()"></label>
        <div class="actions"><button ref="copyButton" class="button" type="button" @click="copy">{{ t('复制分享链接') }}</button><a class="button secondary" :href="link" target="_blank" rel="noopener noreferrer">{{ t('预览收听页') }} ↗</a></div>
        <p class="save-info">{{ t('有效期至') }} {{ formatDate(expires) }}</p>
      </template>
      <button v-else-if="!busy && revision?.confirmed && asset" class="button" type="button" @click="create">{{ t(error ? '重试创建分享链接' : '创建歌曲分享链接') }}</button>
      <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
      <p class="song-share-note">{{ t('请分享这里的链接，不要复制浏览器里的作品编辑地址。原始故事和编辑权限不会公开。') }}</p>
      <details class="song-share-manage"><summary>{{ t('管理分享') }}</summary><p>{{ t('撤销后不能继续获取新内容；已经下载的音频无法远程收回。') }}</p><button class="text-button" type="button" :disabled="busy" @click="revoke">{{ t('撤销本作品全部分享') }}</button></details>
    </section>
  </dialog>
</template>

<style scoped>
.song-share-dialog{width:min(540px,calc(100% - 32px));max-width:none;max-height:calc(100dvh - 32px);padding:0;border:1px solid #f1d9d1;border-radius:24px;background:#fffaf8;color:#282320;box-shadow:0 24px 100px #5d292630}
.song-share-dialog::backdrop{background:#38272b66;backdrop-filter:blur(7px)}
.song-share-content{position:relative;padding:32px}
.song-share-close{position:absolute;right:18px;top:14px;width:40px;height:40px;border:0;background:transparent;color:#796660;font-size:28px}
.song-share-eyebrow{color:#f93822;font-size:11px;letter-spacing:1.8px}
.song-share-content h2{margin:20px 0 12px;font-size:28px;font-weight:700;color:#282320}
.song-share-content p{font-size:14px;line-height:1.8;color:#78645d}
.song-share-content .field{font-size:13px;margin:24px 0 14px}
.song-share-content input{width:100%;font-size:16px;background:white;color:#463830}
.song-share-content .actions{gap:12px;margin:16px 0}
.song-share-content .actions>*{flex:1;justify-content:center;text-align:center}
.song-share-note{border-top:1px solid #f1ded6;padding-top:18px;margin-top:24px}
.song-share-manage{font-size:12px;color:#8c7268;margin-top:18px}
@media(max-width:480px){.song-share-content{padding:28px 22px}.song-share-content h2{font-size:25px}.song-share-content .actions{flex-direction:column;align-items:stretch}}
</style>
