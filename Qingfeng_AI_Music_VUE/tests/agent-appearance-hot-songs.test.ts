// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { appearanceKey, defaults, sanitizeAppearance, useChatAppearance } from '../src/modules/agent/composables/useChatAppearance'
import ChatAppearance from '../src/modules/agent/components/ChatAppearance.vue'
const hot = vi.hoisted(() => ({load:vi.fn(),record:vi.fn(),toggle:vi.fn(),queue:vi.fn()}))
vi.mock('../src/modules/artist/api/artist', async original => ({
 ...await original<typeof import('../src/modules/artist/api/artist')>(),
 listDiscoverSongs: async () => ({songs:[],hasMore:false}),
}))
vi.mock('../src/modules/home/model/hot-songs', async () => { const {ref}=await import('vue'); return {loadHotSongs:hot.load,recordPlay:hot.record,sortByHeat:(tracks:any[])=>[...tracks].reverse(),hotFailure:ref(''),hotLoading:ref(false)} })
vi.mock('../src/shared/player/store', () => ({usePlayer:()=>({setQueue:hot.queue,toggle:hot.toggle,current:null,playing:false,starting:false,error:''})}))
import AgentProgressCard from '../src/modules/agent/components/AgentProgressCard.vue'
import { agentProgress } from '../src/modules/agent/model/progress'
import type { Message } from '../src/modules/agent/model/conversation'
import AgentHotSongs from '../src/modules/agent/components/AgentHotSongs.vue'
beforeEach(()=>{localStorage.clear();vi.clearAllMocks()})
it('rejects unsafe stored styles and bounds font size and corners',()=>{
 expect(sanitizeAppearance({font:'__proto__',border:'url(x)',color:'red;display:none',size:900,radius:-8})).toEqual({...defaults,size:20,radius:4})
 expect(sanitizeAppearance(null)).toEqual(defaults)
})
it('updates existing conversation styles, persists and restores defaults',async()=>{
 const Host=defineComponent({components:{ChatAppearance},setup:useChatAppearance,template:'<div :style="style"><ChatAppearance v-model="appearance" @reset="reset"/><p>Existing message</p></div>'})
 const w=mount(Host);await w.get('.literary').trigger('click');await w.get('input[aria-label="字号"]').setValue('18');
 expect(w.attributes('style')).toContain('--chat-size: 18px');expect(w.attributes('style')).toContain('SimSun');
 await nextTick();expect(JSON.parse(localStorage.getItem(appearanceKey)!).font).toBe('literary');w.unmount()
 const restored=mount(Host);expect(restored.attributes('style')).toContain('--chat-size: 18px');await restored.get('.appearance-panel footer button').trigger('click');expect(restored.attributes('style')).toContain('--chat-size: 15px');restored.unmount()
})
it('scrolls the full set of ranked catalog tracks and plays a selected song directly',async()=>{
 const w=mount(AgentHotSongs);await flushPromises();expect(hot.load).toHaveBeenCalledOnce();expect(w.findAll('li').length).toBeGreaterThan(6);expect(w.find('header a').exists()).toBe(false)
 expect(w.get('li .hot-rank').text()).toBe('02')
 expect(w.findAll('li .hot-song-play strong').map(title=>title.text())).not.toContain(w.get('.hot-featured h3').text())
 await w.get('.hot-featured .hot-song-play').trigger('click');expect(hot.queue).toHaveBeenCalledOnce();expect(hot.toggle).toHaveBeenCalledOnce();expect(hot.record).not.toHaveBeenCalled();w.unmount()
})

it.each([['generate_song','正在生成歌曲'],['generate_lyrics','正在生成歌词']])('renders the active %s until the server reports completion',async(action,title)=>{
 const progress=agentProgress({role:'assistant',contentJson:JSON.stringify({kind:'progress',action,stage:'tool_started',step:1,requestKey:'turn',text:'正在处理'})} as Message)!
 const wrapper=mount(AgentProgressCard,{props:{progress}})
 expect(wrapper.get('.generation-wait').text()).toContain(title)
 await wrapper.setProps({progress:{...progress,stage:'tool_finished'}})
 expect(wrapper.find('.generation-wait').exists()).toBe(false)
 wrapper.unmount()
})
