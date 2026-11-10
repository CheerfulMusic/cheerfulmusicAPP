<script setup lang="ts">
import { t, formatDate, defaultCover, locale } from '../../../shared/i18n/index'
import { ref, watch, nextTick, onBeforeUnmount, computed } from 'vue'
import { createShare, revokeShares, type Revision, type AudioAsset } from '../api/detail'
import { requestJson, apiUrl } from '../../../shared/http/api-client'
import { lyricQuotes, localShareAddress, posterFilename, shareCancelled, shareSongCard, songShareUrl, type SharedSong } from '../model/song-sharing'
import { usePlayer } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'

const props = defineProps<{ open: boolean; workId: string; revision: Revision | null; asset: AudioAsset | null; coverId?: string | null }>()
const emit = defineEmits<{ created: []; close: [] }>()
const dialog = ref<HTMLDialogElement>(), shareButton = ref<HTMLButtonElement>()
const link = ref(''), token = ref(''), expires = ref(''), song = ref<SharedSong>()
const busy = ref(false), error = ref(''), notice = ref(''), sharing = ref(false)
const posterOpen = ref(false), posterBusy = ref(false), posterUrl = ref(''), posterBlob = ref<Blob>(), posterError = ref('')
const signature = ref(''), quote = ref(''), artworkFailed = ref(false), player = usePlayer()
let generation = 0, posterGeneration = 0
const context = () => JSON.stringify([props.workId, props.revision?.id, props.asset?.id, props.coverId])
const quotes = computed(() => lyricQuotes(song.value?.lyrics || ''))
const cover = computed(() => song.value?.hasCover && !artworkFailed.value
  ? apiUrl(`public/gifts/${token.value}/cover?proxy=true`) : defaultCover())
const track = computed(() => song.value && token.value ? {
  id: `gift:${token.value}`, title: song.value.title, cover: cover.value,
  audio: apiUrl(`public/gifts/${token.value}/audio`), provenance: props.asset?.origin,
} : undefined)
const currentPlaying = computed(() => player.current?.id === track.value?.id && (player.playing || player.starting))
const valid = () => !!link.value && Date.parse(expires.value) > Date.now()
function clearPoster() {
  posterGeneration++; posterBusy.value = false; posterBlob.value = undefined
  if (posterUrl.value) URL.revokeObjectURL(posterUrl.value)
  posterUrl.value = ''; posterError.value = ''
}
function reset() {
  generation++; busy.value = false; sharing.value = false; link.value = ''; token.value = ''; expires.value = ''
  song.value = undefined; notice.value = ''; error.value = ''; posterOpen.value = false
  quote.value = ''; signature.value = ''; artworkFailed.value = false; clearPoster()
}
watch(context, reset, { flush: 'sync' })
watch([quote, signature, locale, cover], clearPoster, { flush: 'sync' })

