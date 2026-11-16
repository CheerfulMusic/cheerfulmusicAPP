// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper, type DOMWrapper } from '@vue/test-utils'
import { toRaw } from 'vue'

const mocks = vi.hoisted(() => ({ decode: vi.fn(), draw: vi.fn(), encode: vi.fn(), load: vi.fn(), save: vi.fn() }))
vi.mock('../src/modules/artist/model/profile-image', async importOriginal => ({
  ...await importOriginal<typeof import('../src/modules/artist/model/profile-image')>(),
  decodeProfileImage: mocks.decode, drawProfileCrop: mocks.draw, encodeProfileCrop: mocks.encode,
}))
vi.mock('../src/platform', () => ({ platform: { kind: 'web' } }))
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
const decoded = () => ({ element: new Image(), release: vi.fn() })
const wrappers: VueWrapper[] = []
let createObjectURL: ReturnType<typeof vi.fn<(value: Blob | MediaSource) => string>>
let revokeObjectURL: ReturnType<typeof vi.fn<(url: string) => void>>

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function button(wrapper: VueWrapper | Pick<DOMWrapper<Element>, 'findAll'>, label: string) {
  const found = wrapper.findAll('button').find(item => item.text() === label)
  if (!found) throw new Error(`Missing button: ${label}`)
  return found
}

async function editingPage() {
  const wrapper = mount(MyArtistProfilePage, { attachTo: document.body, global: { stubs: { RouterLink: true, Teleport: true } } })
  wrappers.push(wrapper)
  await flushPromises()
  await button(wrapper, '编辑资料').trigger('click')
  return wrapper
}

