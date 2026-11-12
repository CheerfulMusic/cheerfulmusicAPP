<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import ArtistAvatar from './ArtistAvatar.vue'

const props = defineProps<{
  name: string
  tagline: string
  avatar?: string
  cover?: string
  tracks: PlayableTrack[]
  featuredTrackId?: string | null
}>()
const player = usePlayer()
const failedCover = ref<string>()
const customCover = computed(() => props.cover === failedCover.value ? undefined : props.cover)
const pinnedTrack = computed(() => props.tracks.find(track => track.id === props.featuredTrackId))
const featured = computed(() => pinnedTrack.value ?? props.tracks[0])
const featuredAction = computed(() => {
  const playing = featured.value && active(featured.value)
  if (pinnedTrack.value) return playing ? '暂停代表作' : '播放代表作'
  return playing ? '暂停最新作品' : '播放最新作品'
})
const featureTime = computed(() => {
  if (player.current?.audio !== featured.value?.audio || !player.duration) return undefined
  return { position: player.time, duration: player.duration }
})

function active(track: PlayableTrack) {
  return player.current?.audio === track.audio && (player.playing || player.starting)
}
function play(track: PlayableTrack) {
  player.setQueue(props.tracks, false)
  void player.toggle(track)
}
</script>

<template>
  <div class="artist-home">
    <header class="artist-hero" :class="{ 'has-custom-cover': customCover }">
      <img v-if="customCover" :key="customCover" class="artist-cover-image" :src="customCover" alt="" @error="failedCover = customCover">
      <ArtistAvatar :name="name" :src="avatar" />
      <div class="artist-identity">
        <h1>{{ name || t('你的艺名') }}</h1>
        <div v-if="featured || $slots.actions" class="artist-hero-actions">
          <button v-if="featured" type="button" class="artist-action artist-action-primary" @click="play(featured)">
            <PlaybackIcon :playing="active(featured)" />
            {{ t(featuredAction) }}
          </button>
          <slot name="actions" />
        </div>
      </div>
    </header>

    <nav v-if="tagline" class="artist-page-nav" :aria-label="t('歌手主页')">
      <a href="#artist-works">{{ t('作品') }}</a>
      <a href="#artist-about">{{ t('关于') }}</a>
    </nav>
    <div class="artist-body" :class="{ 'has-about': tagline }">
      <section id="artist-works" class="artist-track-section" aria-labelledby="artist-tracks">
        <article v-if="featured" class="artist-featured" aria-labelledby="artist-featured-title">
          <button type="button" class="artist-feature-cover" :aria-label="t(active(featured) ? '暂停' : '播放')" aria-describedby="artist-featured-title" @click="play(featured)">
            <img :src="featured.cover" alt="">
            <span class="artist-play" aria-hidden="true"><PlaybackIcon :playing="active(featured)" /></span>
          </button>
          <div class="artist-feature-copy">
            <span class="artist-feature-label">{{ t(pinnedTrack ? '代表作' : '最新发布') }}</span>
            <h2 id="artist-featured-title">{{ featured.title }}</h2>
            <progress v-if="featureTime" class="artist-feature-progress" :aria-label="t('播放进度')" :value="featureTime.position" :max="featureTime.duration" />
          </div>
        </article>
        <h2 id="artist-tracks">{{ t('全部作品') }} <span>({{ tracks.length }})</span></h2>
        <p v-if="!tracks.length" class="artist-empty">{{ t('这里还没有公开作品。') }}</p>
        <ul v-else class="artist-track-list">
          <li v-for="(track, index) in tracks" :key="track.audio">
            <button type="button" class="artist-track" :aria-label="t(active(track) ? '暂停 {title}' : '试听 {title}', { title: track.title })" @click="play(track)">
              <span class="artist-track-order" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
              <img :src="track.cover" alt="" loading="lazy">
              <span class="artist-track-title">{{ track.title }}</span>
              <span class="artist-play" aria-hidden="true"><PlaybackIcon :playing="active(track)" /></span>
            </button>
          </li>
        </ul>
      </section>
      <aside v-if="tagline" id="artist-about" class="artist-about" aria-labelledby="artist-about-heading">
        <h2 id="artist-about-heading">{{ t('个人介绍') }}</h2>
        <p>{{ tagline }}</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
