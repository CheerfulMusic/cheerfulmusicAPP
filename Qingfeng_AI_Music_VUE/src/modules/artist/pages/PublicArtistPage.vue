<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
import { getPublicArtist, publicAvatarUrl, publicTrack, type PublicArtist } from '../api/artist'
import ArtistHome from '../components/ArtistHome.vue'

const route = useRoute()
const id = computed(() => String(route.params.id || ''))
const artist = ref<PublicArtist>()
const loading = ref(true)
const missing = ref(false)
const error = ref('')
const avatar = computed(() => artist.value?.avatarKey ? publicAvatarUrl(id.value, artist.value.avatarKey) : undefined)
const tracks = computed(() => artist.value?.songs.map(song => publicTrack(id.value, song)) || [])
let turn = 0

async function load() {
  const request = ++turn
  artist.value = undefined
  loading.value = true
  missing.value = false
  error.value = ''
  try {
    const found = await getPublicArtist(id.value)
    if (request === turn) artist.value = found
  } catch (cause) {
    if (request !== turn) return
    if (cause instanceof ApiError && cause.status === 404) missing.value = true
    else error.value = '暂时无法加载歌手主页，请重试。'
  } finally {
    if (request === turn) loading.value = false
  }
}
watch(id, load, { immediate: true })
</script>

<template>
  <ArtistHome v-if="artist" :name="artist.stageName" :tagline="artist.tagline" :avatar="avatar" :tracks="tracks" />
  <p v-else-if="loading" class="notice" role="status">{{ t('正在加载歌手主页…') }}</p>
  <section v-else class="module-card">
    <h1>{{ t(missing ? '该主页暂不可访问' : error) }}</h1>
    <button v-if="!missing" type="button" class="button secondary" @click="load">{{ t('重试') }}</button>
    <RouterLink v-else class="button secondary" to="/">{{ t('返回首页') }}</RouterLink>
  </section>
</template>
