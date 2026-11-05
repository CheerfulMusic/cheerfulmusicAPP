<template>
  <div class="light-brand-scene" aria-hidden="true">
    <div class="brand-ambient"></div>
    <div class="brand-sphere"></div>
    <div class="brand-symbol"></div>
    <div class="brand-orbit"></div>
    <div v-for="side in ['left', 'right']" :key="side" class="brand-sound-wave" :class="side">
      <i v-for="(height, index) in [12, 28, 47, 62, 40, 27, 14]" :key="index" :style="{ height: height + 'px' }"></i>
    </div>
  </div>
</template>
