<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import {
  artistHomeUrl, getArtistProfile, ownerArtistCoverUrl, ownerAvatarUrl, ownerTrack, removeSongFromArtist, saveArtistProfile,
  setArtistVisibility, withdrawSongFromDiscover, type ArtistProfile, type ArtistRelease,
} from '../api/artist'
import ArtistAvatar from '../components/ArtistAvatar.vue'
import ArtistHome from '../components/ArtistHome.vue'
import ProfileAccount from '../components/ProfileAccount.vue'
import ProfileWorks from '../components/ProfileWorks.vue'
import PublishSongDialog from '../components/PublishSongDialog.vue'

interface ImageDraft { file: File; url: string }

const profile = ref<ArtistProfile>()
const route = useRoute()
const stageName = ref('')
const tagline = ref('')
const avatar = ref<ImageDraft>()
const cover = ref<ImageDraft>()
const removeAvatar = ref(false)
const removeCover = ref(false)
const featuredReleaseId = ref<string | null>(null)
const editing = ref(false)
const preview = ref(false)
const loading = ref(true)
const busy = ref(false)
const discoverRelease = ref<ArtistRelease>()
const discoverCover = computed(() => discoverRelease.value ? ownerTrack(discoverRelease.value).cover : '')
const notice = ref('')
const error = ref('')
const previewHeading = ref<HTMLElement>()
const editHeading = ref<HTMLElement>()
const linkInput = ref<HTMLInputElement>()
const showLink = ref(false)
const workCount = ref<number | null>(null)
const editButton = ref<HTMLButtonElement>()
const nameInput = ref<HTMLInputElement>()
const avatarInput = ref<HTMLInputElement>()
const coverInput = ref<HTMLInputElement>()
const waveform = [7, 15, 34, 18, 9, 17, 29, 12, 22, 53, 16, 24, 10, 33, 17, 8]
const avatarSrc = computed(() => avatar.value?.url || (removeAvatar.value ? undefined
  : profile.value?.avatarKey ? ownerAvatarUrl(profile.value.avatarKey) : undefined))
const coverSrc = computed(() => cover.value?.url || (removeCover.value ? undefined
  : profile.value?.coverKey ? ownerArtistCoverUrl(profile.value.coverKey) : undefined))
const savedFeatured = computed(() => profile.value?.songs.find(song => song.id === profile.value?.featuredReleaseId))
const previewFeatured = computed(() => editing.value
  ? profile.value?.songs.find(song => song.id === featuredReleaseId.value)
  : savedFeatured.value)
const featuredChanged = computed(() => featuredReleaseId.value !== (savedFeatured.value?.id ?? null))
const dirty = computed(() => editing.value && Boolean(
  avatar.value || removeAvatar.value || cover.value || removeCover.value
  || stageName.value.trim() !== profile.value?.stageName || tagline.value.trim() !== profile.value?.tagline
  || featuredChanged.value,
))
const tracks = computed(() => profile.value?.songs.map(ownerTrack) || [])
const homeRoute = computed(() => ({ name: 'artist-home', params: { id: profile.value?.publicId || '' } }))
const link = computed(() => profile.value?.publicId ? artistHomeUrl(profile.value.publicId) : '')
const returnToSong = computed(() => {
  const id = route.query.publishWork
  return typeof id === 'string' && /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i.test(id)
    ? { name: 'work-detail', params: { id }, query: { show: 'publication' } } : null
})

