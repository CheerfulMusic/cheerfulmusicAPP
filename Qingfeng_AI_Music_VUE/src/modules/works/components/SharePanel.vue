<script setup lang="ts">
import { t, formatDate, defaultCover, locale } from '../../../shared/i18n/index'
import { ref, watch, nextTick, onBeforeUnmount, computed } from 'vue'
import { createShare, getSongShareStatus, revokeSongShare, revokeShares, type Revision, type AudioAsset } from '../api/detail'
import { platform } from '../../../platform'
import { apiUrl } from '../../../shared/http/api-client'
import { isWechatBrowser, lyricQuotes, localShareAddress, posterFilename, songShareUrl, songTimelineTitle, wechatShareEntry, type SongShareStatus, type WechatDestination, type WechatShareFeedback, type WechatSongData } from '../model/song-sharing'
import { internationalShareChannels, internationalShareHref, copyInternationalShareLink, shareInternationalSong, shareInternationalPoster } from '../model/international-song-sharing'
import { usePlayer } from '../../../shared/player/store'
import PublicSongStory from './PublicSongStory.vue'
import WechatSelfCard from './WechatSelfCard.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'

const props = defineProps<{ open: boolean; workId: string; revision: Revision | null; asset: AudioAsset | null; coverId?: string | null; workTitle?: string }>()
const emit = defineEmits<{ created: []; revoked: []; close: [] }>()
const dialog = ref<HTMLDialogElement>()
const fallbackOpen = ref(false)
let fallbackOpener: HTMLElement | undefined
const english = computed(() => locale.value === 'en')
const brandMenu = ref<HTMLDetailsElement>(), manage = ref<HTMLDetailsElement>()
const posterSection = ref<HTMLElement>()
const summary = ref<SongShareStatus>()
const song = computed(() => summary.value?.status === 'SHAREABLE' ? summary.value.card : undefined)
const expires = computed(() => song.value?.expiresAt ?? '')
const listeningOrigin = platform.kind === 'web' ? location.origin : new URL(apiUrl('songs')).origin
const dailyShareOrigin = import.meta.env.VITE_SONG_SHARE_ORIGIN
const shareOrigin = platform.kind === 'web' && dailyShareOrigin === 'https://cheerfulmusic.com'
    && ['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname)
  ? dailyShareOrigin : listeningOrigin
const link = computed(() => song.value ? songShareUrl(song.value.songId, shareOrigin, song.value.shortCode) : '')
const sharePath = computed(() => link.value ? new URL(link.value).pathname : '')
const shareLink = computed(() => localizedSongLink(link.value))
const listeningLink = computed(() => song.value ? localizedSongLink(songShareUrl(song.value.songId, listeningOrigin, song.value.shortCode)) : '')
const xPlayerLink = computed(() => {
  if (!song.value) return ''
  const address = new URL(songShareUrl(song.value.songId, listeningOrigin))
  address.pathname += '/x-player'
  return localizedSongLink(address.href)
})
function localizedSongLink(address: string) {
  return english.value && address ? address + '?lang=en' : address
}
const youtubeOpen = ref(false), youtubeInput = ref<HTMLInputElement>()
const youtubeCopyStatus = ref<'copied' | 'manual'>()
const brandLogo = '/brand/logo-symbol.svg'
const invitation = computed(() => t('来听听我创作的歌曲'))
const sharePayload = computed(() => ({
  title: [song.value?.title, song.value?.creator].filter(Boolean).join(' · '),
  text: invitation.value,
  url: shareLink.value,
}))
const stateLabel = { PRIVATE: '未启用歌曲分享页', SHAREABLE: '公开试听已开启', REVOKED: '已撤销公开试听', EXPIRED: '分享链接已过期' }
const busy = ref(false), error = ref(''), notice = ref(''), sharing = ref(false)
const posterOpen = ref(false), posterBusy = ref(false), posterUrl = ref(''), posterBlob = ref<Blob>(), posterError = ref('')
type PosterPurpose = 'download' | 'instagram' | 'customize'
const posterPurpose = ref<PosterPurpose>('download')
const signature = ref(''), quote = ref(''), artworkFailed = ref(false), player = usePlayer()
let generation = 0, posterGeneration = 0
const pendingChanges = new Map<string, Promise<unknown>>()
const insideWechat = isWechatBrowser(navigator.userAgent)
const hasSystemShare = !insideWechat && typeof navigator.share === 'function'
const destination = ref<WechatDestination>(), qr = ref(''), qrError = ref(false)
const client = platform.kind === 'web' && window.cheerfulWechat?.mode('friend') === 'client'
  ? window.cheerfulWechat : undefined
const shareData = computed(() => ({
  title: [song.value?.title, song.value?.creator].filter(Boolean).join(' · '),
  text: t('听听我创作的这首歌 · Cheerful Music'), url: link.value,
}))
const handoffMessage = ref(''), manualLink = ref<HTMLTextAreaElement>()
const manualAddressVisible = ref(false)
let qrRequest = 0
const importedRevisionId = computed(() => props.asset?.origin === 'IMPORTED' && props.revision?.confirmed ? props.revision.id : undefined)
const canCreate = computed(() => !!props.asset && (props.asset.origin === 'GENERATED' || !!importedRevisionId.value))
const context = () => JSON.stringify([props.workId, importedRevisionId.value, props.asset?.id, props.coverId, props.workTitle])
const quotes = computed(() => lyricQuotes(song.value?.lyrics || ''))
const publicCover = computed(() => song.value?.hasCover
  ? apiUrl(`songs/${song.value.songId}/cover`) : defaultCover())
const cover = computed(() => artworkFailed.value ? defaultCover() : publicCover.value)
const track = computed(() => song.value ? {
  id: `song:${song.value.songId}`, title: song.value.title, cover: cover.value,
  audio: apiUrl(`songs/${song.value.songId}/audio`), provenance: props.asset?.origin,
} : undefined)
const currentPlaying = computed(() => player.current?.id === track.value?.id && (player.playing || player.starting))
const previewStarting = computed(() => player.current?.id === track.value?.id && player.starting)
const valid = () => props.open && !busy.value && !!link.value && Date.parse(expires.value) > Date.now()

function retireExpiredShare() {
  if (!summary.value || !song.value) return
  if (player.current?.id === track.value?.id) player.reset()
  summary.value = { ...summary.value, status: 'EXPIRED', card: null }
  clearDestination()
  clearPoster()
  posterOpen.value = false
}

// Retire an open card when its public grant expires while the dialog is on screen.
watch([expires, () => props.open], ([until, open], _, onCleanup) => {
  if (!until || !open) return
  let timer: ReturnType<typeof setTimeout> | undefined
  const expire = () => {
    const remaining = Date.parse(until) - Date.now()
    if (remaining > 0) {
      timer = setTimeout(expire, Math.min(remaining, 2_147_483_647))
      return
    }
    retireExpiredShare()
  }
  expire()
  onCleanup(() => clearTimeout(timer))
})
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
  manualAddressVisible.value = false
  youtubeOpen.value = false
  youtubeCopyStatus.value = undefined
}
function reset() {
  generation++; busy.value = false; sharing.value = false; summary.value = undefined
  clearDestination()
  notice.value = ''; error.value = ''; posterOpen.value = false
  if (brandMenu.value) brandMenu.value.open = false
  if (manage.value) manage.value.open = false
  quote.value = ''; signature.value = ''; artworkFailed.value = false; clearPoster()
}
watch([quote, signature, locale, publicCover], clearPoster, { flush: 'sync' })
watch(locale, () => {
  notice.value = ''
  youtubeCopyStatus.value = undefined
  if (posterOpen.value && posterPurpose.value !== 'customize' && valid()) void makePoster()
})

