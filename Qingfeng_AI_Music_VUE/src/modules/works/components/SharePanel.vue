<script setup lang="ts">
import { t, formatDate, defaultCover, locale } from '../../../shared/i18n/index'
import { ref, watch, nextTick, onBeforeUnmount, computed } from 'vue'
import { createShare, getSongShareStatus, revokeSongShare, revokeShares, type Revision, type AudioAsset } from '../api/detail'
import { platform } from '../../../platform'
import { apiUrl } from '../../../shared/http/api-client'
import { lyricQuotes, localShareAddress, posterFilename, shareCancelled, songShareUrl, songTimelineTitle, wechatShareEntry, type SongShareStatus, type WechatDestination } from '../model/song-sharing'
import { usePlayer } from '../../../shared/player/store'
import PublicSongStory from './PublicSongStory.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'

const props = defineProps<{ open: boolean; workId: string; revision: Revision | null; asset: AudioAsset | null; coverId?: string | null; workTitle?: string }>()
const emit = defineEmits<{ created: []; revoked: []; close: [] }>()
const dialog = ref<HTMLDialogElement>(), shareButton = ref<HTMLAnchorElement>(), more = ref<HTMLDetailsElement>()
const summary = ref<SongShareStatus>()
const song = computed(() => summary.value?.card)
const expires = computed(() => song.value?.expiresAt ?? '')
const link = computed(() => song.value ? songShareUrl(song.value.songId, platform.kind === 'web' ? location.origin : new URL(apiUrl('songs')).origin) : '')
const stateLabel = { PRIVATE: '未启用歌曲分享页', SHAREABLE: '已启用公开试听', REVOKED: '已撤销公开试听', EXPIRED: '分享链接已过期' }
const busy = ref(false), error = ref(''), notice = ref(''), sharing = ref(false)
const posterOpen = ref(false), posterBusy = ref(false), posterUrl = ref(''), posterBlob = ref<Blob>(), posterError = ref('')
const signature = ref(''), quote = ref(''), artworkFailed = ref(false), player = usePlayer()
let generation = 0, posterGeneration = 0
const insideWechat = /MicroMessenger/i.test(navigator.userAgent)
const hasSystemShare = !insideWechat && typeof navigator.share === 'function'
const destination = ref<WechatDestination>(), qr = ref(''), qrError = ref(false)
const handoff = window.cheerfulWechat
const desktopFriend = platform.kind === 'web' && handoff?.mode('friend') === 'desktop'
const handoffMessage = ref(''), manualCopy = ref(false), manualLink = ref<HTMLInputElement>()
let qrRequest = 0
const importedRevisionId = computed(() => props.asset?.origin === 'IMPORTED' && props.revision?.confirmed ? props.revision.id : undefined)
const canCreate = computed(() => !!props.asset && (props.asset.origin === 'GENERATED' || !!importedRevisionId.value))
const context = () => JSON.stringify([props.workId, importedRevisionId.value, props.asset?.id, props.coverId, props.workTitle])
const quotes = computed(() => lyricQuotes(song.value?.lyrics || ''))
const cover = computed(() => song.value?.hasCover && !artworkFailed.value
  ? apiUrl(`songs/${song.value.songId}/cover`) : defaultCover())
const track = computed(() => song.value ? {
  id: `song:${song.value.songId}`, title: song.value.title, cover: cover.value,
  audio: apiUrl(`songs/${song.value.songId}/audio`), provenance: props.asset?.origin,
} : undefined)
const currentPlaying = computed(() => player.current?.id === track.value?.id && (player.playing || player.starting))
const valid = () => !!link.value && Date.parse(expires.value) > Date.now()
function clearPoster() {
  posterGeneration++; posterBusy.value = false; posterBlob.value = undefined
  if (posterUrl.value) URL.revokeObjectURL(posterUrl.value)
  posterUrl.value = ''; posterError.value = ''
}
function clearDestination() {
  qrRequest++
  destination.value = undefined
  qr.value = ''
  qrError.value = false
  handoffMessage.value = ''
  manualCopy.value = false
}
function reset() {
  generation++; busy.value = false; sharing.value = false; summary.value = undefined
  clearDestination()
  notice.value = ''; error.value = ''; posterOpen.value = false
  quote.value = ''; signature.value = ''; artworkFailed.value = false; clearPoster()
}
watch([quote, signature, locale, cover], clearPoster, { flush: 'sync' })

