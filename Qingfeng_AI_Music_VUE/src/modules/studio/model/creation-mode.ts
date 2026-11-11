import type {DraftInput} from '../api/works'
import {english} from '../../../shared/i18n/en'
import {inspirationPresets,inspirationSelection,legacyInspirationDescription,type InspirationPreset} from './inspiration-presets'
export type CreationMode='quick'|'custom'
export const creationMode=(value:unknown,existing=false):CreationMode=>value==='custom'||value!=='quick'&&existing?'custom':'quick'
export const inspirationLimit = 4000
export function appendInspiration(story:string, description:string):string|null {
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
