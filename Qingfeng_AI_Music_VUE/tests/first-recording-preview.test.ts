// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { expect, it, vi } from 'vitest'
import FirstRecordingPreview from '../src/modules/works/components/FirstRecordingPreview.vue'
import { usePlayer } from '../src/shared/player/store'
it('plays the first recording identified by the original task even after reopening', async () => {
  const pinia = createPinia()
  const player = usePlayer(pinia)
  const toggle = vi.spyOn(player, 'toggle').mockResolvedValue()
  const open = () => mount(FirstRecordingPreview, { props: { workId: 'work', assetId: 'ready', title: 'Song' }, global: { plugins: [pinia] } })
  const first = open()
  try {
    await first.get('button').trigger('click')
    expect(toggle).toHaveBeenCalledWith(expect.objectContaining({ id:'ready', audio:'/api/app/works/work/audio/ready' }))
    first.unmount()
    const reopened = open()
    await reopened.get('button').trigger('click')
    expect(toggle).toHaveBeenCalledTimes(2)
    reopened.unmount()
  } finally { vi.restoreAllMocks() }
})
