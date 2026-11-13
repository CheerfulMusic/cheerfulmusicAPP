// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent } from 'vue'
const api = vi.hoisted(() => ({ owner: 'owner-a', profile: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({ currentCreatorId: () => api.owner, ensureCreator: async () => undefined }))
vi.mock('../src/modules/artist/api/artist', () => ({ getArtistProfile: api.profile, ownerAvatarUrl: (key: string) => `/avatar/${key}` }))
import { useProfileAvatar } from '../src/modules/agent/composables/useProfileAvatar'
it('reloads a saved profile avatar and ignores an old account response', async () => {
  let avatar!: ReturnType<typeof useProfileAvatar>
  const wrapper = mount(defineComponent({ setup() { avatar = useProfileAvatar(); return () => null } }))
  try {
    api.owner = 'owner-a'
    api.profile.mockResolvedValue({ avatarKey: 'saved-image' })
    await avatar.refresh()
    expect(avatar.imageUrl.value).toBe('/avatar/saved-image')
    let resolve!: (value: unknown) => void
    api.profile.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const pending = avatar.refresh(); await flushPromises()
    avatar.clear(); api.owner = 'owner-b'
    resolve({ avatarKey: 'old-private-image' }); await pending
    expect(avatar.imageUrl.value).toBe('')
    api.profile.mockResolvedValue({ avatarKey: null }); await avatar.refresh()
    expect(avatar.imageUrl.value).toBe('')
  } finally { wrapper.unmount() }
})
