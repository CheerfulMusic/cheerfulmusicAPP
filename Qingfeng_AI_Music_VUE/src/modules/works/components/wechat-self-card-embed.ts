import { createApp } from 'vue'
import WechatSelfCard from './WechatSelfCard.vue'

export function mountWechatSelfCard(host: HTMLElement, path: string) {
  createApp(WechatSelfCard, { path, embedded: true }).mount(host)
}
