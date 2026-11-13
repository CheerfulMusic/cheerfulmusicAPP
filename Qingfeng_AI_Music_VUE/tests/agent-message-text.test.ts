// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import AgentMessageText from '../src/modules/agent/components/AgentMessageText.vue'
it('renders emphasis and quotes without rendering HTML supplied by the model', () => {
  const wrapper = mount(AgentMessageText, { props: { text: '**已保存**\n> 新歌词\n<img src=x onerror=alert(1)>' } })
  expect(wrapper.find('strong').text()).toBe('已保存')
  expect(wrapper.find('.quoted').text()).toBe('新歌词')
  expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.text()).toContain('<img')
})
