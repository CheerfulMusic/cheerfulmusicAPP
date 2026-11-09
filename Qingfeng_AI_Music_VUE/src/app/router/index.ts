import { installCreationLoginGate } from '../../shared/identity/creation-login-gate'
import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import { platform } from '../../platform'
import { scrollBehavior } from './scroll'

export const router = createRouter({
  history: platform.kind === 'web' ? createWebHistory() : createWebHashHistory(),
  scrollBehavior,
  routes: [
    { path: '/', name: 'home', component: () => import('../../modules/home/pages/PremiumHomePage.vue') },
    { path: '/create', name: 'studio', component: () => import('../../modules/studio/pages/DraftStudioPage.vue') },
    { path: '/works', name: 'works', component: () => import('../../modules/works/pages/WorkLibraryPage.vue') },
    { path: '/works/:id', name: 'work-detail', component: () => import('../../modules/works/pages/WorkDetailPage.vue') },
    { path: '/gift/:token', name: 'gift', component: () => import('../../modules/gift/pages/PublicGiftPage.vue') },
    { path: '/account', name: 'identity', component: () => import('../../modules/identity/pages/IdentityPage.vue') },
    { path: '/legal/terms', component: () => import('../../modules/legal/pages/LegalPage.vue') },
    { path: '/legal/privacy', component: () => import('../../modules/legal/pages/LegalPage.vue') },
    { path: '/recharge', name: 'recharge', component: () => import('../../modules/identity/pages/RechargePage.vue') },
    { path: '/analytics', name: 'analytics', component: () => import('../../modules/analytics/pages/AnalyticsPage.vue') },
    ...(import.meta.env.DEV ? [{ path: '/payment-preview', name: 'payment-preview', component: () => import('../../modules/identity/pages/PaymentPreviewPage.vue') }] : []),
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue') },
  ],
})

installCreationLoginGate(router)