async function refresh(change?: () => Promise<unknown>, afterChange?: () => void) {
  const asset = props.asset, work = props.workId
  if (busy.value || !props.open || !asset) return
  const turn = ++generation
  clearDestination()
  busy.value = true; error.value = ''; notice.value = ''; clearPoster()
  try {
    if (change) {
      await change()
      if (turn !== generation) return
      afterChange?.()
    }
    const result = await getSongShareStatus(work, asset.id)
    if (turn !== generation) return
    summary.value = result
    quote.value = ''
    if (change && result.card) {
      busy.value = false
      await nextTick()
      if (turn === generation) shareButton.value?.focus({ preventScroll: true })
    }
  } catch (cause) {
    if (turn !== generation) return
    summary.value = undefined
    error.value = cause instanceof Error ? cause.message : '分享状态暂时无法读取'
  } finally { if (turn === generation) busy.value = false }
}

function create() {
  const asset = props.asset
  if (!asset || !canCreate.value || !summary.value) return
  const work = props.workId, revision = importedRevisionId.value, title = props.workTitle
  return refresh(() => createShare(work, asset.id, revision, title), () => emit('created'))
}

watch([() => props.open, context], async ([open]) => {
  reset()
  const turn = generation
  await nextTick()
  if (turn !== generation) return
  if (!open) { dialog.value?.close(); return }
  if (!dialog.value?.open) dialog.value?.showModal()
  await refresh()
}, { immediate: true, flush: 'sync' })
function checkLink() {
  if (valid()) return true
  error.value = '分享链接已过期，请重新创建分享。'; return false
}
const linkInput = ref<HTMLInputElement>()
async function copy() {
  if (!checkLink()) return
  const turn = generation
  try { await navigator.clipboard.writeText(link.value); if (turn === generation) notice.value = '已复制分享链接，发给朋友即可收听。' }
  catch {
    if (turn !== generation) return
    linkInput.value?.focus(); linkInput.value?.select()
    notice.value = '请选中链接手动复制。'
  }
}
async function choose(event: MouseEvent, target: WechatDestination) {
  if (!checkLink()) { event.preventDefault(); return }
  if (platform.kind !== 'web') {
    event.preventDefault()
    if (sharing.value || !song.value) return
    const turn = generation, id = song.value.songId
    sharing.value = true; error.value = ''; notice.value = ''
    const current = () => turn === generation && props.open && valid() && id === song.value?.songId
    try {
      const { shareNativeMusic } = await import('../../../platform/native/wechat-music')
      if (current() && await shareNativeMusic(id, target, current) && current()) notice.value = '已交给微信，请在微信中完成分享。'
    } catch (cause) {
      if (current()) error.value = cause instanceof Error ? cause.message : '暂时无法打开微信。'
    } finally { if (turn === generation) sharing.value = false }
    return
  }
  // In WeChat the right-hand menu belongs to the current document. Open the signed public song first.
  if (insideWechat) return
  event.preventDefault()
  clearDestination()
  const request = qrRequest
  destination.value = target
  posterOpen.value = false
  notice.value = ''
  if (more.value) more.value.open = false
  if (event.isTrusted && song.value && handoff) {
    const current = () => request === qrRequest && props.open && valid()
    const payload = { title: target === 'timeline' ? songTimelineTitle(song.value) : song.value.title,
      text: t('听听我创作的这首歌 · Cheerful Music'), url: link.value }
    void handoff.open(target, payload, current).then(async result => {
      if (!result || !current()) return
      handoffMessage.value = result.message
      manualCopy.value = !!result.manual
      if (manualCopy.value) {
        await nextTick()
        if (current()) { manualLink.value?.focus(); manualLink.value?.select() }
      }
    })
  }
  try {
    const renderer = await import('../model/song-poster')
    const image = await renderer.wechatShareQr(link.value, target)
    if (request === qrRequest) qr.value = image
  } catch { if (request === qrRequest) qrError.value = true }
}
async function systemShare() {
  if (sharing.value || !song.value || !checkLink()) return
  const turn = generation; sharing.value = true; error.value = ''; notice.value = ''
  try {
    await navigator.share({ title: songTimelineTitle(song.value), text: t('听听我创作的这首歌 · Cheerful Music'), url: link.value })
  } catch (cause) { if (turn === generation && !shareCancelled(cause)) notice.value = '系统分享暂不可用，可选择微信好友或朋友圈。' }
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
      quote: quote.value, signature: signature.value.trim() || song.value.creator || '', tagline: t('邀请你来听听'),
      scanLabel: t('长按识别二维码试听'), expiresLabel: `${t('公开试听有效期至')} ${formatDate(expires.value)}` })
    if (turn !== posterGeneration || before !== generation || !props.open) return
    posterBlob.value = blob; posterUrl.value = URL.createObjectURL(blob)
  } catch (cause) { if (turn === posterGeneration) posterError.value = cause instanceof Error ? cause.message : '海报生成失败，请重试或复制链接。' }
  finally { if (turn === posterGeneration) posterBusy.value = false }
}
async function openPoster() {
  clearDestination()
  notice.value = ''
  if (more.value) more.value.open = false
  posterOpen.value = !posterOpen.value
  if (posterOpen.value) await makePoster()
}
function savePoster() {
  if (!posterUrl.value || !song.value || !checkLink()) return
  const a = document.createElement('a'); a.href = posterUrl.value; a.download = posterFilename(song.value.title)
  a.click(); notice.value = '已开始保存海报；若未弹出下载，请长按海报图片保存。'
}
async function sharePoster() {
  if (!posterBlob.value || !song.value || !checkLink() || sharing.value) return
  if (typeof File !== 'function') { savePoster(); return }
  const file = new File([posterBlob.value], posterFilename(song.value.title), { type: 'image/png' })
  try { if (!navigator.canShare?.({ files: [file] }) || !navigator.share) { savePoster(); return } }
  catch { savePoster(); return }
  const turn = generation; sharing.value = true
  try { await navigator.share({ files: [file] }); if (turn === generation) notice.value = '已交给系统分享，请在分享应用中完成发送。' }
  catch (cause) { if (turn === generation && !shareCancelled(cause)) notice.value = '当前应用无法直接分享，请复制链接或保存海报。' }
  finally { if (turn === generation) sharing.value = false }
}
function revoke(all: boolean) {
  const asset = props.asset, work = props.workId, oldTrack = track.value?.id
  const prompt = all ? '将关闭这个作品创建过的所有礼物链接，是否继续？' : '撤销这个歌曲版本的公开试听？'
  if (!asset || busy.value || !window.confirm(t(prompt))) return
  return refresh(() => all ? revokeShares(work) : revokeSongShare(work, asset.id), () => {
    posterOpen.value = false
    if (oldTrack && player.current?.id === oldTrack) player.reset()
    notice.value = all ? '此作品的全部分享已撤销，已购买者仍可下载已购音频。' : '此版本的公开试听已撤销。'
    emit('revoked')
  })
}
onBeforeUnmount(() => { generation++; clearDestination(); clearPoster(); dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="song-share-dialog" aria-labelledby="song-share-title" @cancel.prevent="emit('close')" @close="open && emit('close')">
    <section class="song-share-content" :aria-busy="busy">
      <button class="song-share-close" type="button" :aria-label="t('关闭分享')" @click="emit('close')">×</button>
      <h2 id="song-share-title">{{ t('分享歌曲') }}</h2>
      <p v-if="summary" class="save-info" role="status">
        {{ t(stateLabel[summary.status]) }}
        <template v-if="summary.status !== 'PRIVATE'"> · {{ t('{count} 次访问', { count: summary.views }) }} · {{ t('{count} 次播放', { count: summary.plays }) }}</template>
      </p>
      <p v-if="busy" role="status">{{ t('正在准备歌曲卡片…') }}</p>
      <p v-else-if="!canCreate" role="status">{{ t('正在读取歌曲对应的歌词，请稍候…') }}</p>
      <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
      <template v-if="song && link && !busy">
        <article class="share-song-card song-card-preview" :aria-label="t('歌曲分享卡片')">
          <div class="share-card-main">
            <div class="share-card-copy">
              <div class="share-card-details">
                <h3 class="share-card-title">{{ song.title }}</h3>
                <span v-if="song.creator" class="share-card-artist">{{ song.creator }}</span>
                <span class="share-card-invitation">{{ t('邀请你来听听') }}</span>
              </div>
              <span class="share-card-brand">青风音乐 Cheerful Music</span>
            </div>
            <div class="share-card-art"><img :src="cover" :alt="t('歌曲封面')" @error="artworkFailed=true"></div>
          </div>
          <button class="share-card-listen" type="button" @click="track && player.toggle(track)">
            <span class="share-card-play-icon" aria-hidden="true"><PlaybackIcon :playing="currentPlaying"/></span>
            {{ currentPlaying ? t('暂停') : t('试听歌曲') }}
          </button>
        </article>
        <h3 class="channel-heading">{{ t('分享到') }}</h3>
        <div class="share-destinations" role="group" :aria-label="t('分享到')">
          <a ref="shareButton" :href="wechatShareEntry(link, 'friend')" :aria-current="destination === 'friend' || undefined" @click="choose($event,'friend')"><span class="destination-icon for-friend" aria-hidden="true"></span>{{ t('微信好友') }}<small>{{ t(desktopFriend ? '复制链接并打开电脑微信' : '发歌曲卡片') }}</small></a>
          <a :href="wechatShareEntry(link, 'timeline')" :aria-current="destination === 'timeline' || undefined" @click="choose($event,'timeline')"><span class="destination-icon for-timeline" aria-hidden="true"><i></i></span>{{ t('朋友圈') }}</a>
          <button type="button" :aria-expanded="posterOpen" @click="openPoster"><span class="destination-icon for-poster" aria-hidden="true"><i>♫</i></span>{{ t('音乐海报') }}<small>{{ t('发大封面图片') }}</small></button>
        </div>
        <div v-if="destination" class="destination-guide" role="status">
          <img v-if="qr" :src="qr" width="144" height="144" :alt="t('在微信中打开歌曲的二维码')">
          <div>
            <p v-if="handoffMessage">{{ t(handoffMessage) }}</p>
            <input v-if="manualCopy" ref="manualLink" readonly :value="link" :aria-label="t('手动复制歌曲链接')" @focus="manualLink?.select()">
            <a v-if="desktopFriend && destination === 'friend'" class="wechat-open" :href="handoff?.desktopUrl">{{ t('打开电脑微信') }}</a>
            <p>{{ t(destination === 'friend' ? '用微信扫一扫打开歌曲，再点右上角「…」选择「发送给朋友」。' : '用微信扫一扫打开歌曲，再点右上角「…」选择「分享到朋友圈」。') }}</p>
            <p>{{ t(qrError ? '二维码暂时无法生成，可重新点选分享方式。' : qr ? '同一部手机上可截图，再从微信扫一扫的相册打开。' : '正在生成二维码…') }}</p>
          </div>
        </div>
      </template>
      <section v-if="song && link && !busy && posterOpen" class="share-poster-workspace" :aria-label="t('音乐海报')">
          <div class="share-poster-options">
            <h3>{{ t('大封面音乐海报') }}</h3>
            <p>{{ t('发图给好友后，可长按右下角二维码试听。') }}</p>
            <label>{{ t('海报歌词') }}<select v-model="quote"><option value="">{{ t('不展示歌词') }}</option><option v-for="line in quotes" :key="line" :value="line">{{ line }}</option></select></label>
            <label>{{ t('作者署名（选填）') }}<input v-model="signature" maxlength="24" :placeholder="song.creator || t('写下你想展示的名字')"></label>
            <button class="button secondary" type="button" :disabled="posterBusy" @click="makePoster">{{ t(posterBusy ? '正在制作海报…' : posterUrl ? '海报已更新' : '生成海报预览') }}</button>
            <p>{{ t('修改歌词或署名后，重新生成预览即可。') }}</p>
            <p v-if="posterError" class="notice error" role="alert">{{ t(posterError) }}</p>
            <div v-if="posterUrl" class="share-poster-downloads"><button class="button" type="button" @click="savePoster">{{ t('保存海报') }}</button><button class="text-button" type="button" :disabled="sharing" @click="sharePoster">{{ t('分享海报图片') }}</button></div>
          </div>
          <div class="share-poster-preview"><img v-if="posterUrl" :src="posterUrl" :alt="t('音乐海报预览，可长按保存')"><p v-else role="status">{{ t(posterBusy ? '正在制作海报…' : '生成预览后可保存图片') }}</p></div>
      </section>
      <template v-if="link">
        <details ref="more" class="more-share" @toggle="more?.open && clearDestination()">
          <summary>{{ t('更多分享方式') }}</summary>
          <div class="share-link-panel">
            <div class="share-link-heading">
              <label for="song-share-link">{{ t('歌曲分享链接') }}</label>
              <a class="text-button" :href="link" target="_blank" rel="noopener noreferrer">{{ t('预览收听页') }} ↗</a>
            </div>
            <div class="share-link-actions">
              <input ref="linkInput" id="song-share-link" :value="link" readonly @focus="($event.target as HTMLInputElement).select()">
              <button class="button secondary" type="button" @click="copy()">{{ t('复制分享链接') }}</button>
            </div>
            <button v-if="hasSystemShare" class="text-button" type="button" :disabled="sharing" @click="systemShare">{{ t('系统分享') }}</button>
          </div>
        </details>
        <p class="save-info">{{ t('公开试听有效期至') }} {{ formatDate(expires) }}</p>
        <p v-if="localShareAddress(link)" class="notice">{{ t('当前链接是本机或内网地址，朋友的设备需要能访问此地址才能收听。') }}</p>
      </template>
      <PublicSongStory v-if="song" :key="song.songId" :song-id="song.songId" />
      <button v-if="!busy && !summary && error" class="button" type="button" @click="refresh()">{{ t('重新读取分享状态') }}</button>
      <button v-if="!busy && canCreate && summary && !song" class="button" type="button" @click="create">{{ t('启用公开试听') }}</button>
      <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
      <p class="song-share-note">{{ t('朋友无需登录即可收听，原始故事和编辑权限不会公开。') }}</p>
      <details class="song-share-manage">
        <summary>{{ t('管理分享') }}</summary>
        <p>{{ t('撤销后未购买者无法继续访问；已付费解锁者仍可下载已购买音频。') }}</p>
        <button v-if="song" class="text-button" type="button" :disabled="busy || !canCreate" @click="create">{{ t('更新分享卡片') }}</button>
        <button v-if="song" class="text-button" type="button" :disabled="busy" @click="revoke(false)">{{ t('撤销此版本分享') }}</button>
        <button class="text-button" type="button" :disabled="busy" @click="revoke(true)">{{ t('撤销本作品全部分享') }}</button>
      </details>
    </section>
  </dialog>
</template>

<style src="../../../../public/brand/share-destinations.css"></style>
<style scoped>
.channel-heading { margin: 1.25rem 0 .4rem; font-size: 1.05rem; font-weight: 650; }
.song-share-dialog{width:min(740px,calc(100% - 32px));max-width:none;max-height:calc(100dvh - 32px);padding:0;border:1px solid #f1d9d1;border-radius:26px;background:#fffaf8;color:#222223;box-shadow:0 24px 100px #5d292630;overscroll-behavior:contain}
.song-share-dialog::backdrop{background:#38272b66;backdrop-filter:blur(7px)}
.song-share-content{position:relative;padding:36px}.song-share-close{position:absolute;right:16px;top:16px;width:44px;height:44px;border:0;background:transparent;color:#796660;font-size:28px}
.song-share-content h2{margin:4px 36px 10px 0;font-size:28px;font-weight:700;line-height:1.4;color:#222223}.song-share-content p{font-size:13px;line-height:1.7;color:#78645d}
.song-share-content .button{display:inline-flex;align-items:center;justify-content:center;min-height:50px;padding:12px 20px;border:1px solid #f93822;border-radius:999px;background:#f93822;color:white;font-weight:600;white-space:normal;text-align:center}.song-share-content .button.secondary{color:#d8311e;background:#fff0eb;border-color:#f9b4a6}
.share-poster-workspace{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,48%);gap:24px;padding:22px 0;border-top:1px solid #edddd5}.share-poster-options h3{margin:0 0 18px;color:#222223;font-size:18px;font-weight:700}.share-poster-options label{display:block;margin:16px 0;font-size:13px;color:#64534d}.share-poster-options input,.share-poster-options select{display:block;width:100%;min-height:46px;margin-top:8px;background:white;border:1px solid #dfc6bd;border-radius:10px;padding:10px;color:#282320;font-size:16px}.share-poster-options .button{width:100%}.share-poster-downloads{display:grid;gap:15px;margin-top:20px}.share-poster-downloads .text-button{justify-content:center;min-height:44px;color:#ce3422}.share-poster-preview{background:#efe4df;border-radius:12px;min-height:130px;display:grid;place-items:center;padding:12px;align-self:start}.share-poster-preview img{display:block;width:100%;height:auto;border-radius:4px;box-shadow:0 8px 25px #48362a18}.share-poster-preview p{text-align:center;padding:16px}
.share-link-panel { margin:18px 0 14px; padding:13px 16px 16px; border:1px solid #f9b4a6; border-radius:16px; background:#fff0eb; }
.share-link-heading { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:4px 12px; margin-bottom:8px; }
.share-link-heading label { font-size:14px; font-weight:650; }
.share-link-heading .text-button { color:#ad2d1d; font-size:13px; }
.share-link-actions { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:stretch; gap:10px; }
.share-link-actions input { width:100%; min-width:0; min-height:46px; padding:9px 12px; border:1px solid #dfc6bd; border-radius:10px; background:white; color:#463830; font-size:16px; }
.song-share-manage summary { cursor:pointer; min-height:36px; align-content:center; }
.song-share-content .save-info{font-size:12px}.song-share-note{margin-top:20px}.song-share-manage{font-size:12px;color:#8c7268}.song-share-content button:focus-visible,.song-share-content a:focus-visible{outline:3px solid #ed8a76;outline-offset:3px}
@media(max-width:560px){.song-share-content{padding:28px 20px}.song-share-content h2{font-size:24px}.share-poster-workspace{grid-template-columns:1fr;gap:18px}.share-poster-preview{width:100%;justify-self:center;grid-row:1}.song-share-dialog{inset:auto 0 0;width:100%;max-height:calc(100dvh - 12px);margin:0 auto;border-radius:22px 22px 0 0}.song-share-content{padding-bottom:max(24px,env(safe-area-inset-bottom))}}
@media(max-width:480px) { .share-link-actions { grid-template-columns:1fr; } }
</style>
