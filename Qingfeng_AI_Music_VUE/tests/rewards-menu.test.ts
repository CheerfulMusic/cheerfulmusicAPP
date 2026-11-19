// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { describe, it, expect } from 'vitest'
import HelpPage from '../src/app/pages/HelpPage.vue'
import RewardsMenu from '../src/shared/ui/RewardsMenu.vue'

describe('credit rewards menu', () => {
  it('opens all three destinations and closes on Escape and outside clicks', async () => {
    const router = createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]})
    await router.push('/'); await router.isReady()
    const wrapper = mount(RewardsMenu,{attachTo:document.body,global:{plugins:[router]}})
    const button=wrapper.get('button')
    await button.trigger('click')
    expect(button.attributes('aria-expanded')).toBe('true')
    const links=wrapper.findAll('nav a')
    expect(links).toHaveLength(3)
    expect(links.map(a=>a.attributes('href'))).toEqual(['/account#invite-rewards','/works','/help#feedback'])
    expect(wrapper.text()).toContain('送19积分')
    expect(wrapper.text()).toContain('送2积分')
    expect(wrapper.text()).toContain('送10-100积分')
    document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('nav').exists()).toBe(false)
    await button.trigger('click')
    document.body.dispatchEvent(new Event('pointerdown',{bubbles:true}))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('nav').exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the help page at the feedback section from My Music', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/works', component: { template: '<main />' } },
        { path: '/help', component: HelpPage },
      ],
    })
    await router.push('/works')
    const wrapper = mount({
      components: { RewardsMenu, RouterView },
      template: '<RewardsMenu /><RouterView />',
    }, { global: { plugins: [router] } })
    try {
      await wrapper.get('.rewards-trigger').trigger('click')
      await wrapper.get('.rewards-panel a[href="/help#feedback"]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe('/help#feedback')
      expect(wrapper.get('#feedback h2').text()).toBe('反馈建议')
      expect(wrapper.find('.rewards-panel').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })
})