:global(.consumer-shell .workspace:has(.artist-home)) { max-width: 1428px; }
.artist-home {
  --artist-line: #efdee2;
  --artist-surface: #fffafa;
  --artist-accent: #b83c2e;
  --artist-tint: #fce9e7;
  max-width: 1348px;
  min-width: 0;
  margin-inline: auto;
}
.artist-hero {
  --artist-avatar-size: clamp(92px, 11.2vw, 168px);
  position: relative;
  isolation: isolate;
  display: flex;
  align-items: center;
  gap: clamp(20px, 3.4vw, 34px);
  min-height: 256px;
  padding: clamp(25px, 4.1vw, 44px) clamp(24px, 5.4vw, 80px);
  border: 1px solid var(--artist-line);
  border-radius: 24px;
  background:
    linear-gradient(90deg, #fce6e9b0, #fce6e929 58%, transparent 76%),
    #f8dce3 url('../../../assets/artist/artist-sunset-cover.webp') center 72% / cover no-repeat;
}
.artist-cover-image {
  position: absolute;
  inset: 0;
  z-index: -2;
  width: 100%;
  height: 100%;
  border-radius: inherit;
  object-fit: cover;
}
.artist-hero.has-custom-cover::before {
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: inherit;
  background: linear-gradient(90deg, #fff1f4ef, #fff1f4b3 46%, #fff1f440);
  content: '';
  pointer-events: none;
}
.artist-hero :deep(.artist-avatar) {
  border: clamp(3px, .4vw, 5px) solid #fffafa;
  background: #f8d5d5;
  color: #812b34;
}
.artist-identity { min-width: 0; }
.artist-hero h1 {
  margin: 0;
  color: #2b2224;
  font-size: clamp(36px, 4.8vw, 62px);
  font-weight: 800;
  line-height: 1.12;
  overflow-wrap: anywhere;
}
.artist-hero-actions { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 8px 11px; margin-top: 18px; }
.artist-hero-actions :deep(.artist-action) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 42px;
  padding: 7px 16px;
  border: 1px solid #edd0d0;
  border-radius: 24px;
  background: #fff9f9e8;
  color: #8e302c;
  font-size: .875rem;
  font-weight: 650;
  line-height: 1.4;
  cursor: pointer;
}
.artist-hero-actions :deep(.artist-action:hover) { background: #fff; }
.artist-hero-actions .artist-action-primary {
  border-color: transparent;
  background: linear-gradient(100deg, #dc462a, #c72d3a);
  color: #fff;
}
.artist-hero-actions .artist-action-primary:hover { background: #b52b29; }
.artist-hero-actions :deep(svg) { width: 15px; height: 15px; flex: none; }
.artist-page-nav { display: flex; gap: 28px; padding: 0 16px; margin-top: 12px; border-bottom: 1px solid var(--artist-line); }
.artist-page-nav a {
  display: inline-flex;
  align-items: center;
  min-height: 48px;
  padding-inline: 8px;
  color: var(--ink);
  font-size: 1rem;
  font-weight: 650;
  text-decoration: none;
}
.artist-page-nav a:hover { color: var(--artist-accent); }
.artist-body { display: grid; gap: 22px 28px; margin-top: 22px; }
.artist-body.has-about { grid-template-columns: minmax(0, 2.3fr) minmax(230px, 1fr); align-items: start; }
.artist-track-section, .artist-about { min-width: 0; scroll-margin-top: 105px; }
.artist-featured {
  display: grid;
  grid-template-columns: clamp(88px, 13vw, 152px) minmax(0, 1fr);
  align-items: center;
  gap: clamp(16px, 3vw, 32px);
  margin-bottom: 18px;
  padding: clamp(14px, 2.5vw, 22px);
  border: 1px solid #f2d4d7;
  border-radius: 19px;
  background: linear-gradient(110deg, #fff6f4, #fdecf1);
}
.artist-feature-cover { position: relative; display: block; width: 100%; padding: 0; border: 0; border-radius: 15px; background: var(--artist-tint); cursor: pointer; }
.artist-feature-cover img { display: block; width: 100%; aspect-ratio: 1; border-radius: inherit; object-fit: cover; }
.artist-feature-cover .artist-play {
  position: absolute;
  right: 9px;
  bottom: 9px;
  background: #fff;
  box-shadow: 0 3px 12px #71362936;
}
.artist-feature-label { display: inline-flex; padding: 2px 8px; border: 1px solid #f4cfcb; border-radius: 20px; color: var(--artist-accent); font-size: .78rem; }
.artist-feature-copy h2 { margin: 8px 0 0; font-size: clamp(19px, 3vw, 26px); line-height: 1.3; overflow-wrap: anywhere; }
.artist-feature-progress { display: block; width: min(100%, 460px); height: 8px; margin-top: 17px; accent-color: var(--artist-accent); }
.artist-track-section > h2, .artist-about h2 { margin: 0 0 10px; color: var(--ink); font-size: 1.18rem; line-height: 1.45; }
.artist-track-section > h2 span { margin-inline-start: 4px; font-weight: 500; }
.artist-track-list, .artist-about, .artist-empty { border: 1px solid var(--artist-line); border-radius: 17px; background: var(--artist-surface); }
.artist-empty, .artist-about { margin: 0; padding: 16px 19px; color: var(--muted); }
.artist-about p { margin: 0; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.artist-track-list { list-style: none; margin: 0; padding: 0 14px; }
.artist-track-list li + li { border-top: 1px solid var(--artist-line); }
.artist-track {
  display: grid;
  width: 100%;
  min-height: 55px;
  grid-template-columns: 2ch 44px minmax(0, 1fr) 36px;
  align-items: center;
  gap: 13px;
  padding: 7px 4px;
  border: 0;
  background: transparent;
  color: var(--ink);
  text-align: start;
  cursor: pointer;
}
.artist-track:hover { background: #fff0ec; }
.artist-track-order { color: var(--muted); font-variant-numeric: tabular-nums; }
.artist-track img { width: 44px; height: 44px; border-radius: 7px; object-fit: cover; }
.artist-track-title { font-weight: 650; overflow-wrap: anywhere; }
.artist-play { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 50%; background: var(--artist-tint); color: var(--artist-accent); }
.artist-play :deep(svg) { width: 15px; height: 15px; }
@media (max-width: 720px) {
  .artist-body.has-about { grid-template-columns: minmax(0, 1fr); }
  .artist-about { grid-row: 1; }
  .artist-hero { min-height: 208px; background-position: 82% 72%; }
}
@media (max-width: 450px) {
  .artist-hero { --artist-avatar-size: 80px; align-items: flex-start; flex-direction: column; gap: 12px; padding: 20px; }
  .artist-hero.has-custom-cover::before { background: linear-gradient(90deg, #fff1f4eb, #fff1f4a6); }
  .artist-hero-actions { margin-top: 12px; }
  .artist-page-nav { padding-inline: 4px; gap: 18px; }
  .artist-track-list { padding-inline: 9px; }
  .artist-track { gap: 9px; }
}
</style>