function revokeImage(draft?: ImageDraft) {
  if (draft) URL.revokeObjectURL(draft.url)
}
function clearImageDrafts() {
  revokeImage(avatar.value)
  revokeImage(cover.value)
  avatar.value = undefined
  cover.value = undefined
}
function updateProfile(value: ArtistProfile) {
  const keepFeaturedDraft = editing.value && featuredChanged.value && (
    featuredReleaseId.value === null || value.songs.some(song => song.id === featuredReleaseId.value)
  )
  profile.value = value
  if (!keepFeaturedDraft) featuredReleaseId.value = savedFeatured.value?.id ?? null
}
function updateDiscoverRelease(song: ArtistRelease) {
  const current = profile.value
  if (!current) return
  updateProfile({ ...current, songs: current.songs.map(item => item.id === song.id ? song : item) })
}
function apply(value: ArtistProfile) {
  profile.value = value
  stageName.value = value.stageName
  tagline.value = value.tagline
  removeAvatar.value = false
  removeCover.value = false
  featuredReleaseId.value = savedFeatured.value?.id ?? null
  clearImageDrafts()
}
function chooseImage(event: Event, kind: 'avatar' | 'cover') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    error.value = '请选择 5 MB 以内的 JPG 或 PNG 图片。'
    return
  }
  const draft = kind === 'avatar' ? avatar : cover
  const remove = kind === 'avatar' ? removeAvatar : removeCover
  const url = URL.createObjectURL(file)
  revokeImage(draft.value)
  draft.value = { file, url }
  remove.value = false
  error.value = ''
}
function useDefaultAvatar() {
  revokeImage(avatar.value)
  avatar.value = undefined
  removeAvatar.value = Boolean(profile.value?.avatarKey)
}
function useDefaultCover() {
  revokeImage(cover.value)
  cover.value = undefined
  removeCover.value = Boolean(profile.value?.coverKey)
}
async function setEditing(value: boolean) {
  editing.value = value
  await nextTick()
  const target = value ? nameInput.value : editButton.value
  target?.focus()
}
function editProfile() {
  if (!profile.value || busy.value) return
  apply(profile.value)
  notice.value = ''
  error.value = ''
  void setEditing(true)
}
function cancelEdits() {
  if (profile.value) apply(profile.value)
  error.value = ''
  void setEditing(false)
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
    apply(await saveArtistProfile(stageName.value.trim(), tagline.value.trim(), avatar.value?.file, removeAvatar.value, {
      cover: cover.value?.file,
      removeCover: removeCover.value,
      featuredReleaseId: featuredChanged.value ? featuredReleaseId.value : undefined,
    }))
    notice.value = profile.value?.published ? '歌手主页已更新。' : '资料已保存。'
  } catch (cause) {
    report(cause, '资料保存失败，请重试。')
    if (cause instanceof ApiError && cause.code === 'INVALID_ARTIST_FEATURED_RELEASE') {
      try { updateProfile(await getArtistProfile()) } catch { /* 保留草稿，仍可手动选择最新发布后重试。 */ }
    }
    return
  }
  finally { busy.value = false }
  await setEditing(false)
}
async function changeVisibility(published: boolean) {
  if (busy.value || (published && (dirty.value || !profile.value?.stageName))) return
  if (!published && !window.confirm(t('隐藏主页后，访客将无法访问主页和其中的歌曲，已发布到「发现」的歌曲也会撤回。确定隐藏吗？'))) return
  notice.value = ''
  error.value = ''
  busy.value = true
  try {
    updateProfile(await setArtistVisibility(published))
    notice.value = published ? '歌手主页已发布。' : '歌手主页已隐藏。'
    showLink.value = false
    if (published) await showPreview(false)
  } catch (cause) { report(cause, '主页状态更新失败，请重试。') }
  finally { busy.value = false }
}
async function remove(song: ArtistRelease) {
  const prompt = song.onDiscover
    ? '《{title}》将从歌手主页和「发现」移除，原作品仍保存在「我的音乐」。确定移出吗？'
    : '从主页移出《{title}》？'
  if (busy.value || !window.confirm(t(prompt, { title: song.title }))) return
  notice.value = ''
  error.value = ''
  busy.value = true
  try {
    updateProfile(await removeSongFromArtist(song.workId))
    notice.value = '歌曲已移出主页。'
  } catch (cause) { report(cause, '暂时无法移出歌曲，请重试。') }
  finally { busy.value = false }
}
async function withdraw(song: ArtistRelease) {
  if (busy.value || !window.confirm(t('从「发现」撤回《{title}》？访客仍可在你的歌手主页试听。', { title: song.title }))) return
  notice.value = ''
  error.value = ''
  busy.value = true
  try {
    updateProfile(await withdrawSongFromDiscover(song.workId))
    notice.value = '歌曲已从发现撤回。'
  } catch (cause) { report(cause, '暂时无法撤回歌曲，请重试。') }
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
onBeforeUnmount(clearImageDrafts)
</script>

<template>
  <div class="my-artist" :class="{ 'showing-home-preview': preview }">
    <p v-if="notice" class="notice" role="status">{{ t(notice) }}</p>
    <p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
    <p v-if="loading" class="notice" role="status">{{ t('正在读取资料…') }}</p>
    <button v-else-if="!profile" type="button" class="button secondary" @click="load">{{ t('重试') }}</button>
    <template v-else-if="preview">
      <div ref="previewHeading" class="artist-preview-bar" tabindex="-1">
        <button type="button" class="text-button" @click="showPreview(false)">{{ t('返回我的资料') }}</button>
        <span>{{ t('访客视角预览') }}</span>
      </div>
      <ArtistHome
        :name="editing ? stageName : profile.stageName"
        :tagline="editing ? tagline : profile.tagline"
        :avatar="avatarSrc"
        :cover="coverSrc"
        :tracks="tracks"
        :featured-track-id="previewFeatured?.assetId"
      />
      <div v-if="!profile.published" class="artist-preview-publish">
        <p v-if="dirty || !profile.stageName">{{ t('请先保存艺名和资料，再发布主页。') }}</p>
        <p v-else>{{ t('发布后，访客可以查看这些资料和试听上方的歌曲。') }}</p>
        <button class="button" type="button" :disabled="busy || dirty || !profile.stageName" @click="changeVisibility(true)">{{ t('发布歌手主页') }}</button>
      </div>
    </template>
    <template v-else>
      <RouterLink v-if="returnToSong" :to="returnToSong" class="button secondary profile-return-song">
        {{ t(profile.published ? '返回歌曲继续公开' : '返回歌曲') }}
      </RouterLink>
      <header class="profile-heading">
        <h1 ref="editHeading" tabindex="-1">{{ t('我的资料') }}</h1>
        <p>{{ t('管理个人信息、作品与账号设置') }}</p>
      </header>
      <section class="profile-card profile-summary" aria-labelledby="profile-summary-name">
        <div class="profile-avatar-wrap">
          <ArtistAvatar v-if="avatarSrc" :name="stageName" :src="avatarSrc" />
          <span v-else class="profile-avatar-default" aria-hidden="true"><img src="/brand/logo-symbol.svg" alt=""></span>
          <template v-if="editing">
            <input ref="avatarInput" class="profile-avatar-input" type="file" accept="image/jpeg,image/png" tabindex="-1" :aria-label="t('更换头像')" :disabled="busy" @change="chooseImage($event, 'avatar')">
            <button class="profile-avatar-camera" type="button" :aria-label="t('更换头像')" :title="t('更换头像')" :disabled="busy" @click="avatarInput?.click()">
              <span aria-hidden="true"></span>
            </button>
          </template>
        </div>
        <div class="profile-summary-copy">
          <h2 id="profile-summary-name">{{ profile.stageName || t('你的艺名') }}</h2>
          <p v-if="profile.tagline">{{ profile.tagline }}</p>
          <dl class="profile-stats">
            <div v-if="workCount !== null"><dt>{{ t('作品') }}</dt><dd>{{ workCount }}</dd></div>
            <div><dt>{{ t('主页作品') }}</dt><dd>{{ profile.songs.length }}</dd></div>
          </dl>
        </div>
        <div class="profile-summary-actions">
          <div class="profile-wave" aria-hidden="true"><span v-for="(height, index) in waveform" :key="index" :style="{ height: height + 'px' }"></span></div>
          <button v-if="!editing" ref="editButton" type="button" class="profile-pill profile-edit-button" :disabled="busy" @click="editProfile">{{ t('编辑资料') }}</button>
        </div>
      </section>
      <div class="profile-columns">
        <form class="profile-card profile-form" aria-labelledby="profile-basic-title" @submit.prevent="save">
          <h2 id="profile-basic-title" class="profile-card-title"><NavIcon name="account" />{{ t('基本信息') }}</h2>
          <fieldset :disabled="busy">
            <template v-if="editing">
              <label class="profile-form-row">
                <span>{{ t('艺名') }}</span>
                <input ref="nameInput" v-model="stageName" maxlength="30" :required="profile.published" :placeholder="t('填写你想在主页显示的名字')">
              </label>
              <label class="profile-form-row profile-tagline">
                <span>{{ t('个性签名') }}</span>
                <span class="profile-signature-input">
                  <textarea v-model="tagline" rows="2" maxlength="120" :placeholder="t('写一句话介绍自己（选填）')" />
                  <small aria-hidden="true">{{ tagline.length }} / 120</small>
                </span>
              </label>
              <div class="profile-form-row">
                <span>{{ t('主页封面') }}</span>
                <div class="profile-cover-settings">
                  <img v-if="coverSrc" class="profile-cover-thumbnail" :src="coverSrc" alt="">
                  <div class="profile-inline-actions">
                    <input ref="coverInput" type="file" accept="image/jpeg,image/png" :aria-label="t('主页封面')" hidden @change="chooseImage($event, 'cover')">
                    <button type="button" class="profile-pill" @click="coverInput?.click()">{{ t('更换封面') }}</button>
                    <button v-if="coverSrc" type="button" class="profile-pill" @click="useDefaultCover">{{ t('恢复默认封面') }}</button>
                    <small class="profile-field-hint">{{ t('JPG 或 PNG，最多 5 MB') }}</small>
                  </div>
                </div>
              </div>
              <label v-if="profile.songs.length" class="profile-form-row">
                <span>{{ t('代表作') }}</span>
                <select v-model="featuredReleaseId">
                  <option :value="null">{{ t('自动使用最新发布') }}</option>
                  <option v-for="song in profile.songs" :key="song.id" :value="song.id">{{ song.title }}</option>
                </select>
              </label>
              <div v-else class="profile-form-row">
                <span>{{ t('代表作') }}</span>
                <span class="profile-field-hint">{{ t('发布作品后可选择代表作。') }}</span>
              </div>
            </template>
            <dl v-else class="profile-values">
              <div class="profile-form-row">
                <dt>{{ t('艺名') }}</dt>
                <dd :class="{ empty: !profile.stageName }">{{ profile.stageName || '—' }}</dd>
              </div>
              <div class="profile-form-row">
                <dt>{{ t('个性签名') }}</dt>
                <dd :class="{ empty: !profile.tagline }">{{ profile.tagline || '—' }}</dd>
              </div>
              <div class="profile-form-row">
                <dt>{{ t('主页封面') }}</dt>
                <dd>
                  <img v-if="coverSrc" class="profile-cover-thumbnail" :src="coverSrc" :alt="t('自定义封面')">
                  <span v-else class="empty">{{ t('默认封面') }}</span>
                </dd>
              </div>
              <div class="profile-form-row">
                <dt>{{ t('代表作') }}</dt>
                <dd :class="{ empty: !savedFeatured }">{{ savedFeatured?.title || t(profile.songs.length ? '自动使用最新发布' : '发布作品后可选择代表作。') }}</dd>
              </div>
            </dl>
            <div class="profile-form-row">
              <span>{{ t('歌手主页') }}</span>
              <div class="profile-inline-actions">
                <span class="profile-visibility" :class="{ published: profile.published }">{{ t(profile.published ? '已公开' : '未公开') }}</span>
                <button v-if="!profile.published && returnToSong && profile.stageName && !dirty" type="button"
                  class="profile-pill profile-save" @click="changeVisibility(true)">{{ t('发布歌手主页') }}</button>
                <button type="button" class="profile-pill" @click="showPreview(true)">{{ t('预览歌手主页') }}</button>
                <template v-if="profile.published">
                  <RouterLink class="profile-pill" :to="homeRoute">{{ t('查看主页') }}</RouterLink>
                  <button type="button" class="profile-pill" @click="copyLink">{{ t('复制主页链接') }}</button>
                  <button type="button" class="profile-pill" @click="changeVisibility(false)">{{ t('隐藏主页') }}</button>
                </template>
              </div>
            </div>
            <label v-if="showLink" class="profile-form-row">
              <span>{{ t('主页链接') }}</span>
              <input ref="linkInput" readonly :value="link" @focus="linkInput?.select()">
            </label>
            <div v-if="editing" class="profile-form-row profile-form-footer">
              <div class="profile-inline-actions">
                <button type="submit" class="profile-pill profile-save" :disabled="!dirty">
                  {{ t(busy ? '正在保存…' : '保存修改') }}
                </button>
                <button type="button" class="profile-pill" @click="cancelEdits">{{ t('取消') }}</button>
                <button v-if="avatarSrc" type="button" class="profile-avatar-reset" @click="useDefaultAvatar">{{ t('使用默认头像') }}</button>
              </div>
            </div>
          </fieldset>
        </form>
        <ProfileAccount />
      </div>
      <ProfileWorks :songs="profile.songs" :published="profile.published" :busy="busy"
        @publish="discoverRelease = $event" @remove="remove" @withdraw="withdraw" @library-count="workCount = $event" />
      <PublishSongDialog v-if="discoverRelease" :source="discoverRelease" :cover="discoverCover" discover
        @published-to-discover="updateDiscoverRelease" @close="discoverRelease = undefined" />
    </template>
  </div>
</template>

<style scoped>
.profile-return-song { margin-bottom: 12px; }
.my-artist {
  --profile-card: #fffafb;
  --profile-line: #eddee2;
  --profile-shadow: 0 2px 7px #70354908;
  max-width: 1080px;
  margin-inline: auto;
  padding-bottom: 6px;
}
.my-artist.showing-home-preview { max-width: 1348px; }
.profile-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 16px; margin-bottom: 16px; }
.profile-heading h1 { margin: 0; font-size: clamp(28px, 4vw, 34px); font-weight: 800; line-height: 1.18; }
.profile-heading p { margin: 0; color: var(--muted); font-size: 13px; }
.profile-card { min-width: 0; border: 1px solid var(--profile-line); border-radius: 16px; background: var(--profile-card); box-shadow: var(--profile-shadow); }
.profile-summary { display: flex; align-items: center; gap: clamp(18px, 3vw, 34px); padding: 17px 19px 17px clamp(18px, 4vw, 42px); }
.profile-avatar-wrap { --artist-avatar-size: clamp(70px, 9vw, 98px); flex: none; position: relative; display: grid; }
.profile-avatar-default {
  display: grid;
  width: var(--artist-avatar-size);
  aspect-ratio: 1;
  place-items: center;
  border-radius: 50%;
  background: radial-gradient(circle at 22% 18%, #ffe1c3, transparent 58%), linear-gradient(135deg, #ffb795, #eb91bb);
}
.profile-avatar-default img { width: 48%; height: auto; }
.profile-avatar-input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.profile-avatar-camera {
  position: absolute; display: grid; place-items: center; right: 0; bottom: 0; width: 26px; height: 26px;
  padding: 0; border: 2px solid var(--profile-card); border-radius: 50%; background: #332c30; color: #fff; cursor: pointer;
}
.profile-avatar-camera span { position: relative; width: 11px; height: 8px; border: 1px solid; border-radius: 2px; }
.profile-avatar-camera span::before, .profile-avatar-camera span::after { position: absolute; content: ''; }
.profile-avatar-camera span::before { width: 3px; height: 3px; border: 1px solid; border-radius: 50%; inset: 1.5px auto auto 3px; }
.profile-avatar-camera span::after { width: 4px; height: 2px; border-radius: 1px; top: -2px; left: 2px; background: currentColor; }
.profile-summary-copy { flex: 1; min-width: 0; }
.profile-summary h2 { margin: 0; font-size: clamp(17px, 3vw, 21px); line-height: 1.35; overflow-wrap: anywhere; }
.profile-summary-copy > p { margin: 4px 0 0; color: var(--muted); font-size: 13px; white-space: pre-line; overflow-wrap: anywhere; }
.profile-stats { display: flex; align-items: center; flex-wrap: wrap; gap: 7px 0; margin: 13px 0 0; }
.profile-stats div { display: inline-flex; flex-direction: row-reverse; align-items: baseline; gap: 5px; }
.profile-stats div + div { margin-left: 14px; padding-left: 14px; border-left: 1px solid var(--profile-line); }
.profile-stats dt { color: var(--muted); font-size: 12px; }
.profile-stats dd { margin: 0; font-size: 16px; font-weight: 750; line-height: 1.15; font-variant-numeric: tabular-nums; }
.profile-summary-actions { display: flex; align-items: center; gap: clamp(12px, 2vw, 26px); }
.profile-wave { display: flex; align-items: center; gap: 5px; color: #ff9079; }
.profile-wave span { width: 5px; max-height: 42px; border-radius: 8px; background: linear-gradient(currentColor, #ffc9bd); opacity: .28; }
.profile-wave span:nth-child(even) { opacity: .18; }
.profile-columns { display: grid; grid-template-columns: minmax(0, 1.85fr) minmax(0, 1fr); gap: 13px 16px; margin: 13px 0; }
.profile-form { padding: 14px clamp(14px, 2.15vw, 32px) 15px; }
.profile-card-title { display: flex; align-items: center; gap: 7px; margin: 0 0 13px; font-size: 15px; line-height: 1.4; }
.profile-card-title svg { flex: none; width: 17px; height: 17px; color: var(--accent-text); }
.profile-form fieldset { padding: 0; margin: 0; min-width: 0; border: 0; }
.profile-form-row { display: grid; grid-template-columns: 80px minmax(0, 1fr); align-items: center; gap: 8px 12px; margin: 0 0 13px; font-size: 13px; }
.profile-form-row > span:first-child, .profile-values dt { font-weight: 560; }
.profile-values, .profile-values dd { margin: 0; }
.profile-values .profile-form-row { align-items: start; padding-block: 5px; }
.profile-values dd { white-space: pre-wrap; overflow-wrap: anywhere; }
.profile-values .empty { color: var(--muted); }
.profile-form-row input, .profile-form-row textarea, .profile-form-row select {
  width: 100%; min-width: 0; padding: 7px 11px; border: 1px solid #e9dbdf; border-radius: 9px;
  background: #fffafb; color: var(--ink); font: inherit; line-height: 1.45;
}
.profile-form-row input, .profile-form-row select { min-height: 34px; }
.profile-cover-settings { display: grid; gap: 7px; min-width: 0; }
.profile-cover-thumbnail {
  display: block; width: min(100%, 180px); aspect-ratio: 4 / 1; object-fit: cover;
  border: 1px solid var(--profile-line); border-radius: 8px;
}
.profile-field-hint { color: var(--muted); }
.profile-inline-actions .profile-field-hint { font-size: 11px; }
.profile-tagline { align-items: start; }
.profile-tagline > span:first-child { padding-top: 8px; }
.profile-signature-input { position: relative; display: flex; min-width: 0; }
.profile-form-row textarea { min-height: 62px; padding-bottom: 18px; resize: vertical; }
.profile-signature-input small { position: absolute; right: 9px; bottom: 4px; color: var(--muted); font-size: 10px; pointer-events: none; }
.profile-inline-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 7px; }
.profile-visibility { display: inline-flex; padding: 3px 9px; border-radius: 24px; background: #f8edf0; color: #765e68; font-size: 11px; }
.profile-visibility.published { background: #e6f3ec; color: #2b6448; }
.profile-pill {
  display: inline-flex; align-items: center; justify-content: center; padding: 5px 11px; min-height: 29px;
  border: 1px solid var(--profile-line); border-radius: 24px; background: #fff8f6; color: var(--ink);
  font: inherit; font-size: 12px; line-height: 1.35; text-decoration: none; cursor: pointer;
}
.profile-pill:hover:not(:disabled) { background: #ffeae4; color: var(--accent-text); }
.profile-pill:disabled { opacity: .55; cursor: default; }
.profile-edit-button { min-height: 34px; padding-inline: 14px; border-color: transparent; background: #ffe7df; white-space: nowrap; font-weight: 650; }
.profile-form-footer { margin-bottom: 0; }
.profile-form-footer > div { grid-column: 2; }
.profile-save { padding-inline: 15px; border-color: #ed4a2d; background: linear-gradient(180deg, #ff643f, #ef4528); color: #fff; font-weight: 700; }
.profile-save:hover:not(:disabled) { background: #dd3b23; color: #fff; }
.profile-avatar-reset { padding: 4px; border: 0; background: none; color: var(--muted); font-size: 12px; cursor: pointer; }
.profile-avatar-reset:hover { color: var(--accent-text); text-decoration: underline; }
.artist-preview-bar { margin: 0 0 14px; }
.artist-preview-publish { margin-top: 22px; }
.artist-preview-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.artist-preview-bar span, .artist-preview-publish p { color: var(--muted); }
@media (max-width: 890px) { .profile-wave { display: none; } }
@media (max-width: 680px) {
  .profile-columns { grid-template-columns: minmax(0, 1fr); }
  .profile-summary { gap: 13px; flex-wrap: wrap; }
  .profile-summary-actions { margin-left: auto; }
  .profile-form-row { grid-template-columns: 67px minmax(0, 1fr); gap: 6px 8px; }
  .profile-pill, .profile-avatar-camera { min-height: 34px; }
  .profile-avatar-camera { width: 34px; }
}
</style>
