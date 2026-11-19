// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { expect, it, vi } from 'vitest'
import FirstRecordingPreview from '../src/modules/works/components/FirstRecordingPreview.vue'
import { listAudio, type AudioAsset } from '../src/modules/works/api/detail'
import { usePlayer } from '../src/shared/player/store'
vi.mock('../src/modules/works/api/detail', async original => ({ ...await original<typeof import('../src/modules/works/api/detail')>(), listAudio: vi.fn() }))
it('previews the saved recording for this task before completion and recovers it on reopen', async () => {
  vi.useFakeTimers()
  const pinia = createPinia()
  const player = usePlayer(pinia)
  const toggle = vi.spyOn(player, 'toggle').mockResolvedValue()
  const open = () => mount(FirstRecordingPreview, { props: { workId: 'work', taskId: 'new', title: 'Song' }, global: { plugins: [pinia] } })
  const asset = (id: string, task: string) => ({ id, workId: 'work', generationTaskId: task, origin: 'GENERATED', createdAt: '2026-11-18' } as AudioAsset)
  vi.mocked(listAudio).mockResolvedValueOnce([asset('old-recording','old')]).mockResolvedValue([asset('ready','new')])
  const first = open()
  try {
    await flushPromises()
    expect(first.find('button').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(2000)
    await first.get('button').trigger('click')
    expect(toggle).toHaveBeenCalledWith(expect.objectContaining({ id:'ready', audio:'/api/app/works/work/audio/ready' }))
    first.unmount()
    const reopened = open()
    await flushPromises()
    expect(reopened.find('button').exists()).toBe(true)
    reopened.unmount()
    await vi.advanceTimersByTimeAsync(4000)
    expect(listAudio).toHaveBeenCalledTimes(3)
  } finally { vi.useRealTimers(); vi.restoreAllMocks() }
})
