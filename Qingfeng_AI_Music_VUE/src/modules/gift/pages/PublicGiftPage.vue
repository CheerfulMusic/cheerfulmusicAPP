<script setup lang="ts">
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import AudioPreparation from '../../../shared/player/AudioPreparation.vue'
import { t, formatDate } from '../../../shared/i18n/index'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { requestJson, apiUrl } from '../../../shared/http/api-client'
import { usePlayer } from '../../../shared/player/store'
import { downloadSong } from '../../works/api/download'
import { track as trackEvent } from '../../../shared/analytics/track'
import { takeInitialGift, type Gift } from '../model/initial-gift'

const props = defineProps<{ giftToken?: string }>()
const route = useRoute(), gift = ref<Gift>(), loading = ref(false), error = ref(''), player = usePlayer()
const token = computed(() => props.giftToken || String(route.params.token))
const publicPage = computed(() => route.name === 'gift' && !props.giftToken)
const path = () => `public/gifts/${encodeURIComponent(token.value)}`
const audio = () => apiUrl(path() + '/audio')
const artwork = computed(() => gift.value?.hasCover ? apiUrl(path() + '/cover') : '/brand/share-default-cover.png')
const track = computed(() => gift.value ? {
  id: `gift:${token.value}`, title: gift.value.title, cover: artwork.value, audio: audio(), provenance: gift.value.audioOrigin,
} : undefined)

const unlocked = ref(false)
async function download() {
  const id = token.value
  if (await downloadSong(path() + '/download', audio() + '?download=true', `${gift.value?.title || 'song'}.mp3`)
      && token.value === id) unlocked.value = true
}

let sequence = 0
let playbackStarted: number | undefined
watch(token, async id => {
  const turn = ++sequence
  const started = performance.now()
  playbackStarted = undefined
  unlocked.value = false
  loading.value = true
  error.value = ''
  gift.value = undefined
  try {
    if (!/^[A-Za-z0-9_-]{43}$/.test(id)) throw new Error('这份礼物不存在、已过期或已被撤销')
    const initial = takeInitialGift(id)
    const result = initial ?? await requestJson<Gift>(path())
    if (turn !== sequence) return
    gift.value = result
    loading.value = false
    await nextTick()
    if (turn === sequence && publicPage.value) {
      const preview = document.getElementById('qf-gift-preview')
      const earlyAudio = preview?.querySelector('audio')
      if (preview && earlyAudio && track.value && player.adoptGiftPreview(track.value, earlyAudio)) {
        preview.hidden = true
        earlyAudio.controls = false
      } else {
        if (earlyAudio && !earlyAudio.paused) earlyAudio.pause()
        preview?.remove()
      }
      trackEvent('gift_card_ready', { mode: initial ? 'inline' : 'fetch', durationMs: Math.round(performance.now() - started) }, '/gift/:token')
    }
  } catch (cause) {
    if (turn !== sequence) return
    if (publicPage.value) document.getElementById('qf-gift-preview')?.remove()
    error.value = cause instanceof Error ? cause.message : '礼物暂时无法打开'
    if (player.current?.id === `gift:${id}`) player.reset()
  } finally {
    if (turn === sequence) loading.value = false
  }
}, { immediate: true })

function play() {
  if (!track.value) return
  const alreadyActive = player.current?.id === track.value.id && (player.playing || player.starting)
  playbackStarted = alreadyActive ? undefined : performance.now()
  void player.toggle(track.value)
}

watch(() => [player.playing, player.starting, player.error], () => {
  if (playbackStarted === undefined || player.current?.id !== track.value?.id) return
  if (!player.error && (!player.playing || player.starting)) return
  const durationMs = Math.round(performance.now() - playbackStarted)
  playbackStarted = undefined
  if (publicPage.value) trackEvent('gift_play_result', { result: player.error ? 'error' : 'playing', durationMs }, '/gift/:token')
})
</script>
<template><p v-if="loading" class="notice" role="status">{{ t('正在打开这份音乐礼物…') }}</p><section v-else-if="error" class="gift-unavailable"><span>{{ t('青风寄语') }}</span><h1>{{ t('这份心意，暂时无法打开。') }}</h1><p class="notice error" role="alert">{{ t(error) }}</p><p>{{ t('链接可能已经过期或被撤销，也可以向送礼人索取新的链接。') }}</p><RouterLink class="button" to="/">{{ t('回到青风音乐') }}</RouterLink></section><section v-else-if="gift" class="live-gift"><div class="live-gift-intro"><span>{{ t('一份可以被听见的心意') }}</span><h1 v-if="gift.recipient">{{ t('亲爱的{recipient}，', { recipient: gift.recipient }) }}<br>{{ t('这首歌送给你。') }}</h1><h1 v-else>{{ t('听见这首歌里的故事。') }}</h1><p>{{ t('有些话，想用一首歌慢慢说给你听。') }}</p></div><div class="live-gift-card"><div class="live-gift-cover"><img :src="artwork" :alt="t('歌曲封面')"><blockquote v-if="gift.message">“{{ gift.message }}”</blockquote><span>{{ t('带着心意，送给你。') }}</span></div><div class="live-gift-content"><span class="origin-tag">{{ gift.audioOrigin === 'IMPORTED' ? t("导入音频 · 非AI生成") : t("AI生成作品") }}</span><h2>{{ gift.title }}</h2><div class="live-gift-play"><button class="p-gold-play" :aria-label="player.current?.id === `gift:${token}` && (player.playing||player.starting) ? t('暂停礼物音频') : t('播放礼物音频')" @click="play"><PlaybackIcon :playing="player.current?.id===`gift:${token}`&&(player.playing||player.starting)"/></button><p>{{ t('点击，听见这份心意。') }}</p></div><AudioPreparation v-if="track" :track="track"/><div class="live-gift-lyrics"><span>{{ t('这首歌里的话') }}</span><pre>{{ gift.lyrics }}</pre></div><button class="detail-download" @click="download">{{ unlocked?t('免费下载'):t('下载') }} ↓</button></div></div><p class="live-gift-expiry">{{ t('链接有效期至') }} {{ formatDate(gift.expiresAt) }} {{ t('· 送礼人可提前撤销分享') }}</p><RouterLink class="p-button" to="/create">{{ t('我也要创作一首歌') }} <span>↗</span></RouterLink></section></template>
