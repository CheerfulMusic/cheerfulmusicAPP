import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import { platform } from '../../platform'

export const router = createRouter({
  history: platform.kind === 'web' ? createWebHistory() : createWebHashHistory(),
  scrollBehavior: (to) => to.hash ? { el: to.hash, top: 100, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' } : { top: 0 },
  routes: [
    { path: '/', name: 'home', component: () => import('../../modules/home/pages/PremiumHomePage.vue') },
    { path: '/create', name: 'studio', component: () => import('../../modules/studio/pages/DraftStudioPage.vue') },
    { path: '/works', name: 'works', component: () => import('../../modules/works/pages/WorkLibraryPage.vue') },
    { path: '/works/:id', name: 'work-detail', component: () => import('../../modules/works/pages/WorkDetailPage.vue') },
    { path: '/gift/:token', name: 'gift', component: () => import('../../modules/gift/pages/PublicGiftPage.vue') },
    { path: '/account', name: 'identity', component: () => import('../../modules/identity/pages/IdentityPage.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue') },
  ],
})
