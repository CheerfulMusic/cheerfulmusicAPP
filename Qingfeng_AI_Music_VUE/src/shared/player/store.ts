import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
export interface PlayableTrack { id: string; title: string; titleKey?: string; cover: string; audio: string; provenance?: 'SAMPLE' | 'IMPORTED' | 'GENERATED' }
export const usePlayer = defineStore('player', () => {
  const current = ref<PlayableTrack | null>(null), playing = ref(false), buffering = ref(false), time = ref(0), duration = ref(0), error = ref(''), queue = ref<PlayableTrack[]>([])
  const hasQueue = computed(() => queue.value.length > 1 && queue.value.some(track => track.id === current.value?.id))
  let audio: HTMLAudioElement | undefined, operation = 0
  function engine() {
    if (!audio) {
      audio = new Audio(); audio.preload = 'metadata'
      audio.addEventListener('timeupdate', () => { time.value = audio!.currentTime })
      audio.addEventListener('loadedmetadata', () => { duration.value = Number.isFinite(audio!.duration) ? audio!.duration : 0 })
      audio.addEventListener('play', () => { playing.value = true })
      audio.addEventListener('pause', () => { playing.value = false })
      audio.addEventListener('waiting', () => { buffering.value = true })
      audio.addEventListener('canplay', () => { buffering.value = false })
      audio.addEventListener('error', () => { if (!current.value || !audio!.error) return; error.value = '音频无法加载，链接可能已失效或文件已损坏。可点击重试。'; playing.value = false; buffering.value = false })
      audio.addEventListener('ended', () => { playing.value = false; const index = queue.value.findIndex(track => track.id === current.value?.id); if (hasQueue.value && index < queue.value.length - 1) void next() })
    }
    return audio
  }
  async function toggle(track: PlayableTrack) {
    const a = engine(), turn = ++operation
    if (current.value?.id === track.id && !a.paused) { a.pause(); return }
    const failed = Boolean(a.error); error.value = ''
    if (current.value?.id !== track.id) {
      a.pause(); current.value = track; a.src = track.audio; time.value = 0; duration.value = 0
      if (!queue.value.some(item => item.id === track.id)) queue.value = [track]
    } else if (failed) a.load()
    buffering.value = true
    try { await a.play(); if (turn === operation) buffering.value = false }
    catch { if (turn === operation) { error.value = '暂时无法播放，请点击重试，并检查礼物链接是否有效。'; playing.value = false; buffering.value = false } }
  }
  function setQueue(tracks: PlayableTrack[]) { queue.value = tracks }
  async function next(direction = 1) { if (!hasQueue.value) return; const index = queue.value.findIndex(track => track.id === current.value?.id); const track = queue.value[(index + direction + queue.value.length) % queue.value.length]; if (track) await toggle(track) }
  function seek(value: number) { if (audio && Number.isFinite(value) && duration.value > 0) { try { audio.currentTime = Math.min(duration.value, Math.max(0, value)) } catch { error.value = '当前音频暂不支持跳转进度' } } }
  function close() { operation++; audio?.pause(); current.value = null; if (audio) { audio.removeAttribute('src'); audio.load() } queue.value = []; time.value = 0; duration.value = 0; error.value = ''; buffering.value = false; playing.value = false }
  return { current, playing, buffering, time, duration, error, hasQueue, toggle, seek, close, setQueue, next }
})
