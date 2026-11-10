const pendingKeys = new Map<string, string>()
const continuationName = (workId:string,version:number,language:string,structureKey:string) =>
  `qingfeng.lyrics.continue:${workId}:${version}:${language}${structureKey?':'+structureKey:''}`

export function existingContinuationRequestKey(workId:string,version:number,language:string,structureKey=''): string|undefined {
  const name=continuationName(workId,version,language,structureKey)
  try {
    const saved=sessionStorage.getItem(name)
    if(saved&&/^[0-9a-f-]{36}$/i.test(saved))return saved
  } catch { /* Fall back to the current tab's in-memory key. */ }
  return pendingKeys.get(name)
}

export function retireContinuationRequestKey(workId:string,version:number,language:string,structureKey=''){
  const name=continuationName(workId,version,language,structureKey)
  pendingKeys.delete(name)
  try{sessionStorage.removeItem(name)}catch{/* 内存仍可继续保存新请求键。 */}
}

/** 同一页签、作品版本和歌词语言复用请求键。刷新或返回重进不会重复购买首版歌词。 */
export function continuationRequestKey(workId: string, version: number, language: string, structureKey = ''): string {
  const name = continuationName(workId,version,language,structureKey)
  const key = existingContinuationRequestKey(workId,version,language,structureKey) ?? crypto.randomUUID()
  pendingKeys.set(name, key)
  try { sessionStorage.setItem(name, key) } catch { /* 保留内存中的请求键。 */ }
  return key
}
