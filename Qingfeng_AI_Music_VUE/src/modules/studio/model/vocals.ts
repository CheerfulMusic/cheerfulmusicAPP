export type VocalGender = 'AUTO' | 'MALE' | 'FEMALE'
export const vocalChoices = [{value:'AUTO',label:'自动选择'},{value:'MALE',label:'男声'},{value:'FEMALE',label:'女声'}] as const
/** 旧作品与旧的本机草稿没有这个字段，统一保留为自动选择。 */
export function normalizeVocalGender(value:unknown):VocalGender { return value==='MALE'||value==='FEMALE'?value:'AUTO' }
export function vocalGenderLabel(value:unknown):string { return vocalChoices.find(choice=>choice.value===normalizeVocalGender(value))!.label }
