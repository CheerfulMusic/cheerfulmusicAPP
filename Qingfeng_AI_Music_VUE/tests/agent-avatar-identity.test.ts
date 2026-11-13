// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { setLocale } from '../src/shared/i18n'

const mocks = vi.hoisted(() => ({ clear: vi.fn(), refresh: vi.fn(), start: vi.fn(), stop: vi.fn(), current: vi.fn(), state: vi.fn() }))
vi.mock('../src/modules/agent/composables/useProfileAvatar', async () => {
  const { ref } = await import('vue')
  return { useProfileAvatar: () => ({ imageUrl: ref('/brand/music-person.svg'), refresh: vi.fn(async () => undefined), clear: vi.fn() }) }
})
vi.mock('../src/modules/agent/composables/useUserAvatar', async () => {
  const { ref } = await import('vue')
  return { useUserAvatar: () => ({ avatarUrl: ref('/api/app/avatar/image?v=old'), busy: ref(false), error: ref(''), clear: mocks.clear, refresh: mocks.refresh, upload: vi.fn() }) }
})
vi.mock('../src/modules/agent/composables/useAvatarGeneration', async () => {
  const { ref } = await import('vue')
  return { useAvatarGeneration: () => ({ message: ref(''), available: ref(true), start: mocks.start, stop: mocks.stop }) }
})
vi.mock('../src/modules/agent/api/chat', () => ({ getCurrentConversation: mocks.current, getConversationState: mocks.state, getConversationHistory: vi.fn(), sendMessage: vi.fn(), resetConversation: vi.fn() }))
vi.mock('../src/shared/credits/useCredits', () => ({ useCredits: () => ({ credits: null, refreshCredits: vi.fn(async () => undefined) }) }))
import AgentStudioPage from '../src/modules/agent/pages/AgentStudioPage.vue'

beforeEach(() => { vi.clearAllMocks(); setLocale('zh-CN'); mocks.refresh.mockResolvedValue(undefined); mocks.current.mockResolvedValue({ id: 'c1' }); mocks.state.mockResolvedValue({ messages: [], songCard: null, taskId: null, action: null, shareUrl: null, liked: false }) })
afterEach(() => vi.useRealTimers())

it('clears and reloads the avatar when creator identity changes even if display fields do not', async () => {
  const wrapper = mount(AgentStudioPage, { global: { plugins: [createPinia()] } })
  try {
    await flushPromises(); mocks.clear.mockClear(); mocks.refresh.mockClear(); mocks.start.mockClear()
    window.dispatchEvent(new Event('qingfeng:identity-changed'))
    window.dispatchEvent(new Event('qingfeng:identity-changed'))
    expect(mocks.clear).toHaveBeenCalled()
    await flushPromises()
    expect(mocks.refresh).toHaveBeenCalledOnce()
    expect(mocks.start).toHaveBeenCalledOnce()
  } finally { wrapper.unmount() }
  mocks.clear.mockClear()
  window.dispatchEvent(new Event('qingfeng:identity-changed'))
  expect(mocks.clear).not.toHaveBeenCalled()
})
