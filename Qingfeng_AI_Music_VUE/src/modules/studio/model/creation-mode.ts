import type {DraftInput} from '../api/works'
import {english} from '../../../shared/i18n/en'
import {inspirationGroups} from './inspiration-ideas'
import {inspirationPresets,inspirationSelection,legacyInspirationDescription,type InspirationPreset} from './inspiration-presets'
export type CreationMode='quick'|'custom'
export const creationMode=(value:unknown):CreationMode=>value==='quick'?'quick':'custom'
export const inspirationLimit = 4000
const inspirationDescriptions = new Set<string>(
  inspirationGroups.flatMap(group => group.flatMap(([, , storyZh, storyEn]) => [storyZh, storyEn])),
)

/** Replace only the latest intact inspiration paragraph, including after a draft is restored. */
export function chooseInspiration(story:string, description:string):string|null {
  const paragraphs = story.split('\n\n')
  for (let index = paragraphs.length - 1; index >= 0; index--) {
    const paragraph = paragraphs[index]!
    const previous = paragraph.trim()
    if (!inspirationDescriptions.has(previous)) continue
    paragraphs[index] = paragraph.replace(previous, () => description)
    const combined = paragraphs.join('\n\n')
    return combined.length <= inspirationLimit ? combined : null
  }
  const combined = story.trim() ? `${story}\n\n${description}` : description
  return combined.length <= inspirationLimit ? combined : null
}

const presetDescriptions = new Set(
  [...inspirationPresets.map(preset => preset.description), legacyInspirationDescription]
    .flatMap(description => [description, english[description]]),
)

/** Only replace an intact preset paragraph. Any user-written or edited text stays in the brief. */
export function chooseCreationStyle(draft:DraftInput,preset:InspirationPreset,translate:(text:string)=>string):DraftInput|null {
  const filled = inspirationSelection(preset, translate)
  const [first, ...rest] = draft.story.split('\n\n')
  const notes = first && presetDescriptions.has(first.trim()) ? rest.join('\n\n') : draft.story
  const story = notes.trim() ? `${filled.story}\n\n${notes}` : filled.story
  if (story.length > inspirationLimit) return null
  return {...draft,...filled,story}
}
