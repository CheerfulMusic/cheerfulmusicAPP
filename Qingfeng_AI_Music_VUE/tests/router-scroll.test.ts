import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { scrollBehavior } from '../src/app/router/scroll'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: {} },
    { path: '/works/:id', name: 'work-detail', component: {} },
    { path: '/create', name: 'studio', component: {} },
  ],
})
const route = (url: string) => {
  const resolved = router.resolve(url)
  return { ...resolved, name: resolved.name ?? undefined }
}
afterEach(() => vi.unstubAllGlobals())

it.each([
  ['/works/a?revision=2', '/works/a?revision=1'],
  ['/works/a?revision=3&language=en', '/works/a?revision=2&language=zh-CN'],
  ['/works/a?revision=2#lyrics', '/works/a?revision=1#lyrics'],
])('keeps the current viewport when only the work editor query changes: %s', (to, from) => {
  expect(scrollBehavior(route(to), route(from), null)).toBe(false)
})

it.each([
  ['/create?draft=a', '/create'],
  ['/create?draft=a&mode=custom', '/create?draft=a&mode=quick'],
  ['/create?work=a&mode=custom', '/create?work=a&part=1'],
])('preserves the viewport on internal studio query changes: %s', (to, from) => {
  expect(scrollBehavior(route(to), route(from), null)).toBe(false)
})

it.each([
  ['/works/b?revision=2', '/works/a?revision=1'],
  ['/works/a', '/'],
  ['/create?draft=b', '/create?draft=a'],
  ['/create?work=b', '/create?work=a'],
  ['/create?mode=quick', '/create?work=a&mode=quick'],
])('starts normal page navigation at the top: %s', (to, from) => {
  expect(scrollBehavior(route(to), route(from), null)).toEqual({ top: 0 })
})

it('restores browser history positions before applying the editor query rule', () => {
  const saved = { left: 0, top: 640 }
  expect(scrollBehavior(route('/works/a?revision=1'), route('/works/a?revision=2'), saved)).toBe(saved)
  expect(scrollBehavior(route('/#songs'), route('/works/a'), saved)).toBe(saved)
  expect(scrollBehavior(route('/create?draft=a&mode=quick'), route('/create?draft=a&mode=custom'), saved)).toBe(saved)
})

it('only preserves draft-to-work scroll for the corresponding internal promotion', () => {
  const draft = route('/create?draft=a&mode=quick')
  const work = route('/create?work=new&mode=quick')
  expect(scrollBehavior(work, draft, null)).toEqual({top:0})
  vi.stubGlobal('window', {history:{state:{studioDraftPromotion:{draft:'a',work:'new'}}}})
  expect(scrollBehavior(work, draft, null)).toBe(false)
  expect(scrollBehavior(route('/create?work=other'), draft, null)).toEqual({top:0})
  expect(scrollBehavior(work, route('/create?draft=b'), null)).toEqual({top:0})
})

it.each([false, true])('still scrolls to a newly requested anchor with reduced motion %s', (reduced) => {
  vi.stubGlobal('window', { matchMedia: () => ({ matches: reduced }) })
  expect(scrollBehavior(route('/works/a#lyrics'), route('/works/a'), null)).toEqual({
    el: '#lyrics', top: 100, behavior: reduced ? 'auto' : 'smooth',
  })
})
