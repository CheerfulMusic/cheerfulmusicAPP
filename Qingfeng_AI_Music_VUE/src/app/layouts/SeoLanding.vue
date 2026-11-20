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
    <nav>
      <template v-for="link in landingLinks(page.language)" :key="link.href">
        <RouterLink v-if="link.lang" :to="{ path: link.href, query: route.query, hash: route.hash, force: true }"
          :lang="link.lang" :hreflang="link.lang">{{ link.text }}</RouterLink>
        <RouterLink v-else :to="link.href">{{ link.text }}</RouterLink>
      </template>
    </nav>
    <slot />
    <footer><FilingLink /></footer>
  </main>
</template>
