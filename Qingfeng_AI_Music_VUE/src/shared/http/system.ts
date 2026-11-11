import { getJson } from './api-client'
export interface SystemInfo {
  name: string
  version: string
  stage: 'SCAFFOLD' | 'DRAFT_READY' | 'LOCAL_WORKFLOW_READY'
  capabilities: { lyricsGeneration: boolean; musicGeneration: boolean; giftSharing: boolean; workPersistence: boolean; quickCreation?:boolean; photoLyrics?:boolean }
}
export const getSystemInfo = () => getJson<SystemInfo>('public/system/info')
