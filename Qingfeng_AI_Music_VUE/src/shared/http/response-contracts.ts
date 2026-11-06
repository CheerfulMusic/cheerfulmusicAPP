type Row=Record<string,unknown>
const obj=(v:unknown):v is Row=>!!v&&typeof v==='object'&&!Array.isArray(v)
const str=(v:unknown)=>typeof v==='string'
const text=(v:unknown)=>str(v)&&!!(v as string).trim()
const googleAuthorizationUrl=(v:unknown)=>{
 if(!text(v))return false
 try{const url=new URL(v as string);return url.origin==='https://accounts.google.com'&&url.pathname==='/o/oauth2/v2/auth'&&!url.username&&!url.password&&!url.hash}catch{return false}
}
const logtoAuthorizationUrl=(v:unknown)=>{
 if(!text(v))return false
 try{const url=new URL(v as string);return ['http://localhost:3001','https://auth.cheerfulmusic.com'].includes(url.origin)&&url.pathname==='/oidc/auth'&&!url.username&&!url.password&&!url.hash}catch{return false}
}
const num=(v:unknown)=>typeof v==='number'&&Number.isFinite(v)
const list=(v:unknown,check:(v:unknown)=>boolean)=>Array.isArray(v)&&v.every(check)
const work=(v:unknown)=>obj(v)&&text(v.id)&&num(v.version)&&['recipient','occasion','story','message','style','title','vocalGender'].every(k=>str(v[k]))
const revision=(v:unknown)=>obj(v)&&text(v.id)&&text(v.workId)&&num(v.workVersion)&&str(v.title)&&str(v.lyrics)&&typeof v.confirmed==='boolean'
const asset=(v:unknown)=>obj(v)&&text(v.id)&&text(v.workId)&&['IMPORTED','GENERATED'].includes(String(v.origin))&&num(v.byteSize)&&text(v.contentType)
const task=(v:unknown)=>obj(v)&&text(v.id)&&text(v.revisionId)&&['QUEUED','SUBMITTING','RUNNING','SAVING','SUCCEEDED','FAILED','CANCELLED','SUBMISSION_UNKNOWN'].includes(String(v.status))&&str(v.title)
const coverAsset=(v:unknown)=>obj(v)&&text(v.id)&&['UPLOADED','AI'].includes(String(v.origin))
const coverEdit=(v:unknown)=>obj(v)&&text(v.id)&&text(v.sourceId)&&text(v.status)
const cover=(v:unknown)=>obj(v)&&num(v.version)&&(v.currentCoverId===null||text(v.currentCoverId))&&list(v.assets,coverAsset)&&list(v.edits,coverEdit)&&typeof v.aiAvailable==='boolean'
const choices=(v:unknown)=>obj(v)&&Object.values(v).every(values=>list(values,text))
const preferences=(v:unknown)=>obj(v)&&v.version===1&&(v.recipeId===null||text(v.recipeId))&&choices(v.choices)&&['AUTO','MALE','FEMALE'].includes(String(v.vocalGender))&&str(v.freeText)&&str(v.negativeText)
const direction=(v:unknown)=>obj(v)&&preferences(v.preferences)&&num(v.catalogVersion)&&str(v.style)&&str(v.negativeTags)&&str(v.summaryZh)&&str(v.summaryEn)
export const emptyResponseAllowed=(path:string,method:string)=>method==='DELETE'&&/^app\/works\/[^/]+\/shares$/.test(path)||method==='POST'&&/^app\/works\/[^/]+\/generations\/[^/]+\/retry-archive$/.test(path)||method==='GET'&&/^app\/works\/[^/]+\/generations\/[^/]+\/music-settings$/.test(path)
/** 校验应用实际依赖的字段。HTTP 200只代表传输成功，不代表业务结果完整。 */
export function validResponse(path:string,method:string,value:unknown):boolean{
 path=path.split('?')[0]!
 if(value==null)return emptyResponseAllowed(path,method)
 if(path==='public/csrf')return obj(value)&&text(value.token)
 if(path==='public/invite/status')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&typeof value.admitted==='boolean'&&str(value.csrfToken)&&str(value.expiresAt)
 if(path==='public/invite/enter')return method==='POST'&&obj(value)&&value.admitted===true
 if(path==='app/inspiration/rewrite')return obj(value)&&text(value.text)&&(value.text as string).length<=4000
 if(path==='public/session'||path==='public/session/recover')return obj(value)&&text(value.creatorId)
 if(path==='public/session/logout')return method==='POST'&&obj(value)&&value.loggedOut===true
 if(path==='public/auth/google/status')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&typeof value.authenticated==='boolean'&&(!value.authenticated||value.enabled)&&['email','name'].every(k=>!(k in value)||str(value[k]))
 if(path==='public/auth/google/start')return method==='POST'&&obj(value)&&googleAuthorizationUrl(value.authorizationUrl)
 if(path==='public/auth/native/status')return method==='GET'&&obj(value)&&['emailPasswordEnabled','passwordResetEnabled','emailCodeEnabled','phoneEnabled','phoneInternationalEnabled','authenticated'].every(k=>typeof value[k]==='boolean')&&['email','phoneMasked','name'].every(k=>!(k in value)||str(value[k]))
 if(path==='public/auth/native/code')return method==='POST'&&obj(value)&&value.sent===true&&num(value.resendAfter)&&Number(value.resendAfter)>=0&&Number(value.resendAfter)<=3600
 if(['public/auth/native/password/sign-in','public/auth/native/register','public/auth/native/email/sign-in','public/auth/native/phone/sign-in'].includes(path))return method==='POST'&&obj(value)&&value.authenticated===true
 if(path==='public/auth/native/password/reset')return method==='POST'&&obj(value)&&value.reset===true
 if(path==='public/auth/logto/status')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&typeof value.authenticated==='boolean'&&['emailEnabled','phoneEnabled','appleEnabled'].every(k=>!(k in value)||typeof value[k]==='boolean')&&['email','name','phoneMasked'].every(k=>!(k in value)||str(value[k]))
 if(path==='public/auth/logto/start')return method==='POST'&&obj(value)&&logtoAuthorizationUrl(value.authorizationUrl)
 if(path==='public/system/info')return obj(value)&&text(value.name)&&text(value.version)&&obj(value.capabilities)&&['lyricsGeneration','musicGeneration','giftSharing','workPersistence'].every(k=>typeof (value.capabilities as Row)[k]==='boolean')
 if(path==='app/identity/recovery')return obj(value)&&(method==='GET'?typeof value.configured==='boolean':text(value.code))
 if(path==='app/credits')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&num(value.balance)&&num(value.musicCost)
 if(path==='app/payments/catalog'||path==='app/paypal/catalog')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&list(value.packages,p=>obj(p)&&text(p.id)&&str(p.title)&&num(p.amount)&&Number(p.amount)>0&&text(p.currency)&&num(p.credits)&&Number(p.credits)>0)
 if(path==='app/paypal/sdk-config')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&str(value.clientId)&&['sandbox','production'].includes(String(value.environment))&&(!value.enabled||text(value.clientId))
 if(path==='app/payments/orders'||path==='app/paypal/orders')return method==='POST'?obj(value)&&text(value.orderId)&&text(value.checkoutUrl)&&(path!=='app/paypal/orders'||value.paypalOrderId===undefined||text(value.paypalOrderId)):method==='GET'&&obj(value)&&list(value.orders,o=>obj(o)&&text(o.orderId)&&['CREATING','PENDING','PAID'].includes(String(o.status))&&num(o.credits)&&text(o.createdAt))
 if(/^app\/(payments|paypal)\/orders\/[^/]+$/.test(path))return method==='GET'&&obj(value)&&text(value.orderId)&&['CREATING','PENDING','PAID'].includes(String(value.status))&&num(value.credits)
 if(/^app\/paypal\/orders\/[^/]+\/resume$/.test(path))return method==='POST'&&obj(value)&&text(value.orderId)&&(['APPROVED','PAID'].includes(String(value.status))||value.status==='CHECKOUT'&&text(value.checkoutUrl))
 if(/^app\/paypal\/orders\/[^/]+\/capture$/.test(path))return method==='POST'&&obj(value)&&text(value.orderId)&&['CREATING','PENDING','PAID'].includes(String(value.status))&&num(value.credits)
 if(path==='app/usage')return obj(value)&&['music','lyrics'].every(k=>obj(value[k])&&num((value[k] as Row).dailyRemaining)&&num((value[k] as Row).active))
 if(path==='app/music-settings/catalog')return obj(value)&&num(value.version)&&list(value.groups,g=>obj(g)&&text(g.id)&&str(g.zh)&&str(g.en)&&num(g.max)&&list(g.options,o=>obj(o)&&text(o.id)&&str(o.zh)&&str(o.en)&&str(o.prompt)))&&list(value.recipes,r=>obj(r)&&text(r.id)&&str(r.zh)&&str(r.en)&&choices(r.choices))
 if(path==='app/music-settings/preview'||/\/generations\/[^/]+\/music-settings$/.test(path))return direction(value)
 if(/\/music-settings$/.test(path))return obj(value)&&work(value.work)&&(value.direction===null||direction(value.direction))
 if(path==='app/works')return method==='GET'?list(value,work):work(value)
 if(path==='app/works/library')return list(value,v=>obj(v)&&work(v.work)&&text(v.status))
 if(/^app\/works\/[^/]+$/.test(path))return work(value)
 if(/\/section-context$/.test(path))return obj(value)&&typeof value.available==='boolean'&&Array.isArray(value.lines)&&(!value.available||str(value.lyrics)&&num(value.duration))
 if(/\/section-edits$/.test(path))return method==='GET'?list(value,v=>obj(v)&&text(v.taskId)&&text(v.status)&&str(v.replacementLyrics)):task(value)
 if(/\/lyrics\/requests\//.test(path))return obj(value)&&['NOT_FOUND','PENDING','FAILED','SUCCEEDED','UNKNOWN'].includes(String(value.status))&&(value.status!=='SUCCEEDED'||text(value.revisionId))
 if(/\/audio\/[^/]+\/lyrics(?:\/sync)?$/.test(path))return obj(value)&&text(value.status)&&str(value.text)&&list(value.lines,v=>obj(v)&&str(v.text)&&num(v.start)&&num(v.end))&&typeof value.canSync==='boolean'
 if(/\/lyrics(?:\/[^/]+(?:\/confirm)?)?$/.test(path)||/\/lyrics\/generate$/.test(path))return method==='GET'&&path.endsWith('/lyrics')?list(value,revision):revision(value)
 if(/\/audio\/selection$/.test(path))return obj(value)&&num(value.version)&&(value.assetId===null?value.asset===null:asset(value.asset)&&obj(value.asset)&&value.asset.id===value.assetId)
 if(/\/audio$/.test(path))return method==='GET'?list(value,asset):asset(value)
 if(/\/generations(?:\/[^/]+\/(?:cancel|close-checking))?$/.test(path))return method==='GET'?list(value,task):task(value)
 if(/\/covers\/edits$/.test(path))return coverEdit(value)
 if(/\/covers\/selection$/.test(path))return cover(value)
 if(/\/covers$/.test(path))return method==='GET'?cover(value):coverAsset(value)
 if(/\/shares$/.test(path))return obj(value)&&text(value.id)&&text(value.token)&&text(value.expiresAt)
 if(/^public\/gifts\//.test(path))return obj(value)&&str(value.title)&&str(value.lyrics)
 return Array.isArray(value)||obj(value)&&Object.keys(value).length>0
}
