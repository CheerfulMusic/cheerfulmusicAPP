import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { requestJson } from '../http/api-client'
import { getPlaybackLyrics, syncPlaybackLyrics, type PlaybackLyrics } from '../../modules/works/api/detail'
import { lyricPosition } from './lyric-timeline'
import { usePlayer } from './store'

/** Parse only our known playback routes; a cover, download, or arbitrary external URL is not a lyrics source. */
export function lyricsSource(audio: string) {
  let path: string
  try { path = new URL(audio, location.href).pathname } catch { return undefined }
  const own = path.match(/\/app\/works\/([^/]+)\/audio\/([^/]+)$/)
  if (own) {
    try { return { kind: 'owner' as const, work: decodeURIComponent(own[1]!), asset: decodeURIComponent(own[2]!) } } catch { return undefined }
  }
  const published = path.match(/\/public\/artists\/([^/]+)\/songs\/([^/]+)\/(\d+)\/audio$/)
  if (published) return { kind: 'public' as const, path: `public/artists/${published[1]}/songs/${published[2]}/${published[3]}/lyrics` }
  return undefined
}

export function useCurrentLyrics() {
  const player = usePlayer(), data = ref<PlaybackLyrics>(), loading = ref(false)
  let generation = 0, timer: ReturnType<typeof setTimeout> | undefined, disposed = false
  let attempted = false, deadline = 0, failures = 0
  function clear() { clearTimeout(timer); timer = undefined }
  async function load(turn: number, sync = false): Promise<void> {
    const track = player.current
    if (!track || turn !== generation || disposed) return
    const source = lyricsSource(track.audio)
    if (!source) return
    loading.value = true
    try {
      let value: PlaybackLyrics
      if (source.kind === 'owner' && sync) {
        // Share the detail panel's idempotency key. Unknown external results must not be re-submitted.
        const name = `qingfeng.lyrics.timing:${source.asset}`
        let key = ''
        try { key = sessionStorage.getItem(name) || '' } catch { /* Private browsing. */ }
        if (!/^[a-f0-9-]{36}$/i.test(key)) key = crypto.randomUUID()
        try { sessionStorage.setItem(name, key) } catch { /* This mount still attempts only once. */ }
        value = await syncPlaybackLyrics(source.work, source.asset, key)
      } else value = source.kind === 'owner'
        ? await getPlaybackLyrics(source.work, source.asset)
        : await requestJson<PlaybackLyrics>(source.path)
      if (turn !== generation || disposed) return
      data.value = value; failures = 0
    } catch {
      if (turn !== generation || disposed) return
      failures++
      // A lost POST response may still be processing. Read its state; never submit a second job.
      if (sync) data.value = { status: 'UNKNOWN', text: data.value?.text || '', lines: [], canSync: false }
    } finally {
      if (turn === generation && !disposed) { loading.value = false; follow(turn) }
    }
  }
  function follow(turn: number) {
    clear()
    if (disposed || turn !== generation || Date.now() > deadline || failures >= 3) return
    const source = player.current && lyricsSource(player.current.audio)
    if (source?.kind === 'owner' && data.value?.status === 'NOT_REQUESTED' && data.value.canSync && player.playing && !attempted) {
      attempted = true; void load(turn, true); return
    }
    if (data.value?.status === 'FETCHING' || data.value?.status === 'UNKNOWN' || !data.value && failures)
      timer = setTimeout(() => { void load(turn) }, Math.min(15000, 3000 * 2 ** failures))
  }
  watch(() => [player.current?.id, player.current?.audio, player.current?.lyricLines] as const, () => {
    const turn = ++generation
    clear(); data.value = undefined; loading.value = false; attempted = false; failures = 0; deadline = Date.now() + 180000
    const lines = player.current?.lyricLines
    if (lines?.length) { data.value = { status: 'READY', text: '', lines: [...lines], canSync: false }; return }
    void load(turn)
  }, { immediate: true })
  watch(() => player.playing, () => { if (!loading.value) follow(generation) })
  onBeforeUnmount(() => { disposed = true; generation++; clear() })
  const line = computed(() => {
    const lines = data.value?.status === 'READY' ? data.value.lines : []
    const position = lyricPosition(lines, player.time)
    if (position.active >= 0) return { text: lines[position.active]!.text, literal: true }
    if (lines.length) return { text: player.time < lines[0]!.start ? '前奏…' : player.time >= lines[lines.length - 1]!.end ? '尾奏…' : '间奏…', literal: false }
    if (loading.value || data.value?.status === 'FETCHING') return { text: '歌词加载中…', literal: false }
    return { text: data.value?.text ? '暂无同步歌词' : '暂无歌词', literal: false }
  })
  return { line }
}
