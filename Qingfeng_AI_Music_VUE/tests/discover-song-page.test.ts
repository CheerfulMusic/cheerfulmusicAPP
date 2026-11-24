// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import DiscoverSongPage from '../src/modules/artist/pages/DiscoverSongPage.vue'
import type { DiscoverSongDetail } from '../src/modules/artist/api/discover-song'
import { setLocale } from '../src/shared/i18n'

const mocks = vi.hoisted(() => ({ detail: vi.fn(), like: vi.fn(), comment: vi.fn(), login: vi.fn() }))
vi.mock('../src/modules/artist/api/discover-song', async original => ({
  ...await original<typeof import('../src/modules/artist/api/discover-song')>(),
  getDiscoverSong: mocks.detail, setDiscoverLike: mocks.like, postDiscoverComment: mocks.comment,
}))
vi.mock('../src/shared/identity/login-dialog', () => ({ showLoginDialog: mocks.login }))
vi.mock('../src/shared/player/store', () => ({
  usePlayer: () => ({ current: null, playing: false, starting: false, time: 0, duration: 0, tracks: [], selectIfEmpty: vi.fn() }),
}))

const song = { id: '21282ee4-0645-4a38-aa08-3ba3b79be197', version: 2, title: '晚风车站', hasCover: false }
function detail(signedIn = true): DiscoverSongDetail {
  return {
    release: { song, artistId: 'artist-1', stageName: '青风音乐', genre: 'folk', publishedAt: '2026-11-24T01:00:00Z' },
    durationSeconds: 223, lyrics: '第一行\n第二行\n第三行\n第四行', description: '一首木吉他民谣。', signedIn, canEditDescription: false,
    likes: { count: 6, liked: false }, commentCount: 0, comments: { items: [], nextCursor: null },
  }
}

let wrapper: VueWrapper | undefined
async function open() {
  const idle = { template: '<div />' }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: idle }, { path: '/discover', component: idle },
    { path: '/discover/songs/:id/:version', name: 'discover-song', component: DiscoverSongPage },
    { path: '/artist/:id', name: 'artist-home', component: idle },
  ] })
  await router.push(`/discover/songs/${song.id}/${song.version}`)
  wrapper = mount(DiscoverSongPage, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

beforeEach(() => { vi.resetAllMocks(); setLocale('zh-CN'); mocks.detail.mockResolvedValue(detail()) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined })

describe('Discover song detail', () => {
  it('shows the song and playback while community storage is unavailable', async () => {
    mocks.detail.mockResolvedValue({ ...detail(), communityAvailable: false })
    const view = await open()
    expect(view.get('h1').text()).toBe(song.title)
    expect(view.get('.song-detail-words').text()).toContain('第一行')
    expect(view.get('.song-detail-primary').text()).toBe('播放')
    expect(view.get('.song-detail-like').attributes('disabled')).toBeDefined()
    expect(view.find('.song-detail-comment-form').exists()).toBe(false)
    expect(view.get('#discover-song-comments').text()).toContain('点赞和评论暂未开放。')
  })

  it('opens the comment section and keeps guest actions read-only until login', async () => {
    mocks.detail.mockResolvedValue(detail(false))
    const view = await open()
    expect(view.get('h1').text()).toBe(song.title)
    expect(view.get('.song-detail-words').text()).not.toContain('第四行')
    await view.get('[aria-controls="discover-song-words"]').trigger('click')
    expect(view.get('.song-detail-words').text()).toContain('第四行')
    await view.get('[aria-controls="discover-song-comments"]').trigger('click')
    expect(document.activeElement).toBe(view.get('#discover-song-comments').element)
    await view.get('.song-detail-like').trigger('click')
    expect(mocks.login).toHaveBeenCalledOnce()
    expect(mocks.like).not.toHaveBeenCalled()
  })

  it('displays the server like state and retries a failed comment with the same request ID', async () => {
    mocks.like.mockResolvedValue({ count: 7, liked: true })
    mocks.comment.mockRejectedValueOnce(new Error('Network issue')).mockResolvedValueOnce({
      comment: { id: 'comment-1', author: '听众', text: '很喜欢前奏', createdAt: '2026-11-24T02:00:00Z', removable: true }, count: 1,
    })
    const view = await open()
    await view.get('.song-detail-like').trigger('click')
    await flushPromises()
    expect(mocks.like).toHaveBeenCalledWith(song, true)
    expect(view.get('.song-detail-like').attributes('aria-pressed')).toBe('true')
    expect(view.get('.song-detail-like').text()).toContain('7')
    const composer = view.get('.song-detail-comment-form')
    await composer.get('textarea').setValue('很喜欢前奏')
    await composer.trigger('submit')
    await flushPromises()
    expect(view.get('#discover-song-comments [role="alert"]').text()).toBe('Network issue')
    await composer.trigger('submit')
    await flushPromises()
    expect(mocks.comment.mock.calls[1]).toEqual(mocks.comment.mock.calls[0])
    expect(view.get('.song-detail-comment-list li').text()).toContain('很喜欢前奏')
    expect(view.get('#discover-song-comments .song-detail-muted').text()).toBe('1 条评论')
  })
})
