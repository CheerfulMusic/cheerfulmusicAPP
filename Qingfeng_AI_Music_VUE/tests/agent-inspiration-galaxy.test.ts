// @vitest-environment happy-dom
import { mount, flushPromises } from '@vue/test-utils'
import { it, expect, vi } from 'vitest'
import InspirationGalaxy from '../src/modules/agent/components/InspirationGalaxy.vue'

const api=vi.hoisted(()=>({list:vi.fn()}))
vi.mock('../src/shared/http/api-client',()=>({getJson:api.list}))
vi.mock('../src/shared/player/store',()=>({usePlayer:()=>({playing:false})}))

it('shows only a ready MV for the selected recording and shows interactive scenery when it has none',async()=>{
  api.list.mockResolvedValue([{id:'other',asset_id:'a2',state:'READY'},{id:'video1',asset_id:'a1',state:'READY'}])
  const panel=mount(InspirationGalaxy,{props:{workId:'work',assetId:'a1'}})
  await flushPromises()
  expect(panel.get('video').attributes('src')).toBe('/api/app/music-videos/video1/content')
  await panel.setProps({assetId:'unfinished'})
  await flushPromises()
  expect(panel.find('video').exists()).toBe(false)
  await panel.get('.tree-zone').trigger('click')
  expect(panel.findAll('.falling-leaf').length).toBeGreaterThan(1)
  await panel.get('.water-zone').trigger('click')
  expect(panel.find('.water-ring').exists()).toBe(true)
  expect(panel.find('.bubble-board').exists()).toBe(false)
  panel.unmount()
})
