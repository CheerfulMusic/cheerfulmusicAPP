export const storyQuestions = ['recipient', 'occasion', 'story', 'message', 'language'] as const
export const storyFlowVersion = 2
export function questionPart(value: unknown): number | undefined {
  const index = storyQuestions.indexOf(value as typeof storyQuestions[number])
  return index < 0 ? undefined : index
}
// 兼容旧三页草稿：送给谁 / 讲述故事 / 选择旋律。
export function restoredStoryPart(part: unknown, version?: number): number {
  const index = typeof part === 'number' && Number.isInteger(part) ? part : 0
  return version === storyFlowVersion ? Math.max(0, Math.min(4, index)) : [0, 2, 4][Math.max(0, Math.min(2, index))]!
}
export function reachableStoryPart(part: number, draft: {recipient:string;occasion:string}): number {
  if (part > 0 && !draft.recipient.trim()) return 0
  if (part > 1 && !draft.occasion.trim()) return 1
  return part
}
