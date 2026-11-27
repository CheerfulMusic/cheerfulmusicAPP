<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../../../shared/i18n'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import { isWechatBrowser, wechatShareEntry, type WechatCardState, type WechatDestination } from '../../works/model/song-sharing'
import { copyInternationalShareLink, shareInternationalSong } from '../../works/model/international-song-sharing'

const props = defineProps<{ url: string; name: string; artist: string; description?: string; cover: string; thumbnail: string }>()
const dialog = ref<HTMLDialogElement>()
const trigger = ref<HTMLButtonElement>(), linkInput = ref<HTMLInputElement>()
const open = ref(false)
const destination = ref<WechatDestination>()
const qr = ref(''), qrError = ref(false)
const sdkState = ref<WechatCardState>('loading')
const inWechat = isWechatBrowser(navigator.userAgent)
const hasSystemShare = !inWechat && typeof navigator.share === 'function'
const status = ref(''), sharing = ref(false)
let qrRequest = 0, shareRequest = 0
let stopPreparing: (() => void) | undefined

const link = new URL(props.url)
const friendTitle = computed(() => [props.name, props.artist].filter(Boolean).join(' · '))
const description = computed(() => props.description?.trim() || t('在青风音乐广场收听这首歌。'))
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
  try {
    stopPreparing = sdk.prepare({ link: link.href, imgUrl: props.thumbnail, friendTitle: friendTitle.value,
      timelineTitle: '青风音乐 Cheerful Music｜' + friendTitle.value,
      description: description.value }, state => { sdkState.value = state })
  } catch {
    sdkState.value = 'failed'
  }
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
  else {
    dialog.value?.setAttribute('open', '')
    dialog.value?.querySelector<HTMLElement>('h2')?.focus()
  }
}

function reset(restoreFocus = true) {
  const wasOpen = open.value
  ++qrRequest
  ++shareRequest
  open.value = false
  destination.value = undefined
  qr.value = ''
  qrError.value = false
  status.value = ''
  sharing.value = false
  if (wasOpen && restoreFocus) trigger.value?.focus({ preventScroll: true })
}

function close() {
  if (dialog.value?.close) dialog.value.close()
  else dialog.value?.removeAttribute('open')
  reset()
}

async function copyLink(current = () => open.value) {
  const copied = await copyInternationalShareLink(link.href, linkInput.value, current)
  if (current()) status.value = copied ? '复制成功' : '请选中链接手动复制。'
}

async function share(useSystem: boolean) {
  if (sharing.value || !open.value) return
  const request = ++shareRequest
  const current = () => open.value && shareRequest === request
  sharing.value = true
  status.value = ''
  try {
    if (useSystem && await shareInternationalSong({ title: friendTitle.value, text: description.value, url: link.href }) !== 'unavailable') return
    if (current()) await copyLink(current)
  } finally {
    if (current()) sharing.value = false
  }
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

watch(() => props.cover, next => { cover.value = next })
watch([friendTitle, description, () => props.thumbnail], () => {
  if (inWechat && isSongEntry()) prepareCard()
})

onMounted(() => {
  if (!inWechat || !isSongEntry()) return
  prepareCard()
  const hash = location.hash
  if (hash !== '#share-friend' && hash !== '#share-timeline') return
  show()
  void choose(hash === '#share-friend' ? 'friend' : 'timeline')
})
onBeforeUnmount(() => { reset(false); stopPreparing?.() })
</script>

<template>
  <button ref="trigger" type="button" class="discover-share-trigger" aria-haspopup="dialog" @click="show">
    <NavIcon name="share" />{{ t('分享') }}
  </button>
  <Teleport to="body">
    <dialog ref="dialog" class="discover-share-dialog" aria-labelledby="discover-share-title" @close="reset()" @cancel.prevent="close"
      @keydown.esc="!dialog?.showModal && close()">
      <header>
        <h2 id="discover-share-title" tabindex="-1">{{ t('分享歌曲卡片') }}</h2>
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
      <div class="discover-share-link">
        <input ref="linkInput" :value="link.href" :aria-label="t('歌曲分享链接')" readonly
          @focus="($event.target as HTMLInputElement).select()">
        <div>
          <button type="button" :disabled="sharing" @click="share(false)">{{ t('复制本曲链接') }}</button>
          <button v-if="hasSystemShare" type="button" :disabled="sharing" @click="share(true)">{{ t('更多选项') }}</button>
        </div>
        <p v-if="status" role="status">{{ t(status) }}</p>
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
.discover-share-link { display: grid; gap: 8px; margin-top: 16px; }
.discover-share-link input { min-width: 0; min-height: 44px; padding: 8px; border: 1px solid #dedfe0; border-radius: 8px; background: white; color: inherit; font: inherit; text-overflow: ellipsis; }
.discover-share-link > div { display: flex; flex-wrap: wrap; gap: 8px; }
.discover-share-link button { min-height: 44px; padding: 6px 10px; border: 1px solid #dedfe0; border-radius: 8px; background: white; color: inherit; font: inherit; cursor: pointer; }
.discover-share-link p { margin: 0; }
</style>
