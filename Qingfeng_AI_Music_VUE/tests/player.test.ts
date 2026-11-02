import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePlayer, type PlayableTrack } from '../src/shared/player/store'

class FakeAudio extends EventTarget {
  static current: FakeAudio
  src = ''; preload = ''; paused = true; currentTime = 0; duration = 18; error: object | null = null
  nextPlay: Promise<void> | undefined
  constructor() { super(); FakeAudio.current = this }
  play() { this.paused = false; this.dispatchEvent(new Event('play')); const pending = this.nextPlay; this.nextPlay = undefined; return pending || Promise.resolve() }
  pause() { this.paused = true; this.dispatchEvent(new Event('pause')) }
  load() { this.error = null }
  removeAttribute(name: string) { if (name === 'src') this.src = '' }
}
const track = (id: string): PlayableTrack => ({id,title:id,cover:'/cover.svg',audio:`/api/audio/${id}`,provenance:'IMPORTED'})
beforeEach(() => { setActivePinia(createPinia()); vi.stubGlobal('Audio', FakeAudio) })
afterEach(() => vi.unstubAllGlobals())
describe('全站音频状态', () => {
  it('关闭时停止播放并释放私有音频地址', async () => { const player = usePlayer(); await player.toggle(track('one')); player.close(); expect(player.current).toBeNull(); expect(player.playing).toBe(false); expect(FakeAudio.current.src).toBe('') })
  it('旧播放请求失败不会覆盖新曲目的状态', async () => { const player = usePlayer(); await player.toggle(track('initial')); let reject!: (reason: Error) => void; FakeAudio.current.nextPlay = new Promise<void>((_, no) => { reject = no }); const first = player.toggle(track('one')); await player.toggle(track('two')); reject(new Error('旧请求中断')); await first; expect(player.current?.id).toBe('two'); expect(player.error).toBe(''); expect(player.playing).toBe(true) })
  it('文件加载失败给出提示并允许重试', async () => { const player = usePlayer(); await player.toggle(track('one')); FakeAudio.current.error = {code:4}; FakeAudio.current.paused = true; FakeAudio.current.dispatchEvent(new Event('error')); expect(player.error).toContain('无法加载'); await player.toggle(track('one')); expect(player.error).toBe(''); expect(player.playing).toBe(true) })
  it('切歌复用同一个音频实例并支持返回上一首', async () => { const player = usePlayer(); player.setQueue([track('one'),track('two')]); await player.toggle(track('one')); const audio = FakeAudio.current; await player.next(); expect(player.current?.id).toBe('two'); expect(FakeAudio.current).toBe(audio); await player.next(-1); expect(player.current?.id).toBe('one') })
})
