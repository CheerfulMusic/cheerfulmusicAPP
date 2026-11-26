<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../../../shared/i18n'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import { wechatShareEntry, type WechatCardState, type WechatDestination } from '../../works/model/song-sharing'

const props = defineProps<{ url: string; name: string; artist: string; cover: string; coverVersion?: string | null }>()
const dialog = ref<HTMLDialogElement>()
const open = ref(false)
const destination = ref<WechatDestination>()
const qr = ref(''), qrError = ref(false)
const sdkState = ref<WechatCardState>('loading')
const inWechat = /MicroMessenger/i.test(navigator.userAgent)
let qrRequest = 0
let stopPreparing: (() => void) | undefined

const link = new URL(props.url)
const friendTitle = computed(() => [props.name, props.artist].filter(Boolean).join(' · '))
const brandCover = '/brand/share-default-cover.png'
const cover = ref(props.cover)
const guide = computed(() => {
  if (destination.value === 'friend') return inWechat
    ? '点微信右上角「…」，选择「发送给朋友」。'
    : '用微信扫一扫打开歌曲，再点右上角「…」选择「发送给朋友」。'
  return inWechat
    ? '点微信右上角「…」，选择「分享到朋友圈」。'
    : '用微信扫一扫打开歌曲，再点右上角「…」选择「分享到朋友圈」。'
})

function prepareCard() {
  const sdk = window.cheerfulWechatCard
  if (!sdk) { sdkState.value = 'failed'; return }
  stopPreparing?.()
  sdkState.value = 'loading'
  const thumbnail = new URL('/api/public' + link.pathname.replace(/^\/en\//, '/') + '/thumbnail.png', link.origin)
  if (props.coverVersion) thumbnail.searchParams.set('v', props.coverVersion)
  const artwork = new URL(cover.value || brandCover, link.origin)
  const imgUrl = artwork.origin === link.origin && !artwork.pathname.startsWith('/api/')
    && artwork.pathname !== brandCover ? artwork.href : thumbnail.href
  stopPreparing = sdk.prepare({ link: link.href, imgUrl, friendTitle: friendTitle.value,
    timelineTitle: '青风音乐 Cheerful Music｜' + friendTitle.value,
    description: t('在青风音乐广场收听这首歌。') }, state => { sdkState.value = state })
}

function isSongEntry() {
  const current = new URL(location.href)
  return current.origin === link.origin
    && current.pathname.replace(/^\/en\//, '/') === link.pathname.replace(/^\/en\//, '/')
    && (!window.cheerfulWechatCard || window.cheerfulWechatCard.entryUrl === location.href.split('#')[0])
}

function show() {
  if (open.value) return
  if (inWechat && isSongEntry() && sdkState.value === 'failed') prepareCard()
  open.value = true
  if (dialog.value?.showModal) dialog.value.showModal()
  else dialog.value?.setAttribute('open', '')
}

function reset() {
  ++qrRequest
  open.value = false
  destination.value = undefined
  qr.value = ''
  qrError.value = false
}

function close() {
  if (dialog.value?.close) dialog.value.close()
  else dialog.value?.removeAttribute('open')
  reset()
}

async function choose(target: WechatDestination) {
  destination.value = target
  qr.value = ''
  qrError.value = false
  const request = ++qrRequest
  if (inWechat) {
    if (!isSongEntry()) {
      // A full page load makes this song the URL WeChat uses for JS-SDK validation.
      const entry = new URL(wechatShareEntry(link.href, target))
      if (entry.href.split('#')[0] === location.href.split('#')[0]) {
        history.replaceState(history.state, '', entry.href)
        location.reload()
      } else location.assign(entry.href)
    }
    return
  }
  try {
    const { wechatShareQr } = await import('../../works/model/song-poster')
    const image = await wechatShareQr(link.href, target)
    if (request === qrRequest) qr.value = image
  } catch {
    if (request === qrRequest) qrError.value = true
  }
}

onMounted(() => {
  if (!inWechat || !isSongEntry()) return
  prepareCard()
  const hash = location.hash
  if (hash !== '#share-friend' && hash !== '#share-timeline') return
  show()
  void choose(hash === '#share-friend' ? 'friend' : 'timeline')
})
onBeforeUnmount(() => { reset(); stopPreparing?.() })
</script>

<template>
  <button type="button" class="discover-share-trigger" aria-haspopup="dialog" @click="show">
    <NavIcon name="share" />{{ t('分享') }}
  </button>
  <Teleport to="body">
    <dialog ref="dialog" class="discover-share-dialog" aria-labelledby="discover-share-title" @close="reset">
      <header>
        <h2 id="discover-share-title">{{ t('分享歌曲卡片') }}</h2>
        <button class="discover-share-close" type="button" :aria-label="t('关闭')" @click="close">×</button>
      </header>
      <div class="discover-share-song">
        <img :src="cover || brandCover" alt="" @error="cover = brandCover">
        <div><strong>{{ name }}</strong><span>{{ artist }}</span></div>
      </div>
      <div class="share-destinations" role="group" :aria-label="t('分享到')">
        <button type="button" :aria-pressed="destination === 'friend'" @click="choose('friend')">
          <span class="destination-icon for-friend" aria-hidden="true"></span>{{ t('微信好友') }}
        </button>
        <button type="button" :aria-pressed="destination === 'timeline'" @click="choose('timeline')">
          <span class="destination-icon for-timeline" aria-hidden="true"><i></i></span>{{ t('朋友圈') }}
        </button>
      </div>
      <div v-if="destination" class="destination-guide" role="status" aria-live="polite">
        <img v-if="qr" :src="qr" :alt="t('用微信扫码')">
        <div>
          <p>{{ t(guide) }}</p>
          <p v-if="inWechat && sdkState === 'loading'">{{ t('正在准备歌曲卡片…') }}</p>
          <p v-if="inWechat && sdkState === 'failed'">{{ t('歌曲卡片暂时无法准备，请稍后重试。') }}</p>
          <p v-if="!inWechat && !qr && !qrError">{{ t('正在生成二维码…') }}</p>
          <p v-if="qr">{{ t('同一部手机上可截图，再从微信扫一扫的相册打开。') }}</p>
          <p v-if="qrError">{{ t('二维码暂时无法生成，可重新点选分享方式。') }}</p>
        </div>
      </div>
    </dialog>
  </Teleport>
</template>

<style src="../../../../public/brand/share-destinations.css"></style>
<style scoped>
.discover-share-dialog {
  box-sizing: border-box;
  width: min(420px, calc(100% - 32px));
  max-height: calc(100% - 32px);
  padding: 20px;
  border: 1px solid #dedfe0;
  border-radius: 14px;
  background: #fcfcfc;
  color: #191919;
}
.discover-share-dialog::backdrop { background: #19191965; }
.discover-share-dialog header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.discover-share-dialog h2 { margin: 0; font-size: 1.2rem; }
.discover-share-close {
  min-width: 44px;
  min-height: 44px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font-size: 1.5rem;
  cursor: pointer;
}
.discover-share-close:hover { background: #f1eeee; }
.discover-share-song {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 8px 0 16px;
  padding: 10px;
  border: 1px solid #dedfe0;
  border-radius: 10px;
  overflow-wrap: anywhere;
}
.discover-share-song img { width: 52px; height: 52px; border-radius: 6px; object-fit: cover; }
.discover-share-song span { display: block; color: #626467; font-size: .85rem; }
.discover-share-dialog .share-destinations { grid-template-columns: repeat(2, minmax(0, 1fr)); }
</style>
