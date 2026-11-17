// @vitest-environment happy-dom
import { beforeEach,afterEach,it,expect,vi } from 'vitest'
import { mount,flushPromises } from '@vue/test-utils'
import AgentVideoProgress from '../src/modules/agent/components/AgentVideoProgress.vue'
import { getJson, requestJson } from '../src/shared/http/api-client'
vi.mock('../src/shared/http/api-client',()=>({getJson:vi.fn(),requestJson:vi.fn(),ensureCreator:vi.fn().mockResolvedValue(undefined)}))
const id='12345678-1234-1234-1234-123456789abc'
beforeEach(()=>{vi.useFakeTimers();vi.mocked(getJson).mockReset()})
afterEach(()=>vi.useRealTimers())
it('polls only the existing task and exposes the completed owned video',async()=>{
 vi.mocked(getJson).mockResolvedValueOnce({id,state:'RUNNING',shots:[{state:'RUNNING'}]}).mockResolvedValueOnce({id,state:'READY',shots:[{state:'READY'}]})
 const wrapper=mount(AgentVideoProgress,{props:{projectId:id}});await flushPromises()
 expect(wrapper.find('video').exists()).toBe(false)
 await vi.advanceTimersByTimeAsync(10000);await flushPromises()
 expect(wrapper.find('video').attributes('src')).toBe(`/api/app/music-videos/${id}/content`)
 await vi.advanceTimersByTimeAsync(30000);expect(getJson).toHaveBeenCalledTimes(2);wrapper.unmount()
})
it('stops polling on logout and never displays a stale response',async()=>{
 let finish!:(value:unknown)=>void
 vi.mocked(getJson).mockImplementation(()=>new Promise(resolve=>{finish=resolve}))
 const wrapper=mount(AgentVideoProgress,{props:{projectId:id}})
 window.dispatchEvent(new Event('qingfeng:identity-changed'))
 finish({id,state:'READY',shots:[]});await flushPromises()
 expect(wrapper.find('video').exists()).toBe(false);wrapper.unmount()
})
it('bounds network retries without generating a new task',async()=>{
 vi.mocked(getJson).mockRejectedValue(new Error('offline'))
 const wrapper=mount(AgentVideoProgress,{props:{projectId:id}});await flushPromises()
 await vi.advanceTimersByTimeAsync(60000);await flushPromises()
 expect(getJson).toHaveBeenCalledTimes(3);expect(wrapper.find('button').exists()).toBe(true);wrapper.unmount()
})

it('shows rejection and retries only the edited failed shot on the original project',async()=>{
 vi.mocked(getJson).mockResolvedValueOnce({id,state:'FAILED',shots:[{shot_index:0,state:'READY'},{shot_index:1,state:'FAILED',prompt:'旧场景',error_code:'VIDEO_FACE_REJECTED'}]}).mockResolvedValueOnce({id,state:'RUNNING',shots:[{state:'READY'},{state:'QUEUED'}]})
 vi.mocked(requestJson).mockResolvedValue({projectId:id})
 const wrapper=mount(AgentVideoProgress,{props:{projectId:id}});await flushPromises()
 expect(wrapper.text()).toContain('疑似包含真人面孔');expect(wrapper.find('button').attributes('disabled')).toBeDefined()
 await wrapper.find('textarea').setValue('水彩灯塔与海浪');await wrapper.find('button').trigger('click');await flushPromises()
 expect(requestJson).toHaveBeenCalledWith(`app/music-videos/${id}/retry-shot`,'POST',{shotIndex:1,prompt:'水彩灯塔与海浪'})
 expect(wrapper.text()).toContain('镜头生成中');wrapper.unmount()
})
