// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { toRaw } from 'vue'

const mocks = vi.hoisted(() => ({ prepare: vi.fn(), load: vi.fn(), save: vi.fn() }))
vi.mock('../src/modules/artist/model/profile-image', () => ({ prepareProfileImage: mocks.prepare }))
vi.mock('../src/shared/i18n', () => ({ t: (message: string) => message }))
vi.mock('../src/shared/http/api-client', () => ({ ApiError: class ApiError extends Error {} }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
vi.mock('/brand/logo-symbol.svg', () => ({ default: '/brand/logo-symbol.svg' }))
vi.mock('../src/modules/artist/api/artist', () => ({
  getArtistProfile: mocks.load,
  saveArtistProfile: mocks.save,
  artistHomeUrl: (id: string) => `/artist/${id}`,
  ownerAvatarUrl: (key: string) => `/avatar/${key}`,
  ownerArtistCoverUrl: (key: string) => `/cover/${key}`,
  ownerTrack: vi.fn(),
  removeSongFromArtist: vi.fn(),
  setArtistVisibility: vi.fn(),
  withdrawSongFromDiscover: vi.fn(),
}))
vi.mock('../src/shared/ui/NavIcon.vue', () => ({ default: { template: '<span />' } }))
vi.mock('../src/modules/artist/components/ArtistAvatar.vue', () => ({
  default: { props: ['src', 'name'], template: '<img class="avatar-preview" :src="src" />' },
}))
vi.mock('../src/modules/artist/components/ArtistHome.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/modules/artist/components/ProfileAccount.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/modules/artist/components/ProfileWorks.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/modules/artist/components/PublishSongDialog.vue', () => ({ default: { template: '<div />' } }))

import MyArtistProfilePage from '../src/modules/artist/pages/MyArtistProfilePage.vue'

const profile = () => ({
  publicId: null, stageName: '测试歌手', tagline: '', avatarKey: null, coverKey: null,
  featuredReleaseId: null, published: false, songs: [],
})
const inputFile = () => new File(['RIFF\0\0\0\0WEBP'], 'ims.JPG', { type: 'image/jpeg' })
const jpeg = (name = 'prepared.jpg') => new File(['\xff\xd8\xff\xd9'], name, { type: 'image/jpeg' })
const wrappers: VueWrapper[] = []
let createObjectURL: ReturnType<typeof vi.fn<(value: Blob | MediaSource) => string>>
let revokeObjectURL: ReturnType<typeof vi.fn<(url: string) => void>>

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function button(wrapper: VueWrapper, label: string) {
  const found = wrapper.findAll('button').find(item => item.text() === label)
  if (!found) throw new Error(`Missing button: ${label}`)
  return found
}

async function editingPage() {
  const wrapper = mount(MyArtistProfilePage, { global: { stubs: { RouterLink: true } } })
  wrappers.push(wrapper)
  await flushPromises()
  await button(wrapper, '编辑资料').trigger('click')
  return wrapper
}

async function choose(wrapper: VueWrapper, kind: 'avatar' | 'cover', file: File) {
  const input = wrapper.get<HTMLInputElement>(kind === 'avatar'
    ? 'input[aria-label="更换头像"]' : 'input[aria-label="主页封面"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
}

function preview(wrapper: VueWrapper, kind: 'avatar' | 'cover') {
  return wrapper.find(kind === 'avatar' ? 'img.avatar-preview' : 'img.profile-cover-thumbnail')
}

function uploaded(kind: 'avatar' | 'cover') {
  const args = mocks.save.mock.calls.at(-1)!
  // happy-dom's File is reactive in Vue; inspect the original upload object.
  return toRaw(kind === 'avatar' ? args[2] : args[4].cover)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.prepare.mockReset()
  mocks.load.mockResolvedValue(profile())
  mocks.save.mockResolvedValue(profile())
  mocks.prepare.mockImplementation(async (file: File) => file)
  createObjectURL = vi.fn<(value: Blob | MediaSource) => string>(value =>
    `blob:profile/${value instanceof File ? value.name : 'blob'}`)
  revokeObjectURL = vi.fn<(url: string) => void>()
  const NativeURL = URL
  vi.stubGlobal('URL', class extends NativeURL {
    static createObjectURL = createObjectURL
    static revokeObjectURL = revokeObjectURL
  })
})

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.unstubAllGlobals()
})

