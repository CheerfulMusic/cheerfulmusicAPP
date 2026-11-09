<script setup lang="ts">
import { t } from '../../shared/i18n'

/** Links without a shipped destination stay as plain text; no dead links or unbacked claims. */
const products = [
  { label: 'AI 音乐创作', to: '/create' },
  { label: '我的作品', to: '/works' },
  { label: '全球发行', to: '' },
  { label: '高端定制服务', to: '' },
]
const support = [
  { label: '使用指南', to: '' },
  { label: '常见问题 (FAQ)', to: '' },
  { label: '联系客服', to: '' },
  { label: '反馈建议', to: '' },
]
const legal = [
  { label: '用户服务条款（含积分与退款规则）', href: '/legal/terms.html' },
  { label: '隐私政策', href: '/legal/privacy.html' },
  { label: '音乐作品发布与社区规则', href: '/legal/community.html' },
  { label: '版权与授权说明', href: '' },
]
</script>

<template>
  <footer class="site-footer">
    <div class="footer-wave" aria-hidden="true">
      <svg viewBox="0 0 1440 120" preserveAspectRatio="none" focusable="false">
        <defs>
          <linearGradient id="qfFooterWaveGlow" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#ff6a3d"/>
            <stop offset="0.45" stop-color="#f12a12"/>
            <stop offset="1" stop-color="#8c150e"/>
          </linearGradient>
          <linearGradient id="qfFooterWaveBody" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#321013"/>
            <stop offset="1" stop-color="#120f14"/>
          </linearGradient>
        </defs>
        <path d="M0 74 C 160 34, 300 76, 480 70 C 660 64, 760 20, 940 4 C 1120 -12, 1280 6, 1440 48 L1440 120 L0 120 Z" fill="url(#qfFooterWaveGlow)"/>
        <path d="M0 88 C 160 48, 300 90, 480 84 C 660 78, 760 36, 940 22 C 1120 8, 1280 34, 1440 74 L1440 120 L0 120 Z" fill="url(#qfFooterWaveBody)"/>
      </svg>
    </div>

    <div class="footer-body">
      <div class="footer-inner">
        <div class="footer-main">
          <section class="footer-brand" :aria-label="t('Cheerful Music 青风音乐首页')">
            <RouterLink class="footer-brand-link" to="/" :aria-label="t('Cheerful Music 青风音乐首页')">
              <img src="/brand/logo-symbol.svg" alt="" width="42" height="42">
              <strong lang="en">Cheerful Music</strong>
            </RouterLink>
            <p class="footer-tagline-en" lang="en">Everyone Can Be a Singer.</p>
            <p class="footer-tagline-zh">{{ t('让每个人都能创作和发布自己的音乐。') }}</p>
            <div class="footer-social" aria-label="Social media">
              <span class="footer-social-icon" title="TikTok / 抖音" aria-label="TikTok / Douyin" role="img">
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M16.6 2h-3.1v13.1a2.7 2.7 0 1 1-2.2-2.7v-3.2a5.9 5.9 0 1 0 5.3 5.9V8.7a6.8 6.8 0 0 0 4 1.3V6.9A4 4 0 0 1 16.6 2Z"/></svg>
              </span>
              <span class="footer-social-icon" title="Instagram" aria-label="Instagram" role="img">
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="3" y="3" width="18" height="18" rx="5" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="12" cy="12" r="4" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="17.4" cy="6.6" r="1.15" fill="currentColor"/></svg>
              </span>
              <span class="footer-social-icon" title="YouTube" aria-label="YouTube" role="img">
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="2.4" y="5.4" width="19.2" height="13.2" rx="4" fill="none" stroke="currentColor" stroke-width="1.8"/><path fill="currentColor" d="M10.2 9.1 15.7 12l-5.5 2.9V9.1Z"/></svg>
              </span>
              <span class="footer-social-icon" title="X" aria-label="X" role="img">
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M17.6 2.8h3l-6.6 7.5 7.7 10.9h-6l-4.7-6.2-5.4 6.2H2.6l7-8L2.2 2.8h6.2l4.3 5.7 4.9-5.7Zm-1.1 16.5h1.7L7.6 4.6H5.8l10.7 14.7Z"/></svg>
              </span>
              <span class="footer-social-icon" title="WeChat / 微信" aria-label="WeChat" role="img">
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path fill="currentColor" d="M9.3 3.2c-4 0-7.2 2.6-7.2 5.9 0 1.9 1.1 3.6 2.8 4.7l-.7 2.3 2.7-1.4c.8.2 1.6.3 2.4.3h.6a5.2 5.2 0 0 1-.2-1.4c0-3.2 3.1-5.8 6.9-5.8h.5c-.6-2.7-3.7-4.6-7.8-4.6ZM6.7 7.5a.9.9 0 1 1 0-1.8.9.9 0 0 1 0 1.8Zm5.2 0a.9.9 0 1 1 0-1.8.9.9 0 0 1 0 1.8Z"/><path fill="currentColor" d="M22 14.1c0-2.7-2.7-4.9-6-4.9s-6 2.2-6 4.9 2.7 4.9 6 4.9c.7 0 1.4-.1 2.1-.3l2.2 1.2-.6-2c1.5-.9 2.3-2.3 2.3-3.8Zm-8.2-1.1a.8.8 0 1 1 0-1.6.8.8 0 0 1 0 1.6Zm4.4 0a.8.8 0 1 1 0-1.6.8.8 0 0 1 0 1.6Z"/></svg>
              </span>
            </div>
          </section>

          <nav class="footer-column" :aria-label="t('产品')">
            <h2>{{ t('产品') }}</h2>
            <template v-for="item in products" :key="item.label">
              <RouterLink v-if="item.to" class="footer-link" :to="item.to">{{ t(item.label) }}</RouterLink>
              <span v-else class="footer-link footer-link-static">{{ t(item.label) }}</span>
            </template>
          </nav>

          <nav class="footer-column" :aria-label="t('帮助与支持')">
            <h2>{{ t('帮助与支持') }}</h2>
            <span v-for="item in support" :key="item.label" class="footer-link footer-link-static">{{ t(item.label) }}</span>
          </nav>

          <nav class="footer-column" :aria-label="t('法律与政策')">
            <h2>{{ t('法律与政策') }}</h2>
            <template v-for="item in legal" :key="item.label">
              <a v-if="item.href" class="footer-link" :href="item.href">{{ t(item.label) }}</a>
              <span v-else class="footer-link footer-link-static">{{ t(item.label) }}</span>
            </template>
          </nav>

          <section class="footer-cta" :aria-label="t('立即创作你的第一首歌')">
            <p>{{ t('立即创作你的第一首歌') }}</p>
            <RouterLink class="footer-cta-button" to="/create">{{ t('免费体验') }} <span aria-hidden="true">→</span></RouterLink>
          </section>
        </div>

        <div class="footer-bottom">
          <p class="footer-copyright" lang="en">© 2026 Cheerful Music Limited. All rights reserved.</p>
          <div class="footer-company" lang="en">
            <p>Cheerful Music Limited <span>|</span> Company No. 14502629 <span>|</span> Registered in England and Wales</p>
            <p>Registered Office: Room 130, Building 3, Chiswick Park, 566 Chiswick High Road, London, W4 5YA, United Kingdom</p>
          </div>
        </div>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.site-footer { position: relative; width: 100%; margin-top: 0; }
