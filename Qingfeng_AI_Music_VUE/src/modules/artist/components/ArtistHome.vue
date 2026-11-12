<script setup lang="ts">
import { t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import ArtistAvatar from './ArtistAvatar.vue'

const props = defineProps<{ name: string; tagline: string; avatar?: string; tracks: PlayableTrack[] }>()
const player = usePlayer()
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
    <header class="artist-hero">
      <ArtistAvatar :name="name" :src="avatar" />
      <div>
        <h1>{{ name || t('你的艺名') }}</h1>
        <p v-if="tagline">{{ tagline }}</p>
      </div>
    </header>
    <section class="artist-track-section" aria-labelledby="artist-tracks">
      <h2 id="artist-tracks">{{ t('作品') }}</h2>
      <p v-if="!tracks.length" class="artist-empty">{{ t('这里还没有公开作品。') }}</p>
      <ul v-else class="artist-track-list">
        <li v-for="track in tracks" :key="track.audio">
          <button type="button" class="artist-track" :aria-label="t(active(track) ? '暂停 {title}' : '试听 {title}', { title: track.title })" @click="play(track)">
            <img :src="track.cover" alt="" loading="lazy">
            <span>{{ track.title }}</span>
            <span class="artist-play" aria-hidden="true"><PlaybackIcon :playing="active(track)" /></span>
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.artist-home { max-width: 920px; margin-inline: auto; min-width: 0; }
.artist-hero {
  --artist-avatar-size: clamp(86px, 13vw, 144px);
  display: flex;
  align-items: center;
  gap: clamp(20px, 5vw, 54px);
  min-height: 240px;
  padding: clamp(25px, 6vw, 62px);
  border-radius: 22px 22px 6px 6px;
  background: #34212e;
  color: #fff7f4;
}
.artist-hero h1 { color: inherit; font-size: clamp(34px, 6vw, 64px); line-height: 1.08; margin: 0; overflow-wrap: anywhere; }
.artist-hero p { margin: 16px 0 0; color: #f0dbe4; line-height: 1.65; white-space: pre-line; overflow-wrap: anywhere; }
.artist-track-section { margin-block: 34px 56px; }
.artist-track-section h2 { margin: 0 0 12px; font-size: 23px; }
.artist-empty { color: var(--muted); border-top: 1px solid var(--line); padding-block: 18px; }
.artist-track-list { list-style: none; padding: 0; margin: 0; border-top: 1px solid var(--line); }
.artist-track-list li { border-bottom: 1px solid var(--line); }
.artist-track {
  display: grid;
  width: 100%;
  grid-template-columns: 48px minmax(0, 1fr) 42px;
  align-items: center;
  gap: 14px;
  padding: 12px 6px;
  text-align: left;
  overflow-wrap: anywhere;
  font-weight: 600;
  background: transparent;
  border: 0;
  color: var(--ink);
  cursor: pointer;
}
.artist-track:hover { background: var(--accent-soft); }
.artist-track img { width: 48px; height: 48px; border-radius: 6px; object-fit: cover; }
.artist-play { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 50%; background: var(--accent-soft); color: var(--accent-text); }
.artist-play :deep(svg) { width: 17px; height: 17px; }
@media (max-width: 560px) {
  .artist-hero { align-items: flex-start; flex-direction: column; min-height: 0; }
  .artist-track-section { margin-block-start: 25px; }
}
</style>
