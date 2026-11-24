<script setup lang="ts">
import FilingLink from "../../shared/ui/FilingLink.vue"
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { locale } from '../../shared/i18n'
import { landingLinks, sitePage } from '../../shared/i18n/site-seo'

const props = defineProps<{ path: string }>()
const route = useRoute()
const page = computed(() => sitePage(props.path, locale.value))
</script>

<template>
  <main v-if="page" class="seo-landing">
    <h1>{{ page.heading }}</h1>
    <p>{{ page.description }}</p>
    <template v-if="page.guide">
      <section v-for="[heading, body] in page.guide.sections" :key="heading">
        <h2>{{ heading }}</h2>
        <p>{{ body }}</p>
      </section>
      <p><a class="p-button" :href="page.guide.appPath">{{ page.guide.cta }}</a></p>
    </template>
    <nav>
      <template v-for="link in landingLinks(page.language, page.path)" :key="link.href">
        <RouterLink v-if="link.lang" :to="{ path: link.href, query: route.query, hash: route.hash, force: true }"
          :lang="link.lang" :hreflang="link.lang">{{ link.text }}</RouterLink>
        <RouterLink v-else :to="link.href">{{ link.text }}</RouterLink>
      </template>
    </nav>
    <slot />
    <footer><FilingLink /></footer>
  </main>
</template>
