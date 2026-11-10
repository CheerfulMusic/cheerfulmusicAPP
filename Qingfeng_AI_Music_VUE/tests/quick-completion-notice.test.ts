// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App, type PropType } from 'vue'
import QuickCompletionNotice from '../src/modules/studio/components/QuickCompletionNotice.vue'
import { backgroundQuick, followQuickInBackground } from '../src/modules/studio/model/background-quick'

const mock = vi.hoisted(() => ({ get: vi.fn(), latest: vi.fn(), owner: 'owner-a' }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: {}, query: {} }) }))
vi.mock('../src/shared/http/api-client', () => ({ currentCreatorId: () => mock.owner }))
vi.mock('../src/modules/studio/api/quick-creation', () => ({ getQuickCreation: mock.get, latestQuickCreation: mock.latest }))
let app: App | undefined

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  backgroundQuick.clear()
  mock.owner = 'owner-a'
  mock.get.mockResolvedValue({ id: 'flow-a', status: 'SUCCEEDED' })
  app = createApp(QuickCompletionNotice)
  app.component('RouterLink', defineComponent({
    props: { to: { type: Object as PropType<{ path: string }>, required: true } },
    setup: (props, { slots }) => () => h('a', { href: props.to.path }, slots.default?.()),
  }))
  app.mount(document.body.appendChild(document.createElement('div')))
})

afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  vi.useRealTimers()
})

it('links to the completed original task from another page without navigating on its own', async () => {
  followQuickInBackground({ owner: 'owner-a', workId: 'work-a', flowId: 'flow-a', status: 'RUNNING' })
  await vi.advanceTimersByTimeAsync(8000)
  expect(mock.get).toHaveBeenCalledWith('work-a', 'flow-a')
  expect(document.querySelector('a')?.getAttribute('href')).toBe('/works/work-a')
  document.querySelector('button')?.click()
  await nextTick()
  expect(document.querySelector('[role="status"]')).toBeNull()
})

it('stops polling an unconfirmed lyric request so other songs can still notify', async () => {
  mock.get.mockResolvedValueOnce({ id: 'flow-a', status: 'SUBMISSION_UNKNOWN', errorCode: 'LYRICS_SUBMISSION_UNKNOWN' })
  followQuickInBackground({ owner: 'owner-a', workId: 'work-a', flowId: 'flow-a', status: 'WRITING' })
  followQuickInBackground({ owner: 'owner-a', workId: 'work-b', flowId: 'flow-b', status: 'RUNNING' })

  await vi.advanceTimersByTimeAsync(16000)
  expect(mock.get.mock.calls).toEqual([['work-a', 'flow-a'], ['work-b', 'flow-b']])
  expect(backgroundQuick.has('work-a')).toBe(false)
  expect(document.querySelector('a')?.getAttribute('href')).toBe('/works/work-b')
})

it('clears the pending notification on identity change, even if a lookup is in flight', async () => {
  let finish!: (result: unknown) => void
  mock.get.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  followQuickInBackground({ owner: 'owner-a', workId: 'work-a', flowId: 'flow-a' })
  await vi.advanceTimersByTimeAsync(8000)
  mock.owner = 'owner-b'
  window.dispatchEvent(new Event('qingfeng:identity-changed'))
  finish({ id: 'flow-a', status: 'SUCCEEDED' })
  await Promise.resolve()
  await nextTick()
  expect(backgroundQuick.size).toBe(0)
  expect(document.querySelector('[role="status"]')).toBeNull()
})
