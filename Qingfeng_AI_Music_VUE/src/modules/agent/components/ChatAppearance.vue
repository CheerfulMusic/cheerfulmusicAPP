<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { Appearance } from '../composables/useChatAppearance'
import { locale } from '../../../shared/i18n'
const props = defineProps<{ modelValue: Appearance }>()
const emit = defineEmits<{ 'update:modelValue': [value: Appearance]; reset: [] }>()
const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
const panel = ref<HTMLDetailsElement>()
function set<K extends keyof Appearance>(key: K, value: Appearance[K]) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
function close() { if (panel.value) { panel.value.open = false; panel.value.querySelector('summary')?.focus() } }
function outside(event: PointerEvent) { if (event.target instanceof Node && !panel.value?.contains(event.target) && panel.value) panel.value.open = false }
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <details ref="panel" class="chat-appearance" @keydown.esc.stop.prevent="close">
    <summary aria-label="字体与消息外观"><span aria-hidden="true">Aa</span> {{ tr('外观', 'Appearance') }}</summary>
    <div class="appearance-panel" :aria-label="tr('对话外观设置', 'Chat appearance')">
      <header><div><small>MAKE IT YOURS</small><h3>{{ tr('你的对话，你的风格', 'Make this space yours') }}</h3></div><button type="button" :aria-label="tr('关闭外观设置', 'Close appearance')" @click="close">×</button></header>
      <fieldset><legend>{{ tr('字体', 'Typeface') }}</legend><div class="appearance-fonts">
        <button v-for="item in ([['modern','现代黑体','Modern'],['literary','人文宋体','Literary'],['rounded','柔和圆体','Rounded']] as const)" :key="item[0]" type="button" :class="item[0]" :aria-pressed="modelValue.font === item[0]" @click="set('font', item[0])"><b>写</b>{{ tr(item[1],item[2]) }}</button>
      </div></fieldset>
      <label class="appearance-range">{{ tr('字号', 'Text size') }}<output>{{ modelValue.size }} px</output><input aria-label="字号" type="range" min="13" max="20" step="1" :value="modelValue.size" @input="set('size', Number(($event.target as HTMLInputElement).value))"/></label>
      <fieldset><legend>{{ tr('已发送消息的边框', 'Sent message border') }}</legend><div class="appearance-borders"><button v-for="item in ([['solid','细线','Fine'],['double','双线','Double'],['dashed','虚线','Dashed'],['none','无框','None']] as const)" :key="item[0]" type="button" :aria-pressed="modelValue.border === item[0]" @click="set('border',item[0])"><i :style="{borderStyle:item[0],borderWidth:item[0]==='double'?'3px':'1px'}"/>{{ tr(item[1],item[2]) }}</button></div></fieldset>
      <div class="appearance-color"><span>{{ tr('边框颜色', 'Border color') }}</span><button v-for="color in ['#d8b6a4','#9baeb4','#afa0bb','#a2ad97']" :key="color" type="button" :style="{background:color}" :aria-label="tr('边框颜色 ', 'Border color ')+color" :aria-pressed="modelValue.color===color" @click="set('color',color)"/><label class="custom-color" :title="tr('自定义颜色','Custom color')"><input type="color" aria-label="自定义边框颜色" :value="modelValue.color" @input="set('color',($event.target as HTMLInputElement).value)"/><span>＋</span></label></div>
      <label class="appearance-range">{{ tr('圆角', 'Corner radius') }}<output>{{ modelValue.radius }} px</output><input aria-label="圆角" type="range" min="4" max="28" :value="modelValue.radius" @input="set('radius',Number(($event.target as HTMLInputElement).value))"/></label>
      <div class="appearance-sample"><small>{{ tr('实时预览','LIVE PREVIEW') }}</small><div class="sent-sample">{{ tr('把今天的心情，写进一首歌。','Turn this feeling into a song.') }}</div></div>
      <footer><span>{{ tr('自动保存于此浏览器','Saved in this browser') }}</span><button type="button" @click="emit('reset')">{{ tr('恢复默认','Reset') }}</button></footer>
    </div>
  </details>
</template>
