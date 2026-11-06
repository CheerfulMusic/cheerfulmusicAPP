import type { RouterScrollBehavior } from 'vue-router'

export const scrollBehavior: RouterScrollBehavior = (to, from, savedPosition) => {
  if (savedPosition) return savedPosition

  // 保存歌词只更新版本参数；不要打断用户当前的滚动和编辑位置。
  if (to.name === 'work-detail' && from.name === 'work-detail'
    && to.path === from.path && to.hash === from.hash) return false

  return to.hash
    ? { el: to.hash, top: 100, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' }
    : { top: 0 }
}
