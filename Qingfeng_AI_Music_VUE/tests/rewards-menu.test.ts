// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterLink } from 'vue-router'
import { describe, it, expect } from 'vitest'
import RewardsMenu from '../src/shared/ui/RewardsMenu.vue'

describe('credit rewards menu', () => {
  it('opens all three destinations and closes on Escape and outside clicks', async () => {
    const router = createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]})
    await router.push('/'); await router.isReady()
    const wrapper = mount(RewardsMenu,{attachTo:document.body,global:{plugins:[router],components:{RouterLink}}})
    const button=wrapper.get('button')
    await button.trigger('click')
    expect(button.attributes('aria-expanded')).toBe('true')
    const links=wrapper.findAll('nav a')
    expect(links).toHaveLength(3)
    expect(links.map(a=>a.attributes('href'))).toEqual(['/account#invite-rewards','/works','mailto:labelmanager@cheerfulmusic.co.uk?subject=Cheerful%20Music%20Feedback'])
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
})
