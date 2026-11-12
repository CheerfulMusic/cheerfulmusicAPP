<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { platform } from '../../../platform'
import { apiUrl } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
import {
  getArtistProfile, ownerAvatarUrl, ownerTrack, removeSongFromArtist, saveArtistProfile,
  setArtistVisibility, type ArtistProfile, type ArtistRelease,
} from '../api/artist'
import ArtistAvatar from '../components/ArtistAvatar.vue'
import ArtistHome from '../components/ArtistHome.vue'

const router = useRouter()
const profile = ref<ArtistProfile>()
const stageName = ref('')
const tagline = ref('')
const avatar = ref<File>()
const localAvatar = ref('')
const removeAvatar = ref(false)
const preview = ref(false)
const loading = ref(true)
const busy = ref(false)
const notice = ref('')
const error = ref('')
const previewHeading = ref<HTMLElement>()
const editHeading = ref<HTMLElement>()
const linkInput = ref<HTMLInputElement>()
const showLink = ref(false)
const avatarSrc = computed(() => localAvatar.value || (removeAvatar.value ? undefined
  : profile.value?.avatarKey ? ownerAvatarUrl(profile.value.avatarKey) : undefined))
const dirty = computed(() => Boolean(avatar.value || removeAvatar.value
  || stageName.value.trim() !== profile.value?.stageName || tagline.value.trim() !== profile.value?.tagline))
const tracks = computed(() => profile.value?.songs.map(ownerTrack) || [])
const homeRoute = computed(() => ({ name: 'artist-home', params: { id: profile.value?.publicId || '' } }))
const link = computed(() => {
  if (!profile.value?.publicId) return ''
  if (platform.kind !== 'web') return new URL(`/artist/${encodeURIComponent(profile.value.publicId)}`, apiUrl('public/system/info')).href
  return new URL(router.resolve(homeRoute.value).href, window.location.origin).href
})

function clearAvatarDraft() {
  if (localAvatar.value) URL.revokeObjectURL(localAvatar.value)
  localAvatar.value = ''
  avatar.value = undefined
}
function apply(value: ArtistProfile) {
  profile.value = value
  stageName.value = value.stageName
  tagline.value = value.tagline
  removeAvatar.value = false
  clearAvatarDraft()
}
function chooseAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    error.value = '请选择 5 MB 以内的 JPG 或 PNG 图片。'
    return
  }
  clearAvatarDraft()
  error.value = ''
  removeAvatar.value = false
  avatar.value = file
  localAvatar.value = URL.createObjectURL(file)
}
function useDefaultAvatar() {
  clearAvatarDraft()
  removeAvatar.value = Boolean(profile.value?.avatarKey)
}
function report(cause: unknown, fallback: string) {
  error.value = cause instanceof Error ? cause.message : fallback
}
async function load() {
  loading.value = true
  error.value = ''
  try { apply(await getArtistProfile()) }
  catch (cause) { report(cause, '暂时无法读取资料，请重试。') }
  finally { loading.value = false }
}
async function save() {
  if (busy.value || !dirty.value) return
  notice.value = ''
  error.value = ''
  busy.value = true
  try {
    apply(await saveArtistProfile(stageName.value.trim(), tagline.value.trim(), avatar.value, removeAvatar.value))
    notice.value = profile.value?.published ? '歌手主页已更新。' : '资料已保存。'
  } catch (cause) { report(cause, '资料保存失败，请重试。') }
  finally { busy.value = false }
}
async function changeVisibility(published: boolean) {
  if (busy.value || (published && (dirty.value || !profile.value?.stageName))) return
  if (!published && !window.confirm(t('隐藏后，访客将暂时无法访问主页和主页上的歌曲。确定隐藏吗？'))) return
  notice.value = ''
  error.value = ''
  busy.value = true
  try {
    profile.value = await setArtistVisibility(published)
    notice.value = published ? '歌手主页已发布。' : '歌手主页已隐藏。'
    showLink.value = false
    if (published) await showPreview(false)
  } catch (cause) { report(cause, '主页状态更新失败，请重试。') }
  finally { busy.value = false }
}
async function remove(song: ArtistRelease) {
  if (busy.value || !window.confirm(t('从主页移出《{title}》？', { title: song.title }))) return
  notice.value = ''
  error.value = ''
  busy.value = true
  try {
    profile.value = await removeSongFromArtist(song.workId)
    notice.value = '歌曲已移出主页。'
  } catch (cause) { report(cause, '暂时无法移出歌曲，请重试。') }
  finally { busy.value = false }
}
async function copyLink() {
  showLink.value = true
  try {
    await navigator.clipboard.writeText(link.value)
    notice.value = '主页链接已复制。'
  } catch {
    await nextTick()
    linkInput.value?.focus()
  }
}
async function showPreview(value: boolean) {
  preview.value = value
  await nextTick()
  if (value) previewHeading.value?.focus()
  else editHeading.value?.focus()
}
onMounted(load)
onBeforeUnmount(clearAvatarDraft)
</script>

