import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './app/App.vue'
import { router } from './app/router'
import './app/styles/theme-base.css'
import './app/styles/home.css'
import './app/styles/workspace.css'
import './app/styles/work-detail.css'
import './app/styles/consumer.css'
import './app/styles/i18n.css'
import './app/styles/seo-landing.css'
import './app/styles/generation-dialog.css'
import './app/styles/creation.css'
import './app/styles/credits.css'
import './app/styles/playback-lyrics.css'
import './app/styles/studio-refinement.css'
import './app/styles/audio-preparation.css'
import './app/styles/section-editor.css'
import './app/styles/story-focus.css'
import './app/styles/music-settings.css'
import './app/styles/explore-music.css'
import './app/styles/home-creation-guide.css'
import './app/styles/home-light.css'
import './app/styles/brand-theme.css'
import './app/styles/agent.css'
import './app/styles/global-player.css'
import { updatePageLocale } from './shared/i18n'
import { platform } from './platform'
import { installPageLeaveTracking, trackPageView } from './shared/analytics/track'

document.querySelectorAll<HTMLLinkElement>('link[data-gift-styles]').forEach(link => { link.media = 'all' })
router.afterEach(to => {
  const preview = document.getElementById('qf-gift-preview')
  if (preview && !preview.hidden && to.name !== 'gift') preview.remove()
})
const isWeb = platform.kind === 'web'
updatePageLocale(isWeb ? window.location.pathname : '')
router.afterEach((to, _from, failure) => {
  if (!failure) updatePageLocale(isWeb ? to.path : '')
})
installPageLeaveTracking()
router.afterEach(to => { trackPageView(String(to.name ?? 'unknown')) })
const app = createApp(App).use(createPinia()).use(router)
const mountApp = () => app.mount('#app')
// Leave the public landing content in place while the router loads the first page.
if (isWeb && document.querySelector('#app > .seo-landing')) {
  void router.isReady().then(mountApp, mountApp)
} else {
  mountApp()
}
