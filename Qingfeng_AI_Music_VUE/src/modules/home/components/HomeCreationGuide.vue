<script setup lang="ts">
import { t, locale } from '../../../shared/i18n'
</script>

<template>
  <section id="how" class="simple-third p-width" aria-labelledby="creation-guide-title">
    <div class="simple-third-content">
      <h2 id="creation-guide-title">{{ t('你的第一首歌，只需三步。') }}</h2>
      <p class="simple-third-description">{{ t('从一个想法开始，听见属于你的创作。') }}</p>
      <ol class="simple-third-steps">
        <li>
          <span aria-hidden="true">01</span>
          <h3>{{ t('写下灵感') }}</h3>
          <p>{{ t('一句话、一种心情，') }}<br>{{ locale === 'en' ? ' ' : '' }}{{ t('也可以是你写好的歌词。') }}</p>
        </li>
        <li>
          <span aria-hidden="true">02</span>
          <h3>{{ t('选择声音') }}</h3>
          <p>{{ t('选择喜欢的风格和人声，') }}<br>{{ locale === 'en' ? ' ' : '' }}{{ t('把歌词改成自己的表达。') }}</p>
        </li>
        <li>
          <span aria-hidden="true">03</span>
          <h3>{{ t('生成试听') }}</h3>
          <p>{{ t('听听你的新作品，') }}<br>{{ locale === 'en' ? ' ' : '' }}{{ t('满意后保存或分享。') }}</p>
        </li>
      </ol>
      <RouterLink class="p-button simple-third-create" to="/create">
        {{ t('立即生成我的歌') }} <span aria-hidden="true">↗</span>
      </RouterLink>
    </div>
  </section>
</template>