<template>
  <div class="my-artist">
    <p v-if="notice" class="notice" role="status">{{ t(notice) }}</p>
    <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
    <p v-if="loading" class="notice" role="status">{{ t('正在读取资料…') }}</p>
    <button v-else-if="!profile" type="button" class="button secondary" @click="load">{{ t('重试') }}</button>
    <template v-else-if="preview">
      <div ref="previewHeading" class="artist-preview-bar" tabindex="-1">
        <button type="button" class="text-button" @click="showPreview(false)">{{ t('返回我的资料') }}</button>
        <span>{{ t('访客视角预览') }}</span>
      </div>
      <ArtistHome :name="stageName" :tagline="tagline" :avatar="avatarSrc" :tracks="tracks" />
      <div v-if="!profile.published" class="artist-preview-publish">
        <p v-if="dirty || !profile.stageName">{{ t('请先保存艺名和资料，再发布主页。') }}</p>
        <p v-else>{{ t('发布后，访客可以查看这些资料和试听上方的歌曲。') }}</p>
        <button class="button" type="button" :disabled="busy || dirty || !profile.stageName" @click="changeVisibility(true)">{{ t('发布歌手主页') }}</button>
      </div>
    </template>
    <template v-else>
      <header class="profile-heading">
        <h1 ref="editHeading" tabindex="-1">{{ t('我的资料') }}</h1>
        <RouterLink to="/account" class="text-button">{{ t('账号与登录') }}</RouterLink>
      </header>
      <div class="artist-editor-grid">
        <form class="artist-editor" @submit.prevent="save">
          <fieldset :disabled="busy">
            <div class="artist-avatar-field">
              <ArtistAvatar :name="stageName" :src="avatarSrc" />
              <div>
                <label class="artist-upload">
                  {{ t('更换头像') }}
                  <input type="file" accept="image/jpeg,image/png" :aria-label="t('更换头像')" @change="chooseAvatar">
                </label>
                <button v-if="avatarSrc" type="button" class="text-button" @click="useDefaultAvatar">{{ t('使用默认头像') }}</button>
                <small>{{ t('JPG 或 PNG，最多 5 MB') }}</small>
              </div>
            </div>
            <label class="field">
              {{ t('艺名') }}
              <input v-model="stageName" maxlength="30" :required="profile.published" :placeholder="t('填写你想在主页显示的名字')">
            </label>
            <label class="field">
              {{ t('个性签名') }}
              <textarea v-model="tagline" rows="3" maxlength="120" :placeholder="t('写一句话介绍自己（选填）')" />
            </label>
            <button type="submit" class="button" :disabled="!dirty">
              {{ t(busy ? '正在保存…' : profile.published ? '保存并更新主页' : '保存资料') }}
            </button>
          </fieldset>
        </form>
        <section class="artist-publication" aria-labelledby="profile-publication-title">
          <div class="artist-publication-header">
            <h2 id="profile-publication-title">{{ t('歌手主页') }}</h2>
            <span :class="{ published: profile.published }">{{ t(profile.published ? '已公开' : '未公开') }}</span>
          </div>
          <p>{{ t(profile.published ? '访客可通过链接查看资料和试听你选中的歌曲。' : '发布主页后，访客可以通过链接访问。') }}</p>
          <div class="artist-publication-actions">
            <button type="button" class="button secondary" :disabled="busy" @click="showPreview(true)">{{ t('预览歌手主页') }}</button>
            <template v-if="profile.published">
              <button type="button" class="text-button" :disabled="busy" @click="copyLink">{{ t('复制主页链接') }}</button>
              <RouterLink class="text-button" :to="homeRoute">{{ t('查看主页') }}</RouterLink>
              <button type="button" class="text-button" :disabled="busy" @click="changeVisibility(false)">{{ t('隐藏主页') }}</button>
            </template>
          </div>
          <label v-if="showLink" class="field">
            {{ t('主页链接') }}
            <input ref="linkInput" readonly :value="link" @focus="linkInput?.select()">
          </label>
        </section>
      </div>
      <section class="artist-manage" aria-labelledby="artist-manage-title">
        <div class="artist-manage-header">
          <h2 id="artist-manage-title">{{ t('主页作品') }}</h2>
          <RouterLink class="text-button" to="/works">{{ t('去我的音乐选歌') }}</RouterLink>
        </div>
        <p v-if="!profile.songs.length">{{ t('打开一首已完成的歌曲，选择「发布歌曲」即可加入主页。') }}</p>
        <ul v-else>
          <li v-for="(song, index) in profile.songs" :key="song.id">
            <img :src="tracks[index]?.cover" alt="" loading="lazy">
            <strong>{{ song.title }}</strong>
            <div class="artist-song-actions">
              <RouterLink class="text-button" :to="{ name: 'work-detail', params: { id: song.workId } }">{{ t('去作品页更换版本') }}</RouterLink>
              <button type="button" class="text-button" :disabled="busy" @click="remove(song)">{{ t('移出主页') }}</button>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.my-artist { max-width: 1040px; margin-inline: auto; padding-bottom: 24px; }
