// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { expect, it, vi } from 'vitest'
import { computed } from 'vue'
import ExploreMusic from '../src/modules/home/components/ExploreMusic.vue'
import { usePlayer } from '../src/shared/player/store'

vi.mock('../src/modules/home/model/published-recommendations', () => ({
  useHouseRecommendations: () => computed(() => []),
  recordPreviewPlay: vi.fn(),
}))
vi.mock('../src/modules/home/model/hot-songs', async original => ({
  ...await original<typeof import('../src/modules/home/model/hot-songs')>(),
  loadHotSongs: vi.fn(),
  loadMyReactions: vi.fn(),
  signedIn: () => false,
}))

class PreviewAudio extends EventTarget {
  src = ''
  paused = true
  duration = 30
  currentTime = 0
  getAttribute() { return this.src || null }
  removeAttribute() { this.src = '' }
  load() {}
  pause() { this.paused = true }
  async play() {
    this.paused = false
    this.dispatchEvent(new Event('play'))
  }
}

it('plays the complete ranked recommendation playlist even when the home preview is filtered', async () => {
  vi.stubGlobal('Audio', PreviewAudio)
  const pinia = createPinia()
  const player = usePlayer(pinia)
  const wrapper = mount(ExploreMusic, {
    attachTo: document.body,
    global: { plugins: [pinia], stubs: { RouterLink: true } },
  })
  try {
    await flushPromises()
    await wrapper.findAll('nav button').find(button => button.text() === '轻音乐')!.trigger('click')
    const card = wrapper.findAll('.playlist-card').find(section => section.get('h3').text() === '华语流行')!
    expect(card.findAll('.explore-track-row')).toHaveLength(1)
    await card.get('.explore-track-row').trigger('click')
    expect(player.current?.id).toBe('river-instrumental')
    expect(player.tracks).toHaveLength(14)
    expect(player.tracks.some(song => song.id === 'feng-ye-xing')).toBe(true)

    await card.get('.playlist-more').trigger('click')
    const dialog = document.querySelector<HTMLDialogElement>('.playlist-dialog')!
    expect(Array.from(dialog.querySelectorAll('.explore-track-copy strong')).map(title => title.textContent))
      .toEqual(player.tracks.map(song => song.title))
    const repeat = dialog.querySelector<HTMLButtonElement>('.mode-trigger')!
    expect(repeat.disabled).toBe(false)
    repeat.click()
    await flushPromises()
    Array.from(dialog.querySelectorAll('.mode-options button')).find(option => option.textContent?.trim() === '列表循环')!
      .dispatchEvent(new Event('click', { bubbles: true }))
    await flushPromises()
    expect(player.repeatMode).toBe('list')
    expect(repeat.getAttribute('aria-label')).toBe('播放模式：列表循环')
  } finally {
    wrapper.unmount()
    player.reset()
    vi.unstubAllGlobals()
  }
})