async function choose(wrapper: VueWrapper, kind: 'avatar' | 'cover', file: File) {
  if (!wrapper.find('dialog').exists()) await wrapper.get(kind === 'avatar' ? '.profile-avatar-camera' : '.profile-cover-drop').trigger('click')
  const input = wrapper.get<HTMLInputElement>('dialog input[type=file]')
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
  mocks.decode.mockReset()
  mocks.encode.mockReset()
  mocks.load.mockResolvedValue(profile())
  mocks.save.mockResolvedValue(profile())
  mocks.decode.mockImplementation(async () => decoded())
  mocks.encode.mockImplementation(async () => jpeg())
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

describe('profile image cropping and save lifecycle', () => {
  it.each(['avatar', 'cover'] as const)('saves the cropped JPEG only after confirmation for %s', async kind => {
    const original = inputFile(), converted = jpeg()
    mocks.encode.mockResolvedValueOnce(converted)
    const wrapper = await editingPage()
    await choose(wrapper, kind, original)
    await flushPromises()
    expect(mocks.decode).toHaveBeenCalledWith(original, expect.any(AbortSignal))
    expect(preview(wrapper, kind).exists()).toBe(false)
    await button(wrapper, '裁剪并使用').trigger('click')
    await flushPromises()
    expect(preview(wrapper, kind).attributes('src')).toBe('blob:profile/prepared.jpg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.save).toHaveBeenCalledTimes(1)
    expect(uploaded(kind)).toBe(converted)
    expect(uploaded(kind)).not.toBe(original)
  })

  it('blocks both the save control and direct form submission while a crop is open', async () => {
    const pending = deferred<ReturnType<typeof decoded>>()
    mocks.decode.mockReturnValueOnce(pending.promise)
    const wrapper = await editingPage()
    await wrapper.get('input[maxlength="30"]').setValue('新的艺名')
    await choose(wrapper, 'avatar', inputFile())
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(mocks.save).not.toHaveBeenCalled()
    pending.resolve(decoded())
    await flushPromises()
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    await button(wrapper, '裁剪并使用').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded('avatar').name).toBe('prepared.jpg')
  })

  it('allows cancelling preparation without a late completion restoring the cancelled draft', async () => {
    const pending = deferred<ReturnType<typeof decoded>>()
    mocks.decode.mockReturnValueOnce(pending.promise)
    const wrapper = await editingPage()
    await choose(wrapper, 'avatar', inputFile())
    expect(button(wrapper.get('dialog'), '取消').element.matches(':disabled')).toBe(false)
    await button(wrapper.get('dialog'), '取消').trigger('click')
    const image = decoded()
    pending.resolve(image)
    await flushPromises()
    expect(image.release).toHaveBeenCalled()
    expect(preview(wrapper, 'avatar').exists()).toBe(false)
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    expect(mocks.save).not.toHaveBeenCalled()
  })

  it('does not allocate a preview or save a late result after the page is unmounted', async () => {
    const pending = deferred<ReturnType<typeof decoded>>()
    mocks.decode.mockReturnValueOnce(pending.promise)
    const wrapper = await editingPage()
    await choose(wrapper, 'avatar', inputFile())
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    pending.resolve(decoded())
    await flushPromises()
    expect(createObjectURL).not.toHaveBeenCalled()
    expect(mocks.save).not.toHaveBeenCalled()
    const replacement = await editingPage()
    expect(preview(replacement, 'avatar').exists()).toBe(false)
  })

  it.each(['avatar', 'cover'] as const)('preserves the last valid %s draft when its replacement cannot be decoded', async kind => {
    const previous = jpeg('previous.jpg')
    mocks.encode.mockResolvedValueOnce(previous)
    const wrapper = await editingPage()
    await choose(wrapper, kind, previous)
    await flushPromises()
    await button(wrapper, '裁剪并使用').trigger('click')
    await flushPromises()
    await wrapper.get(kind === 'avatar' ? '.profile-avatar-camera' : '.profile-cover-drop').trigger('click')
    await flushPromises()
    mocks.decode.mockRejectedValueOnce(new Error('图片无法读取，请换一张重试'))
    await choose(wrapper, kind, inputFile())
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('图片无法读取')
    await button(wrapper.get('dialog'), '取消').trigger('click')
    expect(preview(wrapper, kind).attributes('src')).toBe('blob:profile/previous.jpg')
    expect(revokeObjectURL).not.toHaveBeenCalledWith('blob:profile/previous.jpg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded(kind)).toBe(previous)
  })

  it('opens a cover drop in the editor and sends the file only on profile save', async () => {
    const original = inputFile()
    const wrapper = await editingPage()
    const dropArea = wrapper.get('.profile-cover-drop')
    const dataTransfer = { types: ['Files'], dropEffect: 'none', files: [original] }
    await dropArea.trigger('dragover', { dataTransfer })
    expect(dropArea.classes()).toContain('is-drop-target')
    await dropArea.trigger('drop', { dataTransfer })
    await flushPromises()
    expect(dropArea.classes()).not.toContain('is-drop-target')
    expect(mocks.decode).toHaveBeenCalledWith(original, expect.any(AbortSignal))
    expect(wrapper.get('dialog canvas').attributes('height')).toBe('400')
    await button(wrapper, '裁剪并使用').trigger('click')
    await flushPromises()
    expect(mocks.save).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded('cover').name).toBe('prepared.jpg')
  })

  it('can reframe an already saved avatar from its owner image URL', async () => {
    const stored = jpeg('stored.jpg')
    mocks.load.mockResolvedValue({ ...profile(), avatarKey: 'saved' })
    const fetchImage = vi.fn().mockResolvedValue({ ok: true, blob: async () => stored })
    vi.stubGlobal('fetch', fetchImage)
    const wrapper = await editingPage()
    await wrapper.get('.profile-avatar-camera').trigger('click')
    await flushPromises()
    expect(fetchImage).toHaveBeenCalledWith('/avatar/saved', expect.objectContaining({ credentials: 'same-origin' }))
    expect(mocks.decode).toHaveBeenCalledWith(stored, expect.any(AbortSignal))
    await wrapper.get('dialog input[type="range"]').setValue('2')
    await wrapper.get('dialog canvas').trigger('keydown', { key: 'ArrowRight' })
    expect(mocks.draw).toHaveBeenLastCalledWith(expect.any(HTMLCanvasElement), expect.any(Image), 'avatar', { zoom: 2, x: 0.48, y: 0.5 })
    await button(wrapper, '裁剪并使用').trigger('click')
    await flushPromises()
    await wrapper.get('.profile-avatar-camera').trigger('click')
    await flushPromises()
    expect(mocks.draw).toHaveBeenLastCalledWith(expect.any(HTMLCanvasElement), expect.any(Image), 'avatar', { zoom: 2, x: 0.48, y: 0.5 })
  })

  it('keeps a new selection when an older cancelled decoding completes later', async () => {
    const old = deferred<ReturnType<typeof decoded>>(), current = deferred<ReturnType<typeof decoded>>()
    mocks.decode.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const wrapper = await editingPage()
    await choose(wrapper, 'avatar', inputFile())
    await button(wrapper.get('dialog'), '取消').trigger('click')
    await choose(wrapper, 'avatar', inputFile())
    old.resolve(decoded())
    await flushPromises()
    expect(wrapper.get<HTMLButtonElement>('button[type="submit"]').element.disabled).toBe(true)
    expect(preview(wrapper, 'avatar').exists()).toBe(false)
    const latest = jpeg('latest.jpg')
    mocks.encode.mockResolvedValueOnce(latest)
    current.resolve(decoded())
    await flushPromises()
    await button(wrapper, '裁剪并使用').trigger('click')
    await flushPromises()
    expect(preview(wrapper, 'avatar').attributes('src')).toBe('blob:profile/latest.jpg')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploaded('avatar')).toBe(latest)
  })
})