describe('profile image preparation and save lifecycle', () => {
  it.each(['avatar', 'cover'] as const)('saves the prepared JPEG instead of the misleading JPG source for %s', async kind => {
    const original = inputFile(), converted = jpeg()
    mocks.prepare.mockResolvedValueOnce(converted)
    const wrapper = await editingPage()
    await choose(wrapper, kind, original)
    await flushPromises()
    expect(mocks.prepare).toHaveBeenCalledWith(original)
    expect(preview(wrapper, kind).attributes('src')).toBe('blob:profile/prepared.jpg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledTimes(1)
    expect(uploaded(kind)).toBe(converted)
    expect(uploaded(kind)).not.toBe(original)
  })

  it('blocks both the save control and direct form submission while an image is still preparing', async () => {
    const pending = deferred<File>()
    mocks.prepare.mockReturnValueOnce(pending.promise)
    const wrapper = await editingPage()
    await wrapper.get('input[maxlength="30"]').setValue('新的艺名')
    await choose(wrapper, 'avatar', inputFile())
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(mocks.save).not.toHaveBeenCalled()
    pending.resolve(jpeg())
    await flushPromises()
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded('avatar').name).toBe('prepared.jpg')
  })

  it('allows cancelling preparation without a late completion restoring the cancelled draft', async () => {
    const pending = deferred<File>()
    mocks.prepare.mockReturnValueOnce(pending.promise)
    const wrapper = await editingPage()
    await choose(wrapper, 'avatar', inputFile())
    expect(button(wrapper, '取消').element.matches(':disabled')).toBe(false)
    await button(wrapper, '取消').trigger('click')
    pending.resolve(jpeg('cancelled.jpg'))
    await flushPromises()
    await button(wrapper, '编辑资料').trigger('click')
    expect(preview(wrapper, 'avatar').exists()).toBe(false)
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    expect(mocks.save).not.toHaveBeenCalled()
  })

  it('does not allocate a preview or save a late result after the page is unmounted', async () => {
    const pending = deferred<File>()
    mocks.prepare.mockReturnValueOnce(pending.promise)
    const wrapper = await editingPage()
    await choose(wrapper, 'avatar', inputFile())
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    pending.resolve(jpeg('unmounted.jpg'))
    await flushPromises()
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(mocks.save).not.toHaveBeenCalled()
    const replacement = await editingPage()
    expect(preview(replacement, 'avatar').exists()).toBe(false)
  })

  it.each(['avatar', 'cover'] as const)('preserves the last valid %s draft when its replacement cannot be decoded', async kind => {
    const previous = jpeg('previous.jpg')
    mocks.prepare.mockResolvedValueOnce(previous)
    const wrapper = await editingPage()
    await choose(wrapper, kind, previous)
    await flushPromises()
    mocks.prepare.mockRejectedValueOnce(new Error('图片无法读取，请换一张重试'))
    await choose(wrapper, kind, inputFile())
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('图片无法读取')
    expect(preview(wrapper, kind).attributes('src')).toBe('blob:profile/previous.jpg')
    expect(revokeObjectURL).not.toHaveBeenCalledWith('blob:profile/previous.jpg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded(kind)).toBe(previous)
  })

  it('keeps a new selection when an older cancelled conversion completes later', async () => {
    const old = deferred<File>(), current = deferred<File>()
    mocks.prepare.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const wrapper = await editingPage()
    await choose(wrapper, 'avatar', inputFile())
    await button(wrapper, '取消').trigger('click')
    await button(wrapper, '编辑资料').trigger('click')
    await choose(wrapper, 'avatar', inputFile())
    old.resolve(jpeg('obsolete.jpg'))
    await flushPromises()
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    expect(preview(wrapper, 'avatar').exists()).toBe(false)
    const latest = jpeg('latest.jpg')
    current.resolve(latest)
    await flushPromises()
    expect(preview(wrapper, 'avatar').attributes('src')).toBe('blob:profile/latest.jpg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded('avatar')).toBe(latest)
  })
})