async function create() {
  if (busy.value || !props.open || !props.revision?.confirmed || !props.asset) return
  if (song.value && valid()) return
  const turn = ++generation, before = context()
  busy.value = true; error.value = ''; notice.value = ''; clearPoster()
  try {
    // 卡片读取失败时保留已创建的令牌，重试只读取，避免不断创建分享。
    if (!token.value || !valid()) {
      const share = await createShare(props.workId, props.revision.id, props.asset.id)
      if (turn !== generation || before !== context()) return
      if (!Number.isFinite(Date.parse(share.expiresAt))) throw new Error(t('分享链接暂时无法创建，请重试。'))
      link.value = songShareUrl(share.token, location.origin); token.value = share.token
      expires.value = share.expiresAt; emit('created')
    }
    const result = await requestJson<SharedSong>(`public/gifts/${token.value}`)
    if (turn !== generation || before !== context()) return
    song.value = result; quote.value = lyricQuotes(result.lyrics)[0] || ''
    await nextTick(); if (props.open) shareButton.value?.focus({ preventScroll: true })
  } catch (cause) {
    if (turn === generation) error.value = cause instanceof Error ? cause.message : '创建分享失败'
  } finally { if (turn === generation) busy.value = false }
}
watch([() => props.open, context], async () => {
  await nextTick()
  if (!props.open) { dialog.value?.close(); clearPoster(); return }
  if (!dialog.value?.open) dialog.value?.showModal()
  if (!song.value || !valid()) await create()
}, { immediate: true })
function checkLink() {
  if (valid()) return true
  error.value = '分享链接已过期，请重新创建分享。'; return false
}
async function copy() {
  if (!checkLink()) return
  const turn = generation
  try { await navigator.clipboard.writeText(link.value); if (turn === generation) notice.value = '已复制分享链接，发给朋友即可收听。' }
  catch { if (turn === generation) notice.value = '请选中下方链接手动复制。' }
}
async function share() {
  if (sharing.value || !song.value || !checkLink()) return
  const turn = generation; sharing.value = true; error.value = ''; notice.value = ''
  try {
    const result = await shareSongCard({ title: song.value.title, text: t('听听我创作的这首歌 · Cheerful Music'), url: link.value })
    if (turn === generation && result !== 'cancelled') notice.value = result === 'shared' ? '已交给系统分享，请在分享应用中完成发送。' : '已复制分享链接，发给朋友即可收听。'
  } catch { if (turn === generation) notice.value = '当前应用无法直接分享，请复制链接或保存海报。' }
  finally { if (turn === generation) sharing.value = false }
}
async function makePoster() {
  if (!song.value || posterBusy.value || !checkLink()) return
  if (posterBlob.value) return
  const turn = ++posterGeneration, before = generation
  posterBusy.value = true; posterError.value = ''
  try {
    const { renderSongPoster } = await import('../model/song-poster')
    const blob = await renderSongPoster({ title: song.value.title, cover: cover.value, link: link.value,
      quote: quote.value, signature: signature.value, tagline: t('人人都是歌手'),
      scanLabel: t('扫码收听我的歌曲'), expiresLabel: `${t('收听链接有效期至')} ${formatDate(expires.value)}` })
    if (turn !== posterGeneration || before !== generation || !props.open) return
    posterBlob.value = blob; posterUrl.value = URL.createObjectURL(blob)
  } catch (cause) { if (turn === posterGeneration) posterError.value = cause instanceof Error ? cause.message : '海报生成失败，请重试或复制链接。' }
  finally { if (turn === posterGeneration) posterBusy.value = false }
}
async function openPoster() { posterOpen.value = !posterOpen.value; if (posterOpen.value) await makePoster() }
function savePoster() {
  if (!posterUrl.value || !song.value || !checkLink()) return
  const a = document.createElement('a'); a.href = posterUrl.value; a.download = posterFilename(song.value.title)
  a.click(); notice.value = '已开始保存海报；若未弹出下载，请长按海报图片保存。'
}
async function sharePoster() {
  if (!posterBlob.value || !song.value || !checkLink() || sharing.value) return
  const file = new File([posterBlob.value], posterFilename(song.value.title), { type: 'image/png' })
  try { if (!navigator.canShare?.({ files: [file] }) || !navigator.share) { savePoster(); return } }
  catch { savePoster(); return }
  const turn = generation; sharing.value = true
  try { await navigator.share({ files: [file] }); if (turn === generation) notice.value = '已交给系统分享，请在分享应用中完成发送。' }
  catch (cause) { if (turn === generation && !shareCancelled(cause)) notice.value = '当前应用无法直接分享，请复制链接或保存海报。' }
  finally { if (turn === generation) sharing.value = false }
}
async function revoke() {
  if (busy.value || !window.confirm(t('将关闭这个作品创建过的所有礼物链接，是否继续？'))) return
  const turn = ++generation, workId = props.workId, oldTrack = track.value?.id
  busy.value = true; error.value = ''; clearPoster()
  try {
    await revokeShares(workId)
    if (turn !== generation) return
    reset(); if (player.current?.id === oldTrack) player.reset()
    notice.value = '此作品的全部分享已撤销，停止新的礼物与音频访问。'
  } catch (cause) { if (turn === generation) error.value = cause instanceof Error ? cause.message : '撤销失败' }
  finally { if (turn === generation) busy.value = false }
}
onBeforeUnmount(() => { generation++; clearPoster(); dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="song-share-dialog" aria-labelledby="song-share-title" @cancel.prevent="emit('close')" @close="open && emit('close')">
    <section class="song-share-content" :aria-busy="busy">
      <button class="song-share-close" type="button" :aria-label="t('关闭分享')" @click="emit('close')">×</button>
      <h2 id="song-share-title">{{ t('让你的歌被听见') }}</h2>
      <p class="share-intro">{{ t('分享给朋友，或把喜欢的一句做成音乐海报。') }}</p>
      <p v-if="busy" role="status">{{ t('正在准备歌曲卡片…') }}</p>
      <p v-else-if="!revision?.confirmed || !asset" role="status">{{ t('正在读取歌曲对应的歌词，请稍候…') }}</p>
      <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
      <template v-if="song && link && !busy">
        <article class="share-song-card" :aria-label="t('歌曲分享卡片')">
          <div class="share-card-art"><img :src="cover" :alt="t('歌曲封面')" @error="artworkFailed=true"><button type="button" :aria-label="currentPlaying ? t('暂停') : t('试听歌曲')" @click="track && player.toggle(track)"><PlaybackIcon :playing="currentPlaying"/></button></div>
          <div class="share-card-copy"><span>Cheerful Music</span><h3>{{ song.title }}</h3><p>{{ t('我的原创，邀请你来听') }}</p></div>
        </article>
        <div class="share-primary-actions">
          <button ref="shareButton" class="button" type="button" :disabled="sharing" @click="share">{{ t(sharing ? '正在打开分享…' : '分享歌曲卡片') }}</button>
          <button class="button secondary" type="button" :aria-expanded="posterOpen" @click="openPoster">{{ t(posterOpen ? '收起音乐海报' : '制作音乐海报') }}</button>
        </div>
        <p class="share-platform-note">{{ t('卡片展示由接收应用决定，点击后进入公开收听页。') }}</p>
        <section v-if="posterOpen" class="share-poster-workspace" :aria-label="t('音乐海报')">
          <div class="share-poster-options">
            <h3>{{ t('把这一句，分享出去') }}</h3>
            <label>{{ t('海报歌词') }}<select v-model="quote"><option value="">{{ t('不展示歌词') }}</option><option v-for="line in quotes" :key="line" :value="line">{{ line }}</option></select></label>
            <label>{{ t('作者署名（选填）') }}<input v-model="signature" maxlength="24" :placeholder="t('写下你想展示的名字')"></label>
            <button class="button secondary" type="button" :disabled="posterBusy" @click="makePoster">{{ t(posterBusy ? '正在制作海报…' : posterUrl ? '海报已更新' : '生成海报预览') }}</button>
            <p>{{ t('修改歌词或署名后，重新生成预览即可。') }}</p>
            <p v-if="posterError" class="notice error" role="alert">{{ t(posterError) }}</p>
            <div v-if="posterUrl" class="share-poster-downloads"><button class="button" type="button" @click="savePoster">{{ t('保存海报') }}</button><button class="text-button" type="button" :disabled="sharing" @click="sharePoster">{{ t('分享海报图片') }}</button></div>
          </div>
          <div class="share-poster-preview"><img v-if="posterUrl" :src="posterUrl" :alt="t('音乐海报预览，可长按保存')"><p v-else role="status">{{ t(posterBusy ? '正在制作海报…' : '生成预览后可保存图片') }}</p></div>
        </section>
      </template>
      <template v-if="link">
        <details class="share-link-backup" :open="!!error"><summary>{{ t('复制链接或预览收听页') }}</summary><label class="field">{{ t('歌曲分享链接') }}<input :value="link" readonly @focus="($event.target as HTMLInputElement).select()"></label><div class="actions"><button class="button secondary" type="button" @click="copy">{{ t('复制分享链接') }}</button><a class="text-button" :href="link" target="_blank" rel="noopener noreferrer">{{ t('预览收听页') }} ↗</a></div></details>
        <p class="save-info">{{ t('收听链接有效期至') }} {{ formatDate(expires) }}</p>
        <p v-if="localShareAddress(link)" class="notice">{{ t('当前链接是本机或内网地址，朋友的设备需要能访问此地址才能收听。') }}</p>
      </template>
      <button v-if="!busy && revision?.confirmed && asset && (!song || error)" class="button" type="button" @click="create">{{ t(error ? '重试创建分享链接' : '创建歌曲分享链接') }}</button>
      <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
      <p class="song-share-note">{{ t('朋友无需登录即可收听，原始故事和编辑权限不会公开。') }}</p>
      <details class="song-share-manage"><summary>{{ t('管理分享') }}</summary><p>{{ t('撤销后链接及海报二维码将失效，已保存的图片和音频无法收回。') }}</p><button class="text-button" type="button" :disabled="busy" @click="revoke">{{ t('撤销本作品全部分享') }}</button></details>
    </section>
  </dialog>
</template>

<style scoped>
.song-share-dialog{width:min(740px,calc(100% - 32px));max-width:none;max-height:calc(100dvh - 32px);padding:0;border:1px solid #f1d9d1;border-radius:26px;background:#fffaf8;color:#222223;box-shadow:0 24px 100px #5d292630;overscroll-behavior:contain}
.song-share-dialog::backdrop{background:#38272b66;backdrop-filter:blur(7px)}
.song-share-content{position:relative;padding:36px}.song-share-close{position:absolute;right:16px;top:16px;width:44px;height:44px;border:0;background:transparent;color:#796660;font-size:28px}
.song-share-content h2{margin:4px 36px 10px 0;font-size:28px;font-weight:700;line-height:1.4;color:#222223}.song-share-content p{font-size:13px;line-height:1.7;color:#78645d}.share-intro{margin:0 0 25px}
.share-song-card{display:flex;align-items:center;gap:24px;padding:20px;border:1px solid #edddd5;border-radius:18px;background:white}.share-card-art{position:relative;flex:0 0 142px;width:142px}.share-card-art img{display:block;width:100%;aspect-ratio:1;object-fit:cover;border-radius:12px}.share-card-art button{position:absolute;bottom:8px;right:8px;border:0;border-radius:50%;background:#f93822;color:white;width:42px;height:42px;display:grid;place-items:center}.share-card-art svg{width:20px;height:20px}
.share-card-copy{min-width:0}.share-card-copy>span{font-size:12px;color:#8b7771}.share-card-copy h3{margin:12px 0;font-size:25px;font-weight:700;line-height:1.4;overflow-wrap:anywhere;color:#222223}.share-card-copy p{margin:0}
.share-primary-actions{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:24px}.song-share-content .button{display:inline-flex;align-items:center;justify-content:center;min-height:50px;padding:12px 20px;border:1px solid #f93822;border-radius:999px;background:#f93822;color:white;font-weight:600;white-space:normal;text-align:center}.song-share-content .button.secondary{color:#d8311e;background:#fff0eb;border-color:#f9b4a6}.share-platform-note{text-align:center;font-size:12px!important;margin:12px 0 20px}
.share-poster-workspace{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,240px);gap:24px;padding:22px 0;border-top:1px solid #edddd5}.share-poster-options h3{margin:0 0 18px;color:#222223;font-size:18px;font-weight:700}.share-poster-options label{display:block;margin:16px 0;font-size:13px;color:#64534d}.share-poster-options input,.share-poster-options select{display:block;width:100%;min-height:46px;margin-top:8px;background:white;border:1px solid #dfc6bd;border-radius:10px;padding:10px;color:#282320;font-size:16px}.share-poster-options .button{width:100%}.share-poster-downloads{display:grid;gap:15px;margin-top:20px}.share-poster-downloads .text-button{justify-content:center;min-height:44px;color:#ce3422}.share-poster-preview{background:#efe4df;border-radius:12px;min-height:230px;display:grid;place-items:center;padding:12px;align-self:start}.share-poster-preview img{display:block;width:100%;height:auto;border-radius:4px;box-shadow:0 8px 25px #48362a18}.share-poster-preview p{text-align:center;padding:16px}
.share-link-backup{border-top:1px solid #edddd5;padding-top:17px;font-size:14px}.share-link-backup summary,.song-share-manage summary{cursor:pointer;min-height:36px;align-content:center}.share-link-backup .field{font-size:13px;margin:15px 0}.share-link-backup input{font-size:16px;background:white;color:#463830;width:100%}.share-link-backup .actions{gap:20px;margin:12px 0;flex-wrap:wrap}.share-link-backup .text-button{min-height:44px}
.song-share-content .save-info{font-size:12px}.song-share-note{margin-top:20px}.song-share-manage{font-size:12px;color:#8c7268}.song-share-content button:focus-visible,.song-share-content a:focus-visible{outline:3px solid #ed8a76;outline-offset:3px}
@media(max-width:560px){.song-share-content{padding:28px 20px}.song-share-content h2{font-size:24px}.share-song-card{gap:16px;padding:14px}.share-card-art{width:104px;flex-basis:104px}.share-card-copy h3{font-size:20px;margin:8px 0}.share-card-copy p{font-size:12px}.share-primary-actions{gap:10px}.share-primary-actions>.button{padding:12px 8px;font-size:14px}.share-poster-workspace{grid-template-columns:1fr;gap:18px}.share-poster-preview{width:min(100%,220px);justify-self:center;grid-row:1}.song-share-dialog{max-height:calc(100dvh - 16px)}}
</style>