async function refresh(change?: () => Promise<unknown>, afterChange?: () => void) {
  const asset = props.asset, work = props.workId
  if (busy.value || !props.open || !asset) return
  const turn = ++generation
  clearDestination()
  busy.value = true; sharing.value = false; error.value = ''; notice.value = ''; clearPoster()
  try {
    // Reopening or switching versions while a write is pending must read the settled work state.
    const pending = pendingChanges.get(work)
    if (pending) await pending.catch(() => undefined)
    if (turn !== generation) return
    if (change) {
      const mutation = change()
      pendingChanges.set(work, mutation)
      try { await mutation } finally { pendingChanges.delete(work) }
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
      if (turn === generation) dialog.value?.querySelector<HTMLAnchorElement>('.international-wechat')?.focus({ preventScroll: true })
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
  if (!open) { closeDialog(); return }
  const element = dialog.value
  if (element && !element.hasAttribute('open')) {
    if (typeof element.showModal === 'function') element.showModal()
    else {
      fallbackOpener = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
      fallbackOpen.value = true
      element.setAttribute('open', '')
      element.querySelector<HTMLElement>('#song-share-title')?.focus()
    }
  }
  await refresh()
}, { immediate: true, flush: 'sync' })
function closeDialog() {
  const element = dialog.value
  if (!element?.hasAttribute('open')) return
  if (fallbackOpen.value) {
    element.removeAttribute('open')
    fallbackOpen.value = false
    if (fallbackOpener?.isConnected) fallbackOpener.focus()
    fallbackOpener = undefined
  } else element.close()
}
function fallbackKeydown(event: KeyboardEvent) {
  if (!fallbackOpen.value || !dialog.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
    return
  }
  if (event.key !== 'Tab') return
  const focusable = [...dialog.value.querySelectorAll<HTMLElement>(
    'a[href], button:enabled, input:enabled, select:enabled, textarea:enabled, summary',
  )].filter(element => element.getClientRects().length > 0)
  const activeElement = document.activeElement
  const first = focusable[0], last = focusable[focusable.length - 1]
  if (!first || (event.shiftKey ? activeElement === first : activeElement === last)) {
    event.preventDefault()
    const nextFocus = event.shiftKey ? last : first
    nextFocus?.focus()
  }
}
function checkLink() {
  if (valid()) return true
  if (song.value && !(Date.parse(expires.value) > Date.now())) retireExpiredShare()
  if (props.open && !busy.value) error.value = '分享链接已过期，请重新创建分享。'
  return false
}
function previewSong() {
  if (track.value && checkLink()) void player.toggle(track.value)
}
const linkInput = ref<HTMLInputElement>()
async function copy() {
  if (!checkLink()) return
  const turn = generation, address = shareLink.value
  const current = () => turn === generation && shareLink.value === address && valid()
  const copied = await copyInternationalShareLink(address, linkInput.value, current)
  if (current()) notice.value = copied ? '复制成功' : '请选中链接手动复制。'
}
function toggleYoutube() {
  if (!checkLink()) return
  const opening = !youtubeOpen.value
  clearDestination()
  posterOpen.value = false
  notice.value = ''
  youtubeOpen.value = opening
}
async function copyForYoutube() {
  if (!checkLink()) return
  const request = qrRequest, address = shareLink.value
  const current = () => request === qrRequest && youtubeOpen.value && shareLink.value === address && valid()
  const copied = await copyInternationalShareLink(address, youtubeInput.value ?? linkInput.value, current)
  if (!current()) return
  youtubeCopyStatus.value = copied ? 'copied' : 'manual'
  if (!copied) {
    await nextTick()
    if (current()) { youtubeInput.value?.focus(); youtubeInput.value?.select() }
  }
}
function showManagement() {
  if (brandMenu.value) brandMenu.value.open = false
  if (!manage.value) return
  manage.value.open = true
  const heading = manage.value.querySelector('summary')
  heading?.focus()
  heading?.scrollIntoView?.({ block: 'nearest' })
}
async function systemShare() {
  if (brandMenu.value?.open) {
    brandMenu.value.open = false
    brandMenu.value.querySelector('summary')?.focus({ preventScroll: true })
  }
  if (sharing.value || !song.value || !checkLink()) return
  const turn = generation
  sharing.value = true
  notice.value = ''
  try {
    const payload = { ...sharePayload.value, title: english.value ? sharePayload.value.title : songTimelineTitle(song.value) }
    const result = hasSystemShare ? await shareInternationalSong(payload) : 'unavailable'
    if (result === 'unavailable' && turn === generation && payload.url === shareLink.value && valid()) await copy()
  } finally {
    if (turn === generation) sharing.value = false
  }
}
async function showFeedback(result: WechatShareFeedback | undefined, request: number) {
  if (!result || request !== qrRequest || !props.open || !valid()) return
  handoffMessage.value = result.message
  if (client?.isWindows && result.clipboard) manualAddressVisible.value = result.clipboard === 'manual'
  if (result.clipboard === 'manual') {
    await nextTick()
    if (request === qrRequest && props.open) { manualLink.value?.focus(); manualLink.value?.select() }
  }
}
async function copyShareDraft() {
  if (!client || !destination.value || !checkLink()) return
  const request = qrRequest
  try {
    await showFeedback(await client.copyDraft(shareData.value, destination.value), request)
  } catch {
    await showFeedback({ message: '请手动复制歌曲页地址。', clipboard: 'manual' }, request)
  }
}
async function openWechatClient(request: number, data: WechatSongData) {
  if (!client) return
  try {
    await showFeedback(await client.open('friend', data), request)
  } catch {
    await showFeedback({ message: '浏览器未能打开微信。请手动打开电脑微信。' }, request)
  }
}
async function chooseWechat(event: MouseEvent) {
  if (!checkLink()) { event.preventDefault(); return }
  const target = 'friend'
  clearDestination()
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
  // The WeChat menu shares the current document. Open the signed public song first.
  if (insideWechat) return
  event.preventDefault()
  const request = qrRequest
  destination.value = target
  posterOpen.value = false
  notice.value = ''
  if (event.isTrusted) void openWechatClient(request, shareData.value)
  try {
    const address = link.value
    const renderer = await import('../model/song-poster')
    if (request !== qrRequest || !valid()) return
    const image = await renderer.wechatShareQr(address, target)
    if (request === qrRequest && valid()) qr.value = image
  } catch { if (request === qrRequest) qrError.value = true }
}
async function makePoster() {
  if (!song.value || posterBusy.value || !checkLink()) return
  if (posterBlob.value) return
  const turn = ++posterGeneration, before = generation
  posterBusy.value = true; posterError.value = ''
  try {
    const renderer = await import('../model/song-poster')
    if (turn !== posterGeneration || before !== generation || !props.open || !song.value || !valid()) return
    const blob = await renderer.renderInternationalSongPoster({
      title: song.value.title, artist: song.value.creator || '', cover: publicCover.value, link: shareLink.value,
      tagline: invitation.value, quote: quote.value, signature: signature.value.trim(), scanLabel: t('扫码试听'),
    })
    if (turn !== posterGeneration || before !== generation || !props.open || !valid()) return
    posterUrl.value = URL.createObjectURL(blob)
    posterBlob.value = blob
  } catch {
    if (turn === posterGeneration) posterError.value = '海报生成失败，请重试或复制链接。'
  }
  finally { if (turn === posterGeneration) posterBusy.value = false }
}
async function openPoster(purpose: PosterPurpose) {
  if (!checkLink()) return
  const turn = generation
  clearDestination()
  posterPurpose.value = purpose
  posterOpen.value = true
  notice.value = ''
  void nextTick(() => {
    if (turn === generation && posterOpen.value) posterSection.value?.scrollIntoView?.({ block: 'nearest' })
  })
  if (purpose === 'instagram' && posterBlob.value && !insideWechat) {
    await sharePoster()
    return
  }
  await makePoster()
  if (turn !== generation || !posterOpen.value || posterPurpose.value !== purpose || !posterUrl.value || !valid()) return
  if (purpose === 'download' && song.value) {
    const download = document.createElement('a')
    if ('download' in download && !insideWechat) {
      download.href = posterUrl.value
      download.download = posterFilename(song.value.title)
      download.click()
    }
  }
}
async function sharePoster() {
  if (!posterBlob.value || !song.value || sharing.value || !checkLink()) return
  const turn = generation, poster = posterGeneration, purpose = posterPurpose.value
  sharing.value = true
  notice.value = ''
  try {
    const result = insideWechat ? 'unavailable' : await shareInternationalPoster(posterBlob.value, song.value.title)
    if (result === 'unavailable' && turn === generation && poster === posterGeneration
      && purpose === posterPurpose.value && posterOpen.value && valid()) {
      notice.value = purpose === 'instagram' ? '这个浏览器无法分享文件。请保存海报，再到 Instagram 上传。'
        : '当前应用无法直接分享，请复制链接或保存海报。'
    }
  } finally {
    if (turn === generation) sharing.value = false
  }
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
onBeforeUnmount(() => { generation++; clearDestination(); clearPoster(); closeDialog() })
</script>

<template>
  <div v-if="fallbackOpen && open" class="song-share-fallback-backdrop" aria-hidden="true" @wheel.prevent @touchmove.prevent></div>
  <dialog ref="dialog" class="song-share-dialog international-share-dialog" :class="{ 'dialog-fallback': fallbackOpen }"
    aria-labelledby="song-share-title" aria-modal="true" role="dialog" @cancel.prevent="emit('close')"
    @close="open && !dialog?.hasAttribute('open') && emit('close')" @keydown="fallbackKeydown">
    <header class="international-share-header">
      <button class="international-round-control" type="button" :aria-label="t('关闭分享')" @click="emit('close')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m5 5 14 14M19 5 5 19"/></svg>
      </button>
      <div class="international-brand">
        <img :src="brandLogo" alt="">
        <div><strong>{{ t('青风音乐') }}</strong><span>cheerfulmusic.com</span></div>
      </div>
      <details ref="brandMenu" class="international-brand-menu">
        <summary class="international-round-control" :aria-label="t('分享菜单')">
          <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="4" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="20" cy="12" r="1.9"/></svg>
        </summary>
        <div class="international-brand-menu-items">
          <template v-if="song && link && !busy">
            <button type="button" :disabled="sharing" @click="systemShare">{{ t('更多选项') }}</button>
            <a :href="listeningLink" target="_blank" rel="noopener noreferrer" @click="!checkLink() && $event.preventDefault()">{{ t('打开收听页') }}</a>
            <a :href="xPlayerLink" target="_blank" rel="noopener noreferrer" @click="!checkLink() && $event.preventDefault()">{{ t('预览 X 播放器') }}</a>
          </template>
          <button type="button" @click="showManagement">{{ t('管理分享') }}</button>
        </div>
      </details>
    </header>
    <section class="song-share-content" :aria-busy="busy">
      <button class="song-share-close" type="button" :aria-label="t('关闭分享')" @click="emit('close')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m5 5 14 14M19 5 5 19"/></svg>
      </button>
      <h2 id="song-share-title" tabindex="-1">{{ t('分享歌曲') }}</h2>
      <p v-if="summary" class="save-info" role="status">
        {{ t(stateLabel[summary.status]) }}
        <template v-if="summary.status !== 'PRIVATE'"> · {{ t('{count} 次访问', { count: summary.views }) }} · {{ t('{count} 次播放', { count: summary.plays }) }}</template>
      </p>
      <p v-if="busy" role="status">{{ t('正在准备歌曲卡片…') }}</p>
      <p v-else-if="!canCreate" role="status">{{ t('正在读取歌曲对应的歌词，请稍候…') }}</p>
      <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
      <template v-if="song && link && !busy">
        <article class="share-song-card international-song-card" :aria-label="t('歌曲试听')">
          <div class="international-song-details">
            <h3>{{ song.title }}</h3>
            <span v-if="song.creator" class="international-song-artist">{{ song.creator }}</span>
            <p>{{ invitation }}</p>
          </div>
          <div class="international-song-cover">
            <img v-if="song.hasCover && !artworkFailed" :src="cover" :alt="t('歌曲封面')" @error="artworkFailed = true">
            <span v-else class="international-cover-placeholder" role="img" :aria-label="t('封面暂无')">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M9 17V7l11-3v10M9 10l11-3"/><ellipse cx="6" cy="17" rx="3" ry="2"/><ellipse cx="17" cy="14" rx="3" ry="2"/>
              </svg>
            </span>
          </div>
        </article>
        <button class="international-preview" type="button" :aria-busy="previewStarting"
          :aria-label="previewStarting ? t('取消试听') : undefined" @click="previewSong">
          <span class="international-play-icon" aria-hidden="true"><PlaybackIcon :playing="currentPlaying"/></span>
          {{ t(previewStarting ? '正在加载试听…' : currentPlaying ? '暂停试听' : '播放试听') }}
          <svg class="international-preview-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg>
        </button>
        <p v-if="player.current?.id === track?.id && player.error" class="notice error" role="alert">{{ t(player.error) }}</p>
        <h3 id="international-channel-heading" class="international-channel-heading">{{ t('分享到') }}</h3>
        <div class="international-share-channels" role="group" aria-labelledby="international-channel-heading">
          <template v-for="channel in internationalShareChannels" :key="channel.id">
            <a v-if="channel.id === 'wechat'" class="international-wechat"
              :href="wechatShareEntry(link, 'friend')" :aria-current="destination === 'friend' || undefined"
              @click="chooseWechat">
              <span class="international-channel-icon"><img :src="channel.icon" alt=""></span>
              {{ english ? channel.label : channel.chineseLabel }}
            </a>
            <button v-else-if="channel.id === 'youtube'" type="button" :aria-expanded="youtubeOpen" @click="toggleYoutube">
              <span class="international-channel-icon"><img :src="channel.icon" alt=""></span>
              {{ english ? channel.label : channel.chineseLabel }}
            </button>
            <button v-else-if="channel.id === 'instagram'" type="button" :aria-expanded="posterOpen && posterPurpose === 'instagram'"
              @click="openPoster('instagram')">
              <span class="international-channel-icon"><img :src="channel.icon" alt=""></span>
              {{ english ? channel.label : channel.chineseLabel }}
            </button>
            <a v-else :href="internationalShareHref(channel.id, sharePayload)"
              :target="channel.id === 'email' ? undefined : '_blank'" rel="noopener noreferrer"
              @click="!checkLink() && $event.preventDefault()">
              <span class="international-channel-icon"><img :src="channel.icon" alt=""></span>
              {{ english ? channel.label : channel.chineseLabel }}
            </a>
          </template>
        </div>
        <div v-if="youtubeOpen" class="destination-guide youtube-share-guide">
          <div>
            <p>{{ t('复制歌曲链接，再粘贴到 YouTube Studio 的视频简介里。') }}</p>
            <div class="youtube-share-actions">
              <button type="button" class="international-secondary" @click="copyForYoutube">
                {{ t('复制歌曲链接') }}
              </button>
              <a class="international-secondary" href="https://studio.youtube.com/" target="_blank" rel="noopener noreferrer"
                @click="!checkLink() && $event.preventDefault()">{{ t('打开 YouTube Studio') }}</a>
            </div>
            <p v-if="youtubeCopyStatus === 'copied'" role="status">{{ t('复制成功') }}</p>
            <template v-if="youtubeCopyStatus === 'manual'">
              <p role="status">{{ t('请选中链接手动复制。') }}</p>
              <input ref="youtubeInput" :value="shareLink" :aria-label="t('歌曲分享链接')"
                readonly spellcheck="false" @focus="($event.target as HTMLInputElement).select()">
            </template>
          </div>
        </div>
        <div v-if="destination" class="destination-guide" role="status">
          <img v-if="qr" :src="qr" width="144" height="144" :alt="t('在微信中打开歌曲的二维码')">
          <div>
            <template v-if="client">
              <template v-if="!client.isWindows || manualAddressVisible">
                <label for="creator-share-draft">{{ t(client.isWindows ? '歌曲页地址' : '待发送内容') }}</label>
                <textarea id="creator-share-draft" ref="manualLink" readonly rows="3" :value="client.draft(shareData, destination)" @focus="manualLink?.select()"></textarea>
              </template>
              <button type="button" class="wechat-open" @click="copyShareDraft">{{ t(client.isWindows ? '复制到电脑微信打开' : '复制发送内容') }}</button>
              <a class="wechat-open" :href="client.clientUrl">{{ t(client.isWindows ? '打开电脑微信' : '再次打开微信') }}</a>
            </template>
            <p v-if="handoffMessage">{{ t(handoffMessage) }}</p>
            <p v-if="client?.isWindows">{{ t(client.windowsInstruction) }}</p>
            <p v-else>{{ t('用微信扫一扫打开歌曲，再点右上角「…」选择「发送给朋友」。') }}</p>
            <p v-if="qrError">{{ t('二维码暂时无法生成，可重新点选分享方式。') }}</p>
            <p v-else-if="qr">{{ t(client?.isWindows ? '也可用手机微信扫码打开歌曲。' : '同一部手机上可截图，再从微信扫一扫的相册打开。') }}</p>
            <p v-else>{{ t('正在生成二维码…') }}</p>
          </div>
        </div>
      </template>
      <template v-if="song && link && !busy">
        <div class="international-share-actions" role="group" :aria-label="t('分享操作')">
          <button type="button" @click="copy">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m10 13 4-4m-5 6-2 2a3.5 3.5 0 0 1-5-5l4-4a3.5 3.5 0 0 1 5 0m4 1 2-2a3.5 3.5 0 0 1 5 5l-4 4a3.5 3.5 0 0 1-5 0" transform="rotate(-3 12 12)"/></svg>
            {{ t('复制本曲链接') }}
          </button>
          <button type="button" :disabled="posterBusy" @click="openPoster('download')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/></svg>
            {{ t('下载海报') }}
          </button>
          <button type="button" :disabled="sharing" @click="systemShare">
            <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="4" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="20" cy="12" r="2"/></svg>
            {{ t('更多选项') }}
          </button>
        </div>
        <div class="international-share-link">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m10 13 4-4m-5 6-2 2a3.5 3.5 0 0 1-5-5l4-4a3.5 3.5 0 0 1 5 0m4 1 2-2a3.5 3.5 0 0 1 5 5l-4 4a3.5 3.5 0 0 1-5 0" transform="rotate(-3 12 12)"/></svg>
          <input id="song-share-link" ref="linkInput" :value="shareLink" :title="shareLink" :aria-label="t('歌曲分享链接')"
            readonly spellcheck="false" @focus="($event.target as HTMLInputElement).select()">
          <button type="button" :aria-label="t('复制本曲链接')" @click="copy">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3.5" y="7.5" width="13" height="13" rx="2"/><path d="M8 7.5V5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2.5"/></svg>
          </button>
        </div>
        <p v-if="summary?.status === 'SHAREABLE'" class="international-public-note">
          <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 2 3 6v5c0 5 3.9 9.4 9 11 5.1-1.6 9-6 9-11V6l-9-4Z"/><path d="m8 12 2.6 2.6 5.4-5.4" stroke="#fff" stroke-width="1.8" fill="none" stroke-linecap="round" stroke-linejoin="round"/></svg>
          {{ t('有链接的人都可以公开试听。') }}
        </p>
        <p v-if="localShareAddress(link)" class="notice">{{ t('当前链接是本机或内网地址，朋友的设备需要能访问此地址才能收听。') }}</p>
        <section v-if="posterOpen" ref="posterSection" class="international-poster-workspace" aria-labelledby="international-poster-heading">
          <div>
            <h3 id="international-poster-heading">{{ t(posterPurpose === 'instagram' ? '分享海报到 Instagram' : '歌曲海报') }}</h3>
            <p v-if="posterPurpose === 'instagram'">{{ t(hasSystemShare
              ? '在系统分享菜单中选择 Instagram，或保存海报后上传。'
              : '保存海报后，在 Instagram 中添加。') }}</p>
            <div v-if="posterPurpose === 'customize'" class="poster-customization">
              <label>{{ t('海报歌词') }}
                <select v-model="quote">
                  <option value="">{{ t('不展示歌词') }}</option>
                  <option v-for="line in quotes" :key="line" :value="line">{{ line }}</option>
                </select>
              </label>
              <label>{{ t('作者署名（选填）') }}
                <input v-model="signature" maxlength="24" :placeholder="song.creator || t('写下你想展示的名字')">
              </label>
              <button class="international-secondary" type="button" :disabled="posterBusy" @click="makePoster">{{ t('生成海报预览') }}</button>
            </div>
            <p v-else-if="posterUrl && posterPurpose === 'download'">{{ t('也可以长按图片保存。') }}</p>
            <p v-if="posterBusy" role="status">{{ t('正在制作海报…') }}</p>
            <p v-if="posterError" class="notice error" role="alert">{{ t(posterError) }}</p>
            <div class="international-poster-actions">
              <a v-if="posterUrl" class="international-secondary" :href="posterUrl" :download="posterFilename(song.title)"
                @click="!checkLink() && $event.preventDefault()">{{ t('保存海报') }}</a>
              <button v-if="posterUrl && posterPurpose !== 'download' && hasSystemShare" type="button"
                class="international-secondary" :disabled="sharing" @click="sharePoster">{{ t(posterPurpose === 'instagram' ? '分享歌曲海报' : '分享海报图片') }}</button>
              <button v-if="posterError && posterPurpose !== 'customize'" type="button" class="international-secondary" @click="makePoster">{{ t('重试') }}</button>
            </div>
          </div>
          <img v-if="posterUrl" :src="posterUrl" :alt="t('带试听二维码的歌曲海报')">
        </section>
      </template>
      <button v-if="!busy && !summary && error" class="button" type="button" @click="refresh()">{{ t('重新读取分享状态') }}</button>
      <button v-if="!busy && canCreate && summary && !song" class="button" type="button" @click="create">{{ t('启用公开试听') }}</button>
      <p v-if="notice" class="notice" :class="{ success: notice === '复制成功' }" role="status">{{ t(notice) }}</p>
      <details ref="manage" class="song-share-manage">
        <summary>{{ t('管理分享') }}</summary>
        <template v-if="song && link && !busy">
          <p>{{ t('公开试听有效期至') }} {{ formatDate(expires) }}</p>
          <button class="international-secondary" type="button" @click="openPoster('customize')">{{ t('自定义海报') }}</button>
          <WechatSelfCard :key="sharePath" :path="sharePath" />
          <PublicSongStory :key="'story-' + song.songId" :song-id="song.songId" />
        </template>
        <p>{{ t('撤销后未购买者无法继续访问；已付费解锁者仍可下载已购买音频。') }}</p>
        <button v-if="song" class="text-button" type="button" :disabled="busy || !canCreate" @click="create">{{ t('更新分享卡片') }}</button>
        <button v-if="song" class="text-button" type="button" :disabled="busy" @click="revoke(false)">{{ t('撤销此版本分享') }}</button>
        <button class="text-button" type="button" :disabled="busy" @click="revoke(true)">{{ t('撤销本作品全部分享') }}</button>
      </details>
    </section>
  </dialog>
</template>

<style src="../../../../public/brand/share-destinations.css"></style>
<style scoped src="./share-panel-international.css"></style>
<style scoped>
.song-share-dialog { max-width: none; }
.song-share-dialog:not([open]) { display: none; }
.song-share-fallback-backdrop { position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 999; background: #38272b77; }
.song-share-dialog.dialog-fallback[open] { display: block; position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: 1000; margin: auto; }
.song-share-dialog::backdrop { background: #38272b66; backdrop-filter: blur(7px); }
.song-share-content { position: relative; }
.song-share-close { position: absolute; }
.song-share-content p { font-size: 13px; line-height: 1.7; color: #78645d; }
.song-share-content .button { display: inline-flex; align-items: center; justify-content: center; min-height: 50px; padding: 12px 20px; border: 1px solid #f93822; border-radius: 999px; background: #f93822; color: white; font-weight: 600; white-space: normal; text-align: center; }
.song-share-manage { font-size: 12px; color: #8c7268; }
.song-share-manage summary { cursor: pointer; min-height: 44px; align-content: center; }
.song-share-manage .text-button { min-height: 44px; }
.destination-guide .wechat-open { display: inline-flex; align-items: center; min-height: 44px; }
.youtube-share-actions { display: flex; flex-wrap: wrap; gap: .5rem; margin: .5rem 0; }
.youtube-share-guide input, .poster-customization input, .poster-customization select {
  box-sizing: border-box;
  display: block;
  width: 100%;
  min-width: 0;
  min-height: 44px;
  margin-top: .5rem;
  padding: .5rem;
  border: 1px solid #d6cdc6;
  border-radius: .5rem;
  font: inherit;
}
.poster-customization label { display: block; margin: .75rem 0; font-size: 13px; }
.poster-customization { margin-bottom: .75rem; }
@media (max-width: 560px) {
  .song-share-dialog, .song-share-dialog.dialog-fallback[open] { top: auto; right: 0; bottom: 0; left: 0; margin: 0 auto; }
  .youtube-share-guide input, .poster-customization input, .poster-customization select { font-size: 16px; }
}
</style>
