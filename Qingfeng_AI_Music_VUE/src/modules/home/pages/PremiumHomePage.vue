<script setup lang="ts">
import { useRoute } from 'vue-router'
import { homeBrand } from '../../../shared/i18n/home-seo'
import HomeHero from '../components/HomeHero.vue'
import ExploreMusic from '../components/ExploreMusic.vue'
import HomeCreationGuide from '../components/HomeCreationGuide.vue'
const route = useRoute()
</script>

<template>
  <div class="p-home">
    <HomeHero :brand="homeBrand(route.path)"/>
    <ExploreMusic/>
    <HomeCreationGuide/>
  </div>
</template>
