import { getJson } from './api-client'
export interface SystemInfo {
  name: string
  version: string
  stage: 'SCAFFOLD' | 'DRAFT_READY' | 'LOCAL_WORKFLOW_READY'
  capabilities: { lyricsGeneration: boolean; musicGeneration: boolean; giftSharing: boolean; workPersistence: boolean; quickCreation?:boolean; photoLyrics?:boolean }
}
export const getSystemInfo = () => getJson<SystemInfo>('public/system/info')
export const usesLocalPhotoCapability = import.meta.env.VITE_LOCAL_PHOTO_CAPABILITY === 'true'
export async function getPhotoLyricsAvailable() {
  const info = usesLocalPhotoCapability
    ? await getJson<SystemInfo>('public/system/photo-preview')
    : await getSystemInfo()
  return info.capabilities.photoLyrics === true
}