.footer-wave { display: block; height: 60px; line-height: 0; }
.footer-wave svg { display: block; width: 100%; height: 100%; }
.footer-body { background: #120f14; color: #fff; }
.footer-inner { max-width: 1440px; margin: 0 auto; padding: 18px 48px 24px; }
.footer-main { display: grid; grid-template-columns: 1.55fr 0.85fr 0.9fr 1.5fr 1.05fr; gap: 30px; align-items: start; }

.footer-brand-link { display: inline-flex; align-items: center; gap: 9px; color: #fff; }
.footer-brand-link img { width: 32px; height: 32px; flex: none; }
.footer-brand-link strong { font-size: 21px; font-weight: 700; letter-spacing: -.4px; line-height: 1; }
.footer-tagline-en { margin: 14px 0 0; font-size: 15px; color: #fff; }
.footer-tagline-zh { margin: 6px 0 0; font-size: 13px; color: #c8bfc5; }
.footer-social { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 14px; }
.footer-social-icon { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 8px; background: #ffffff14; color: #fff; }
.footer-social-icon svg { width: 16px; height: 16px; }

.footer-column { display: flex; flex-direction: column; align-items: flex-start; gap: 11px; }
.footer-column h2 { margin: 3px 0 5px; font-size: 14px; font-weight: 700; color: #fff; }
.footer-link { font-size: 13px; line-height: 1.4; color: #d9d2d7; }
a.footer-link:hover { color: #fff; text-decoration: underline; text-underline-offset: 3px; }
.footer-link-static { cursor: default; }

.footer-cta { border-left: 1px solid #ffffff24; padding-left: 30px; }
.footer-cta p { margin: 3px 0 15px; font-size: 14px; color: #fff; }
.footer-cta-button { display: inline-flex; align-items: center; gap: 10px; min-height: 44px; padding: 0 26px; border-radius: 999px; background: linear-gradient(105deg, #ff6338, #f42a12); color: #fff; font-size: 15px; font-weight: 700; white-space: nowrap; }
.footer-cta-button:hover { filter: brightness(1.07); }
.footer-cta-button span { font-size: 17px; font-weight: 400; }

.footer-bottom { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px 32px; margin-top: 42px; padding-top: 18px; border-top: 1px solid #ffffff20; }
.footer-bottom p { margin: 0; font-size: 12px; line-height: 1.65; color: #b9b1b7; }
.footer-company { text-align: right; }
.footer-company span { padding: 0 7px; color: #6f676d; }

@media (max-width: 1180px) {
  .footer-inner { padding-inline: 32px; }
  .footer-main { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px 22px; }
  .footer-brand { grid-column: 1 / -1; }
  .footer-cta { grid-column: 1 / -1; border-left: 0; border-top: 1px solid #ffffff24; padding: 18px 0 0; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
  .footer-cta p { margin: 0; }
}
@media (max-width: 700px) {
  .footer-wave { height: 38px; }
  .footer-inner { padding: 12px 18px 20px; }
  .footer-main { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px 16px; }
  .footer-brand-link strong { font-size: 19px; }
  .footer-brand-link img { width: 28px; height: 28px; }
  .footer-tagline-en { font-size: 14px; }
  .footer-column h2 { font-size: 13px; }
  .footer-link { font-size: 12px; }
  .footer-cta { flex-direction: column; align-items: flex-start; }
  .footer-cta-button { min-height: 40px; font-size: 14px; }
  .footer-bottom { margin-top: 28px; }
  .footer-company { text-align: left; }
}
@media (max-width: 420px) {
  .footer-main { grid-template-columns: 1fr; }
  .footer-company span { padding: 0 5px; }
}
</style>
