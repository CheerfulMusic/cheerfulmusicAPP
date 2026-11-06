import type { VocalGender } from './vocals'

export interface InspirationPreset { label: string; description: string; style: string; vocalGender: VocalGender }
/** English music prompts belong to the backend; users edit the readable brief here. */
export const inspirationPresets: readonly InspirationPreset[] = [
  { label: '流行抒情', description: '我想写一首慢速的抒情歌曲，以钢琴和弦乐打底，节奏温柔舒缓，清晰温柔的女声演唱。', style: '流行抒情', vocalGender: 'FEMALE' },
  { label: '流行摇滚', description: '我想写一首充满攻击性的摇滚歌曲，电吉他riff和强力和弦驱动，配上有力的贝斯和爆炸性的现场鼓声，粗旷的男声演唱。', style: '流行摇滚', vocalGender: 'MALE' },
  { label: '蓝调R&B', description: '我想写一首节奏轻松的R&B歌曲，使用七和弦、九和弦、转调及转位和弦，色彩丰富，男声演唱，展现极高的人声技巧与情感爆发力。', style: '蓝调R&B', vocalGender: 'MALE' },
  { label: '华语民谣', description: '我想写一首以木吉他为主的民谣歌曲，旋律简单流畅，配器线条清晰简约，辅以口琴、手风琴、箱鼓或基础打击乐即可。', style: '华语民谣', vocalGender: 'AUTO' },
  { label: '中国风', description: '我想写一首五声音阶的中国风歌曲，以婉约诗词、古典意象和细腻叙事为核心，结合清新流行旋律与淡雅编曲，营造出含蓄、忧伤、浪漫的文人气质。', style: '中国风', vocalGender: 'MALE' },
  { label: '我还没有灵感', description: '我还没有明确的灵感，想创作一首自然、有画面感的歌曲，请帮我寻找一个主题。', style: '轻快流行', vocalGender: 'AUTO' },
]
export type InspirationSelection = Pick<InspirationPreset, 'style' | 'vocalGender'> & { story: string }
export function inspirationSelection(preset: InspirationPreset, translate: (text: string) => string): InspirationSelection {
  return { story: translate(preset.description), style: preset.style, vocalGender: preset.vocalGender }
}
