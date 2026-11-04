const pendingKeys = new Map<string, string>()
export function retireContinuationRequestKey(workId:string,version:number,language:string){
  const name=`qingfeng.lyrics.continue:${workId}:${version}:${language}`
  pendingKeys.delete(name)
  try{sessionStorage.removeItem(name)}catch{/* 内存仍可继续保存新请求键。 */}
}

/** 同一页签、作品版本和歌词语言复用请求键。刷新或返回重进不会重复购买首版歌词。 */
export function continuationRequestKey(workId: string, version: number, language: string): string {
  const name = `qingfeng.lyrics.continue:${workId}:${version}:${language}`
  let key = pendingKeys.get(name)
  try {
    const saved = sessionStorage.getItem(name)
    if (saved && /^[0-9a-f-]{36}$/i.test(saved)) key = saved
  } catch { /* 浏览器限制存储时仍在当前页面内去重。 */ }
  if (!key) key = crypto.randomUUID()
  pendingKeys.set(name, key)
  try { sessionStorage.setItem(name, key) } catch { /* 保留内存中的请求键。 */ }
  return key
}