.profile-heading, .artist-manage-header, .artist-publication-header, .artist-preview-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
}
.profile-heading { margin: 0 0 26px; }
.profile-heading h1 { font-size: clamp(32px, 5vw, 50px); margin: 0; }
.artist-editor-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); align-items: start; gap: clamp(20px, 5vw, 52px); }
.artist-editor fieldset { padding: 0; border: 0; margin: 0; min-width: 0; }
.artist-editor .field { margin-block: 20px; }
.artist-avatar-field { display: flex; align-items: center; gap: 16px; }
.artist-avatar-field small { display: block; color: var(--muted); margin-top: 8px; }
.artist-upload { display: inline-block; position: relative; cursor: pointer; text-decoration: underline; text-underline-offset: 3px; font-weight: 600; }
.artist-upload:focus-within { outline: 2px solid var(--accent); outline-offset: 4px; }
.artist-upload input { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: pointer; }
.artist-avatar-field button { margin-inline-start: 12px; }
.artist-publication { background: var(--surface); border: 1px solid var(--line); border-radius: 16px; padding: clamp(18px, 4vw, 26px); }
.artist-publication h2, .artist-manage h2 { margin: 0; font-size: 21px; }
.artist-publication-header span { border-radius: 30px; background: var(--accent-soft); padding: 4px 10px; color: var(--muted); font-size: .82rem; }
.artist-publication-header .published { background: #e2efe8; color: #285d43; }
.artist-publication p, .artist-manage p, .artist-preview-publish p { color: var(--muted); margin: 12px 0 18px; }
.artist-publication-actions, .artist-song-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px 14px; }
.artist-publication .field { margin-block: 16px 0; }
.artist-manage { margin-top: 42px; }
.artist-manage ul { padding: 0; margin-block: 14px 0; list-style: none; border-top: 1px solid var(--line); }
.artist-manage li { display: grid; grid-template-columns: 46px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }
.artist-manage img { width: 46px; height: 46px; border-radius: 6px; object-fit: cover; }
.artist-preview-bar, .artist-preview-publish { max-width: 920px; margin: 0 auto 14px; }
.artist-preview-bar span { color: var(--muted); }
@media (max-width: 680px) {
  .artist-editor-grid { grid-template-columns: minmax(0, 1fr); gap: 24px; }
  .artist-manage li { grid-template-columns: 46px minmax(0, 1fr); }
  .artist-song-actions { grid-column: 2; }
}
</style>
