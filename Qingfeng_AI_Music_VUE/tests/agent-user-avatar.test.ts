// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({ owner: 'owner-a', ensure: vi.fn(), get: vi.fn(), upload: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({
  currentCreatorId: () => api.owner,
  ensureCreator: api.ensure,
  requestJson: api.get,
  uploadForm: api.upload,
}))
import { useUserAvatar } from '../src/modules/agent/composables/useUserAvatar'
import { useAvatarGeneration } from '../src/modules/agent/composables/useAvatarGeneration'
import { setLocale } from '../src/shared/i18n'
import UserAvatarDialog from '../src/modules/agent/components/UserAvatarDialog.vue'

const saved = { imageUrl: '/api/app/avatar/image', version: 'version-a' }
beforeEach(() => { vi.clearAllMocks(); setLocale('zh-CN'); api.owner = 'owner-a'; api.ensure.mockResolvedValue(undefined) })
afterEach(() => vi.useRealTimers())

describe('user avatar', () => {
  it('uploads a validated image for the identity present at the click', async () => {
    api.upload.mockResolvedValue(saved)
    const avatar = useUserAvatar(), file = new File(['test'], 'avatar.png', { type: 'image/png' })
    expect(await avatar.upload(file)).toBe(true)
    const [path, form] = api.upload.mock.calls[0]!
    expect(path).toBe('app/avatar')
    expect(form.get('expectedCreatorId')).toBe('owner-a')
    expect(form.get('file')).toBe(file)
    expect(avatar.avatarUrl.value).toBe('/api/app/avatar/image?v=version-a')
  })
  it('rejects unsupported files and reports upload failures', async () => {
    const avatar = useUserAvatar()
    expect(await avatar.upload(new File(['x'], 'a.webp', { type: 'image/webp' }))).toBe(false)
    expect(avatar.error.value).toContain('5MB')
    expect(api.upload).not.toHaveBeenCalled()
    api.upload.mockRejectedValue(new Error('network'))
    expect(await avatar.upload(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))).toBe(false)
    expect(avatar.error.value).toContain('上传失败')
  })
  it('discards late reads after the account changes', async () => {
    let resolve!: (value: unknown) => void
    api.get.mockImplementation(() => new Promise(done => { resolve = done }))
    const avatar = useUserAvatar(), pending = avatar.refresh()
    await Promise.resolve(); await Promise.resolve()
    avatar.clear(); api.owner = 'owner-b'; resolve(saved); await pending
    expect(avatar.avatarUrl.value).toBe('')
  })
  it('does not submit an upload after the creator session changes', async () => {
    let resolve!: () => void
    api.ensure.mockImplementation(() => new Promise<void>(done => { resolve = done }))
    const avatar = useUserAvatar(), pending = avatar.upload(new File(['x'], 'a.jpg', { type: 'image/jpeg' }))
    api.owner = 'owner-b'; resolve(); expect(await pending).toBe(false)
    expect(api.upload).not.toHaveBeenCalled()
  })
  it('shows file validation errors in the selected language', async () => {
    setLocale('en')
    const avatar = useUserAvatar()
    await avatar.upload(new File(['x'], 'a.webp', { type: 'image/webp' }))
    expect(avatar.error.value).toContain('Choose a JPG or PNG')
  })
})

describe('avatar generation', () => {
  it('explains unavailable generation while leaving upload available', () => {
    const dialog = mount(UserAvatarDialog, { props: { busy: false, error: '', disabled: false, generationAvailable: false } })
    try {
      expect(dialog.text()).toContain('AI 头像生成暂不可用')
      expect(dialog.find('input[type=file]').attributes('disabled')).toBeUndefined()
      expect(dialog.find('button.avatar-generate').attributes('disabled')).toBeDefined()
    } finally { dialog.unmount() }
  })
  it('resumes a running task and refreshes the avatar when it succeeds', async () => {
    vi.useFakeTimers()
    api.get.mockResolvedValueOnce({ id: 'task', status: 'RUNNING', available: true }).mockResolvedValueOnce({ id: 'task', status: 'SUCCEEDED', available: true })
    const refresh = vi.fn(async () => undefined), scope = effectScope()
    const generation = scope.run(() => useAvatarGeneration(refresh))!
    try {
      generation.start(); await vi.advanceTimersByTimeAsync(0)
      expect(generation.message.value).toContain('正在生成')
      await vi.advanceTimersByTimeAsync(4000)
      expect(refresh).toHaveBeenCalledOnce()
      expect(generation.message.value).toBe('')
    } finally { scope.stop() }
  })
  it('does not apply a previous identity’s completed task', async () => {
    let resolve!: (value: unknown) => void
    api.get.mockImplementation(() => new Promise(done => { resolve = done }))
    const refresh = vi.fn(async () => undefined), scope = effectScope()
    const generation = scope.run(() => useAvatarGeneration(refresh))!
    try {
      generation.start(); await Promise.resolve(); await Promise.resolve()
      generation.stop(); api.owner = 'owner-b'; resolve({ id: 'task', status: 'SUCCEEDED', available: true })
      await Promise.resolve(); await Promise.resolve()
      expect(refresh).not.toHaveBeenCalled()
    } finally { scope.stop() }
  })
  it('shows generation progress in the selected language', async () => {
    setLocale('en'); vi.useFakeTimers()
    api.get.mockResolvedValue({ id: 'task', status: 'RUNNING', available: true })
    const scope = effectScope(), generation = scope.run(() => useAvatarGeneration(async () => undefined))!
    try {
      generation.start(); await vi.advanceTimersByTimeAsync(0)
      expect(generation.message.value).toContain('Creating your avatar')
    } finally { scope.stop() }
  })
})
