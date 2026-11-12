// @vitest-environment jsdom
import {expect,it} from 'vitest'
import {createApp,h,nextTick} from 'vue'
import {createRouter,createMemoryHistory,RouterView} from 'vue-router'
import LegalPage from '../src/modules/legal/pages/LegalPage.vue'
import terms from '../src/modules/legal/content/terms-2026-11-13-draft-2.json'
async function open(path:string){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/legal/terms',component:LegalPage},{path:'/legal/privacy',component:LegalPage}]});await router.push(path);await router.isReady();const root=document.createElement('div'),app=createApp({render:()=>h(RouterView)});app.use(router);app.mount(root);await nextTick();return{root,app}}
it('renders every supplied terms paragraph and an explicit source version without manufacturing a translation',async()=>{const {root,app}=await open('/legal/terms');expect(root.querySelector('h1')?.textContent).toBe('用户服务条款（含积分与退款规则）');expect(root.querySelectorAll('.legal-body p')).toHaveLength(terms.paragraphs.length);expect(root.textContent).toContain('2026-11-13-draft-2');expect(root.textContent).toContain('Refunds, Cancellation and Consumer Rights');expect(root.querySelector('.legal-body')?.getAttribute('lang')).toBe('en');app.unmount()})
it('does not present missing privacy text as published terms or obtain fictional consent',async()=>{const {root,app}=await open('/legal/privacy');expect(root.querySelector('h1')?.textContent).toBe('隐私政策');expect(root.textContent).toContain('隐私政策正文待提供');expect(root.querySelector('.legal-body')).toBeNull();expect(root.querySelector('input[type=checkbox]')).toBeNull();app.unmount()})
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import previous from '../src/modules/legal/content/terms-2026-11-13-draft-1.json'
it('preserves the supplied draft and records the sole new-user bonus amendment',()=>{
  const oldFile=readFileSync('src/modules/legal/content/terms-2026-11-13-draft-1.json')
  expect(terms.amendedFromSha256).toBe(createHash('sha256').update(oldFile.toString('utf8').replace(/\r\n/g,'\n')).digest('hex'))
  expect(terms.originalSourceSha256).toBe(previous.sourceSha256)
  expect(terms.paragraphs).toEqual(previous.paragraphs.map(p=>p.replace('one-time award of 100 Promotional Credits','one-time award of 50 Promotional Credits')))
})

