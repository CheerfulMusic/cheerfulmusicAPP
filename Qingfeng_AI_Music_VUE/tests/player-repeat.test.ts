import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlayer, type PlayableTrack } from '../src/shared/player/store'

class FakeAudio extends EventTarget {
  static latest: FakeAudio
  static failure: Promise<void> | undefined
  src = ''; preload = ''; paused = true; ended = false; currentTime = 0; duration = 18; readyState = 3; error = null
  constructor() { super(); FakeAudio.latest = this }
  play = vi.fn(() => { this.paused = false; this.ended = false; this.dispatchEvent(new Event('play')); const result = FakeAudio.failure; FakeAudio.failure = undefined; return result || Promise.resolve() })
  pause = vi.fn(() => { this.paused = true; this.dispatchEvent(new Event('pause')) })
  getAttribute(name: string) { return name === 'src' ? this.src : null }
  removeAttribute(name: string) { if (name === 'src') this.src = '' }
  load() {}
}
const track = (id: string): PlayableTrack => ({ id, title: id, cover: '/cover', audio: `/audio/${id}` })
beforeEach(() => { setActivePinia(createPinia()); FakeAudio.failure = undefined; vi.stubGlobal('Audio', FakeAudio) })
afterEach(() => { usePlayer().reset(); vi.unstubAllGlobals() })

describe('whole song repeat', () => {
  it('replays the same audio at the start instead of advancing the queue', async () => {
    const player = usePlayer(); player.setQueue([track('one'), track('two')]); await player.toggle(track('one')); player.toggleRepeat()
    const audio = FakeAudio.latest; audio.currentTime = 18; audio.paused = true; audio.dispatchEvent(new Event('ended'))
    expect(player.repeatOne).toBe(true); expect(player.current?.id).toBe('one'); expect(audio.currentTime).toBe(0); expect(audio.play).toHaveBeenCalledTimes(2)
    player.toggleRepeat(); audio.paused = true; audio.dispatchEvent(new Event('ended')); await Promise.resolve()
    expect(player.current?.id).toBe('two')
  })
  it('preserves segment behavior and clears repeat when the player closes', async () => {
    const player = usePlayer(); player.toggleRepeat(); await player.playSegment(track('one'), 3, 8, false)
    const audio = FakeAudio.latest; audio.currentTime = 18; audio.paused = true; audio.dispatchEvent(new Event('ended'))
    expect(audio.play).toHaveBeenCalledTimes(1); expect(player.segment).toBeNull()
    player.close(); expect(player.repeatOne).toBe(false)
  })
  it('reports a failed replay and exposes a copy of the real queue', async () => {
    const player = usePlayer(); player.setQueue([track('one'), track('two')]); await player.toggle(track('one')); player.toggleRepeat()
    expect(Object.isFrozen(player.tracks)).toBe(true); expect(Object.isFrozen(player.tracks[0])).toBe(true)
    FakeAudio.failure = Promise.reject(new DOMException('blocked', 'NotAllowedError'))
    const audio = FakeAudio.latest; audio.paused = true; audio.dispatchEvent(new Event('ended')); await Promise.resolve(); await Promise.resolve()
    expect(player.error).toContain('浏览器暂停'); expect(player.playing).toBe(false); expect(audio.paused).toBe(true)
    await player.toggle(track('one')); expect(player.error).toBe(''); expect(player.playing).toBe(true)
  })
})
