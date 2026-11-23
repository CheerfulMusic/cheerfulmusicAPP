<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadPublicTrack, shareNativeMusic, tokenFromSongUrl, type PublicTrack } from './wechat-music'
import type { WechatDestination } from '../../modules/works/model/song-sharing'
import BrandLogo from '../../shared/ui/BrandLogo.vue'
const route = useRoute(), router = useRouter()
const input = ref(''), track = ref<PublicTrack>(), error = ref(''), notice = ref('')
const loading = ref(false), sending = ref(false)
let turn = 0
let playCounted = false
function countPlay() {
  if (!track.value || playCounted) return
  playCounted = true
  void fetch(new URL('play', track.value.audio), { method: 'POST', credentials: 'omit' }).catch(() => {})
}
watch(() => route.params.token, async token => {
  playCounted = false
  const request = ++turn
  track.value = undefined; error.value = ''; notice.value = ''; sending.value = false; loading.value = !!token
  if (typeof token !== 'string' || !token) return
  try {
    const song = await loadPublicTrack(token)
    if (request === turn) track.value = song
  } catch (cause) { if (request === turn) error.value = cause instanceof Error ? cause.message : '无法打开歌曲。' }
  finally { if (request === turn) loading.value = false }
}, { immediate: true })
function listen() {
  const token = tokenFromSongUrl(input.value)
  if (!token) { error.value = '请粘贴 cheerfulmusic.com 的公开歌曲链接。'; return }
  input.value = ''
  router.push({ name: 'native-listen', params: { token } })
}
async function share(destination: WechatDestination) {
  if (!track.value || sending.value) return
  const request = turn; sending.value = true; error.value = ''; notice.value = ''
  try {
    if (await shareNativeMusic(track.value.id, destination, () => request === turn) && request === turn)
      notice.value = '已交给微信，请在微信中完成分享。'
  } catch (cause) { if (request === turn) error.value = cause instanceof Error ? cause.message : '暂时无法打开微信。' }
  finally { if (request === turn) sending.value = false }
}
onBeforeUnmount(() => { turn++ })
</script>
<template>
  <main class="native-listening">
    <header><BrandLogo compact/><RouterLink to="/works">我的作品</RouterLink></header>
    <section v-if="track" :key="track.id" class="track">
      <img :src="track.thumbnail" :alt="track.title + '封面'">
      <h1>{{ track.title }}</h1><p>{{ track.creator }}</p>
      <audio :src="track.audio" controls playsinline preload="metadata" @playing="countPlay" @ended="playCounted=false" :aria-label="track.title"/>
      <div class="channels"><button type="button" :disabled="sending" @click="share('friend')">微信好友</button><button type="button" :disabled="sending" @click="share('timeline')">朋友圈</button></div>
      <details v-if="track.lyrics"><summary>歌词</summary><p class="lyrics">{{ track.lyrics }}</p></details>
    </section>
    <form @submit.prevent="listen">
      <label for="public-song">打开公开歌曲</label>
      <input id="public-song" v-model="input" type="url" placeholder="粘贴青风音乐歌曲链接" required>
      <button type="submit">打开歌曲</button>
    </form>
    <p v-if="loading || sending || notice" role="status">{{ loading ? '正在加载歌曲…' : sending ? '正在准备音乐卡片…' : notice }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </main>
</template>
<style scoped>
.native-listening{max-width:32rem;margin:auto;padding:max(1.4rem,env(safe-area-inset-top)) 1.4rem 3rem;color:#26213e}
header,.channels{display:flex;align-items:center;justify-content:space-between;gap:1rem}header{margin-bottom:2rem}
.track img{display:block;width:min(100%,18rem);aspect-ratio:1;object-fit:cover;margin:auto;border-radius:1rem}
h1{font-size:1.65rem;line-height:1.25;margin:1.3rem 0 .35rem}.track p{margin:.35rem 0 1rem}.track audio{width:100%}
.channels{margin:1.1rem 0}.channels button{flex:1}button{border:0;border-radius:.7rem;background:#433687;color:white;padding:.75rem 1rem;font:inherit}
button:disabled{opacity:.55}form{display:grid;gap:.6rem;margin-top:2rem}label,summary{font-weight:650}input{width:100%;box-sizing:border-box;padding:.8rem;border-radius:.65rem;border:1px solid #aea7c4;font:inherit}
.lyrics{white-space:pre-line;line-height:1.9}[role=alert]{color:#8d2542}
</style>
