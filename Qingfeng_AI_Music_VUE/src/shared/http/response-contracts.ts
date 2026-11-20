import { isAgentContinuation } from '../../modules/agent/model/conversation'
import { isStripeMethod, validCreditPack, validStripeMethods } from '../../modules/identity/model/stripe-payment-methods'
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
const paymentOrder=(v:unknown)=>obj(v)&&text(v.orderId)&&['CREATING','CREATION_UNKNOWN','PENDING','PAID','CANCELLED','FAILED','UNKNOWN'].includes(String(v.status))&&num(v.credits)
 &&(v.amount===undefined&&v.currency===undefined||num(v.amount)&&Number(v.amount)>0&&text(v.currency))
 &&(v.packageId===undefined||text(v.packageId))&&(v.paymentMethod===undefined||isStripeMethod(v.paymentMethod))
const list=(v:unknown,check:(v:unknown)=>boolean)=>Array.isArray(v)&&v.every(check)
const work=(v:unknown)=>obj(v)&&text(v.id)&&num(v.version)&&['recipient','occasion','story','message','style','title','vocalGender'].every(k=>str(v[k]))
const revision=(v:unknown)=>obj(v)&&text(v.id)&&text(v.workId)&&num(v.workVersion)&&str(v.title)&&str(v.lyrics)&&typeof v.confirmed==='boolean'
const asset=(v:unknown)=>obj(v)&&text(v.id)&&text(v.workId)&&['IMPORTED','GENERATED'].includes(String(v.origin))&&num(v.byteSize)&&text(v.contentType)
 &&(v.note===undefined||str(v.note)&&v.note.length<=500)&&(v.noteVersion===undefined||Number.isInteger(v.noteVersion)&&Number(v.noteVersion)>=0)
 &&(v.versionNumber===undefined||v.versionNumber===null||Number.isInteger(v.versionNumber)&&Number(v.versionNumber)>0)
const task=(v:unknown)=>obj(v)&&text(v.id)&&text(v.revisionId)&&['QUEUED','SUBMITTING','RUNNING','SAVING','SUCCEEDED','FAILED','CANCELLED','SUBMISSION_UNKNOWN'].includes(String(v.status))&&str(v.title)
const coverAsset=(v:unknown)=>obj(v)&&text(v.id)&&['UPLOADED','AI','AUTO'].includes(String(v.origin))
const coverEdit=(v:unknown)=>obj(v)&&text(v.id)&&text(v.sourceId)&&text(v.status)
const cover=(v:unknown)=>obj(v)&&num(v.version)&&(v.currentCoverId===null||text(v.currentCoverId))&&list(v.assets,coverAsset)&&list(v.edits,coverEdit)&&typeof v.aiAvailable==='boolean'
const choices=(v:unknown)=>obj(v)&&Object.values(v).every(values=>list(values,text))
const preferences=(v:unknown)=>obj(v)&&v.version===1&&(v.recipeId===null||text(v.recipeId))&&choices(v.choices)&&['AUTO','MALE','FEMALE'].includes(String(v.vocalGender))&&str(v.freeText)&&str(v.negativeText)
const direction=(v:unknown)=>obj(v)&&preferences(v.preferences)&&num(v.catalogVersion)&&str(v.style)&&str(v.negativeTags)&&str(v.summaryZh)&&str(v.summaryEn)
const hotStat=(v:unknown)=>obj(v)&&text(v.trackId)&&['plays','likes','favorites','comments'].every(k=>num(v[k])&&Number(v[k])>=0)
const nullableText=(v:unknown)=>v===null||str(v)
const artistSong=(v:unknown):v is Row=>obj(v)&&text(v.id)&&Number.isSafeInteger(v.version)&&Number(v.version)>0&&text(v.title)
const publicArtistSong=(v:unknown)=>artistSong(v)&&typeof v.hasCover==='boolean'&&(v.coverVersion===undefined||nullableText(v.coverVersion))&&(v.coverId===undefined||nullableText(v.coverId))
const artistRelease=(v:unknown)=>artistSong(v)&&text(v.workId)&&text(v.assetId)&&nullableText(v.coverId)
  &&(v.onDiscover===undefined||typeof v.onDiscover==='boolean')
const nonnegativeCount=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>=0
const rhymeEnding=(v:unknown)=>obj(v)&&text(v.word)&&text(v.sound)
const rhymeCandidate=(v:unknown)=>obj(v)&&text(v.text)&&String(v.text).length<=160
  &&!/[\r\n\u0085\u2028\u2029\v\f]/.test(String(v.text))
  &&rhymeEnding(v.ending)&&['EXACT','NEAR','CROSS_LANGUAGE'].includes(String(v.match))
  &&(v.syllables==null||nonnegativeCount(v.syllables))
const lyricRhyme=(v:unknown)=>{
  if(!obj(v)||!Array.isArray(v.candidates)||v.candidates.length>3||!v.candidates.every(rhymeCandidate))return false
  if(v.original!=null&&!rhymeEnding(v.original)||v.reference!=null&&!rhymeEnding(v.reference))return false
  if(v.status==='CHOOSE_REFERENCE')return v.referenceIndex==null&&v.reference==null&&v.candidates.length===0
  if(!nonnegativeCount(v.referenceIndex)||Number(v.referenceIndex)>=40)return false
  if(v.status==='UNKNOWN_PRONUNCIATION')return v.candidates.length===0
  if(!rhymeEnding(v.original)||!rhymeEnding(v.reference))return false
  if(v.status==='SUGGESTIONS')return v.candidates.length>0
  return ['ALREADY_RHYMES','NO_MATCH'].includes(String(v.status))&&v.candidates.length===0
}
const discoverSong=(v:unknown)=>obj(v)&&text(v.artistId)&&text(v.stageName)&&publicArtistSong(v.song)&&text(v.publishedAt)
const discoverShelves=(v:unknown)=>obj(v)&&typeof v.listeningDataAvailable==='boolean'
 &&list(v.today,discoverSong)&&list(v.vocal,discoverSong)&&obj(v.genres)
 &&Object.values(v.genres).every(rows=>list(rows,discoverSong))
  &&(v.genre===undefined||str(v.genre))&&(v.plays===undefined||nonnegativeCount(v.plays))
const discoverArtist=(v:unknown)=>obj(v)&&text(v.artistId)&&text(v.stageName)&&nullableText(v.avatarKey)
  &&nonnegativeCount(v.songs)&&nonnegativeCount(v.plays)
const artistFields=(v:unknown):v is Row=>obj(v)&&str(v.stageName)&&str(v.tagline)&&nullableText(v.avatarKey)
  &&nullableText(v.coverKey)&&nullableText(v.featuredReleaseId)
const myArtist=(v:unknown)=>artistFields(v)&&nullableText(v.publicId)&&typeof v.published==='boolean'&&list(v.songs,artistRelease)
const agentMessage=(v:unknown)=>obj(v)&&text(v.id)&&text(v.conversationId)&&Number.isInteger(v.seq)&&Number(v.seq)>0&&['user','assistant'].includes(String(v.role))&&['TEXT','TOOL_CARD','PLACEHOLDER'].includes(String(v.kind))&&['PENDING','RUNNING','DONE','FAILED'].includes(String(v.state))&&nullableText(v.contentJson)&&nullableText(v.taskId)&&str(v.createdAt)
const agentCard=(v:unknown)=>obj(v)&&nullableText(v.songId)&&['title','style','lyrics','audioUrl','status'].every(k=>str(v[k]))&&nullableText(v.coverUrl)&&num(v.revision)&&['revisionId','assetId','taskId'].every(k=>v[k]===undefined||nullableText(v[k]))
const agentSnapshot=(v:unknown)=>obj(v)&&(v.continuation===undefined||v.continuation===null||isAgentContinuation(v.continuation))&&list(v.messages,agentMessage)&&(v.songCard===null||agentCard(v.songCard))&&nullableText(v.taskId)&&nullableText(v.action)&&(v.shareUrl===null||typeof v.shareUrl==='string'&&/^\/gift\/[A-Za-z0-9_-]+$/.test(v.shareUrl))&&typeof v.liked==='boolean'
const analyticsCount=(v:unknown)=>num(v)&&Number(v)>=0
const analyticsTotals=(v:unknown)=>obj(v)&&['pv','pageviews','uv','sessions','avgDurationMs','totalDurationMs'].every(k=>analyticsCount(v[k]))
const analyticsDaily=(v:unknown)=>obj(v)&&text(v.d)&&['pv','pageviews','uv','avgDurationMs'].every(k=>analyticsCount(v[k]))
const analyticsPageRow=(v:unknown)=>obj(v)&&text(v.pagePath)&&analyticsCount(v.pv)&&analyticsCount(v.avgDurationMs)&&analyticsCount(v.samples)
const analyticsUserRow=(v:unknown)=>obj(v)&&str(v.creatorId)&&text(v.accountType)&&['name','email','phoneMasked'].every(k=>v[k]===null||str(v[k]))&&['pv','pageviews','avgDurationMs','samples'].every(k=>analyticsCount(v[k]))&&str(v.firstSeen)&&str(v.lastSeen)&&(v.noActivity===undefined||typeof v.noActivity==='boolean')
const analyticsCountryRow=(v:unknown)=>obj(v)&&text(v.country)&&analyticsCount(v.visitors)&&analyticsCount(v.hits)
/**
 * 统计看板：allowed 明确拒绝时不带数据；QUERY_FAILED 只带 error，绝不能被当成 0 展示。
 * 字段缺失或类型不对一律判为非法响应，让前端进入“加载失败”而不是渲染 0。
 */
const analyticsSummaryBody=(v:unknown)=>{
 if(!obj(v)||typeof v.allowed!=='boolean')return false
 if(v.allowed!==true)return true
 if(v.error!==undefined)return text(v.error)&&v.totals===undefined&&v.daily===undefined
 return analyticsTotals(v.totals)&&list(v.daily,analyticsDaily)&&list(v.pages,analyticsPageRow)&&list(v.users,analyticsUserRow)&&(v.countries===undefined||list(v.countries,analyticsCountryRow))
}
const analyticsAccessBody=(v:unknown)=>obj(v)&&typeof v.allowed==='boolean'&&(v.allowed!==true||text(v.viewer))
const quickCreation=(v:unknown)=>obj(v)&&text(v.id)&&text(v.workId)&&['QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC','MUSIC_QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN','SUCCEEDED','FAILED','CANCELLED'].includes(String(v.status))&&['revisionId','taskId','errorCode'].every(k=>v[k]===null||text(v[k]))&&typeof v.canRetry==='boolean'&&text(v.createdAt)
export const emptyResponseAllowed=(path:string,method:string)=>method==='DELETE'&&/^app\/works\/[^/]+\/shares$/.test(path)||method==='POST'&&(/^app\/works\/[^/]+\/generations\/[^/]+\/retry-archive$/.test(path)||/^public\/discover\/songs\/[^/]+\/\d+\/play$/.test(path))||method==='GET'&&(/^app\/works\/[^/]+\/generations\/[^/]+\/music-settings$/.test(path)||/^app\/works\/[^/]+\/quick-creations\/latest$/.test(path))
/** 校验应用实际依赖的字段。HTTP 200只代表传输成功，不代表业务结果完整。 */
export function validResponse(path:string,method:string,value:unknown):boolean{
 const [endpoint,query]=path.split('?')
 path=endpoint!
 if(value==null)return method==='GET'&&/^app\/works\/[^/]+\/quick-creations\/latest$/.test(path)||emptyResponseAllowed(path,method)
 if(/^app\/works\/[^/]+\/quick-creations(?:\/[^/]+(?:\/retry)?)?$/.test(path))return quickCreation(value)
 if(path==='public/csrf')return obj(value)&&text(value.token)
 if(path==='app/avatar')return ['GET','POST'].includes(method)&&obj(value)&&((value.imageUrl===null&&value.version===null)||(value.imageUrl==='/api/app/avatar/image'&&text(value.version)))
 if(path==='app/avatar/generation/latest')return method==='GET'&&obj(value)&&typeof value.available==='boolean'&&['NONE','SUBMITTING','RUNNING','SUCCEEDED','FAILED','UNKNOWN','SUPERSEDED'].includes(String(value.status))&&(value.status==='NONE'?value.id===null:text(value.id))
 if(path==='public/invite/status')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&typeof value.admitted==='boolean'&&str(value.csrfToken)&&str(value.expiresAt)
 if(path==='public/invite/enter')return method==='POST'&&obj(value)&&value.admitted===true
 if(path==='app/inspiration/rewrite')return obj(value)&&text(value.text)&&(value.text as string).length<=4000
 if(path==='app/agent/reference')return method==='POST'&&obj(value)&&text(value.style)&&str(value.mood)&&num(value.tempo)&&Number(value.tempo)>=0&&typeof value.tempoVerified==='boolean'&&list(value.inferredInstrumentation,text)&&list(value.negativeTags,text)&&text(value.summary)&&['AI','RULE'].includes(String(value.source))
 if(path==='app/agent/conversations/current')return method==='GET'&&obj(value)&&text(value.id)&&nullableText(value.currentWorkId)
 if(/^app\/agent\/conversations\/[^/]+\/state$/.test(path))return method==='GET'&&agentSnapshot(value)
 if(/^app\/agent\/conversations\/[^/]+\/messages$/.test(path))return method==='GET'?list(value,agentMessage):method==='POST'&&agentSnapshot(value)
 if(/^app\/agent\/conversations\/[^/]+(?:\/reset)?$/.test(path))return ['POST','DELETE'].includes(method)&&obj(value)&&value.cleared===true
 if(path==='app/welcome')return obj(value)&&typeof value.show==='boolean'&&Number.isSafeInteger(value.amount)&&(value.show?Number(value.amount)>0:value.amount===0)
 if(path==='public/session'||path==='public/session/recover')return obj(value)&&text(value.creatorId)&&(value.associatedGuestCreatorId===undefined||text(value.associatedGuestCreatorId))
 if(path==='public/session/logout')return method==='POST'&&obj(value)&&value.loggedOut===true
 if(path==='public/auth/google/status')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&typeof value.authenticated==='boolean'&&(!value.authenticated||value.enabled)&&['email','name'].every(k=>!(k in value)||str(value[k]))
 if(path==='public/auth/google/start')return method==='POST'&&obj(value)&&googleAuthorizationUrl(value.authorizationUrl)
 if(path==='public/auth/native/status')return method==='GET'&&obj(value)&&['emailPasswordEnabled','passwordResetEnabled','emailCodeEnabled','phoneEnabled','phoneInternationalEnabled','authenticated'].every(k=>typeof value[k]==='boolean')&&['email','phoneMasked','name'].every(k=>!(k in value)||str(value[k]))
   &&(value.phoneAllowedRegions===undefined||list(value.phoneAllowedRegions,r=>str(r)&&/^[A-Z]{2}$/.test(r as string)))
 if(path==='public/auth/native/code')return method==='POST'&&obj(value)&&value.sent===true&&num(value.resendAfter)&&Number(value.resendAfter)>=0&&Number(value.resendAfter)<=3600
 if(['public/auth/native/password/sign-in','public/auth/native/register','public/auth/native/email/sign-in','public/auth/native/phone/sign-in'].includes(path))return method==='POST'&&obj(value)&&value.authenticated===true
 if(path==='public/auth/native/password/reset')return method==='POST'&&obj(value)&&value.reset===true
 if(path==='public/auth/logto/status')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&typeof value.authenticated==='boolean'&&['emailEnabled','phoneEnabled','appleEnabled'].every(k=>!(k in value)||typeof value[k]==='boolean')&&['email','name','phoneMasked'].every(k=>!(k in value)||str(value[k]))
 if(path==='public/auth/logto/start')return method==='POST'&&obj(value)&&logtoAuthorizationUrl(value.authorizationUrl)
 if(path==='public/system/info'||path==='public/system/photo-preview')return obj(value)&&text(value.name)&&text(value.version)&&obj(value.capabilities)&&['lyricsGeneration','musicGeneration','giftSharing','workPersistence'].every(k=>typeof (value.capabilities as Row)[k]==='boolean')
 if(path==='public/hot-songs')return method==='GET'&&obj(value)&&list(value.songs,hotStat)
 if(/^public\/artists\/[^/]+$/.test(path))return method==='GET'&&artistFields(value)&&list(value.songs,publicArtistSong)
 if(path==='public/discover')return method==='GET'&&(
   obj(value)&&typeof value.hasMore==='boolean'&&list(value.songs,discoverSong)
     &&(value.artists===undefined||list(value.artists,discoverArtist))
   || new URLSearchParams(query).get('view')==='shelves'&&discoverShelves(value))
 if(path==='app/artist-profile'||path==='app/artist-profile/visibility')return myArtist(value)
 if(/^app\/artist-profile\/releases\/[^/]+(?:\/publication)?$/.test(path)){
  const unified=path.endsWith('/publication')
  if(!unified&&method==='DELETE')return myArtist(value)
  if(!unified&&method==='PUT')return artistRelease(value)
  return (unified?['PUT','POST','DELETE'].includes(method):method==='GET')
    &&obj(value)&&typeof value.profileReady==='boolean'&&typeof value.published==='boolean'
    &&(value.publicId===undefined||nullableText(value.publicId))&&(value.stageName===undefined||str(value.stageName))
    &&(value.song===null||artistRelease(value.song))
    &&(!unified||typeof value.publicationPending==='boolean')
    &&(value.reopensSongs===undefined||typeof value.reopensSongs==='boolean')
 }
 if(/^app\/artist-profile\/releases\/[^/]+\/discover$/.test(path))
  return method==='DELETE'?myArtist(value):method==='PUT'&&artistRelease(value)&&obj(value)&&value.onDiscover===true
 if(/^public\/hot-songs\/[^/]+\/play$/.test(path))return method==='POST'&&hotStat(value)
 if(path==='app/analytics/phone-users')return method==='GET'&&obj(value)&&typeof value.allowed==='boolean'&&
  (!value.allowed||(value.error==='QUERY_FAILED'&&value.users===undefined)||list(value.users,u=>obj(u)&&
   text(u.creatorId)&&text(u.accountType)&&(u.name===null||str(u.name))&&str(u.phoneMasked)&&/^\*{4}[0-9]{4}$/.test(u.phoneMasked)))
 if(path==='app/analytics/country-users')return method==='GET'&&obj(value)&&typeof value.allowed==='boolean'&&
  (!value.allowed||(value.error==='QUERY_FAILED'&&value.users===undefined)||list(value.users,u=>obj(u)&&
   (u.creatorId===null||str(u.creatorId))&&text(u.accountType)&&(u.name===null||str(u.name))&&
   (u.email===null||str(u.email))&&(u.phoneMasked===null||str(u.phoneMasked))&&num(u.hits)&&text(u.lastSeen)))
 if(path==='app/analytics/page-users')return method==='GET'&&obj(value)&&typeof value.allowed==='boolean'&&(value.allowed!==true||(num(value.days)&&text(value.pagePath)&&list(value.users,u=>obj(u)&&text(u.creatorId)&&text(u.accountType)&&(u.name===null||str(u.name))&&(u.email===null||str(u.email))&&(u.phoneMasked===null||str(u.phoneMasked))&&num(u.avgDurationMs)&&num(u.samples)&&Number(u.samples)>=0)))
 if(path==='app/analytics/access')return method==='GET'&&analyticsAccessBody(value)
 if(path==='app/analytics/summary')return method==='GET'&&analyticsSummaryBody(value)
 if(path==='app/analytics/user-pages')return method==='GET'&&obj(value)&&typeof value.allowed==='boolean'&&(value.allowed!==true||(num(value.days)&&text(value.creatorId)&&list(value.pages,u=>obj(u)&&text(u.pagePath)&&num(u.avgDurationMs)&&num(u.samples)&&Number(u.samples)>=0)))
 if(path==='app/hot-songs/reactions')return method==='GET'&&obj(value)&&list(value.liked,text)&&list(value.favorited,text)
 if(/^app\/hot-songs\/[^/]+\/(?:like|favorite)$/.test(path))return ['POST','DELETE'].includes(method)&&obj(value)&&hotStat(value.stat)&&typeof value.liked==='boolean'&&typeof value.favorited==='boolean'
 if(path==='app/identity/recovery')return obj(value)&&(method==='GET'?typeof value.configured==='boolean':text(value.code))
 if(path==='app/credits')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&num(value.balance)&&num(value.musicCost)
 if(path==='app/referrals')return method==='GET'&&obj(value)&&text(value.code)
 if(path==='app/referrals/accept')return method==='POST'&&obj(value)&&value.accepted===true
 if(path==='app/payments/catalog'||path==='app/paypal/catalog'||path==='app/stripe/catalog')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&list(value.packages,validCreditPack)&&(path!=='app/stripe/catalog'||value.paymentMethods===undefined||validStripeMethods(value.paymentMethods))
 if(path==='app/paypal/sdk-config')return method==='GET'&&obj(value)&&typeof value.enabled==='boolean'&&str(value.clientId)&&['sandbox','production'].includes(String(value.environment))&&(!value.enabled||text(value.clientId))
 if(path==='app/payments/orders'||path==='app/paypal/orders'||path==='app/stripe/orders')return method==='POST'?obj(value)&&text(value.orderId)&&text(value.checkoutUrl)&&(path!=='app/paypal/orders'||value.paypalOrderId===undefined||text(value.paypalOrderId)):method==='GET'&&obj(value)&&list(value.orders,o=>paymentOrder(o)&&obj(o)&&text(o.createdAt))
 if(/^app\/(payments|paypal|stripe)\/orders\/[^/]+$/.test(path))return method==='GET'&&paymentOrder(value)
 if(/^app\/stripe\/orders\/[^/]+\/cancel$/.test(path))return method==='POST'&&paymentOrder(value)
 if(/^app\/stripe\/orders\/[^/]+\/resume$/.test(path))return method==='POST'&&obj(value)&&text(value.orderId)&&(['PAID','CANCELLED'].includes(String(value.status))||text(value.checkoutUrl))
 if(/^app\/paypal\/orders\/[^/]+\/resume$/.test(path))return method==='POST'&&obj(value)&&text(value.orderId)&&(['APPROVED','PAID'].includes(String(value.status))||value.status==='CHECKOUT'&&text(value.checkoutUrl))
 if(/^app\/paypal\/orders\/[^/]+\/(capture|close)$/.test(path))return method==='POST'&&paymentOrder(value)
 if(path==='app/usage')return obj(value)&&['music','lyrics'].every(k=>obj(value[k])&&num((value[k] as Row).dailyRemaining)&&num((value[k] as Row).active))
 if(path==='app/music-settings/catalog')return obj(value)&&num(value.version)&&list(value.groups,g=>obj(g)&&text(g.id)&&str(g.zh)&&str(g.en)&&num(g.max)&&list(g.options,o=>obj(o)&&text(o.id)&&str(o.zh)&&str(o.en)&&str(o.prompt)))&&list(value.recipes,r=>obj(r)&&text(r.id)&&str(r.zh)&&str(r.en)&&choices(r.choices))
 if(path==='app/music-settings/simplify')return obj(value)&&(method==='GET'?typeof value.available==='boolean':text(value.original)&&text(value.text)&&String(value.text).length<=300)
 if(path==='app/music-settings/preview'||/\/music-settings\/preview$/.test(path)||/\/generations\/[^/]+\/music-settings$/.test(path))return direction(value)
 if(/\/generations\/preflight$/.test(path))return method==='POST'&&obj(value)&&num(value.workVersion)&&text(value.revisionId)&&['styleCharacters','styleLimit','negativeCharacters','negativeLimit'].every(k=>num(value[k]))&&Number(value.styleCharacters)<=Number(value.styleLimit)&&Number(value.negativeCharacters)<=Number(value.negativeLimit)&&str(value.notice)
 if(/\/music-settings$/.test(path))return obj(value)&&work(value.work)&&(value.direction===null||direction(value.direction))
   &&(value.photoInspiration==null||obj(value.photoInspiration)&&text(value.photoInspiration.summary)&&text(value.photoInspiration.arrangement))
 if(path==='app/works')return method==='GET'?list(value,work):work(value)
 if(path==='app/works/library')return list(value,v=>obj(v)&&work(v.work)&&text(v.status))
 if(/^app\/works\/[^/]+$/.test(path)){
  if(method!=='DELETE')return work(value)
  // 兼容已发布旧接口返回被删除作品的形状，且必须与请求作品一致。
  let id:string;try{id=decodeURIComponent(path.slice('app/works/'.length))}catch{return false}
  return obj(value)&&(value.deleted===true||work(value)&&value.id===id)
 }
 if(/\/section-context$/.test(path))return obj(value)&&typeof value.available==='boolean'&&Array.isArray(value.lines)&&(!value.available||str(value.lyrics)&&num(value.duration))
 if(/\/section-edits$/.test(path))return method==='GET'?list(value,v=>obj(v)&&text(v.taskId)&&text(v.status)&&str(v.replacementLyrics)):task(value)
 if(/\/lyrics\/requests\//.test(path))return obj(value)&&['NOT_FOUND','PENDING','FAILED','SUCCEEDED','UNKNOWN'].includes(String(value.status))&&(value.status!=='SUCCEEDED'||text(value.revisionId))
 if(/\/lyrics\/rewrite-line$/.test(path))return method==='POST'&&obj(value)&&text(value.text)&&String(value.text).length<=160&&!/[\r\n\u2028\u2029]/.test(String(value.text))
 if(/^app\/works\/[^/]+\/lyrics\/rhyme-line$/.test(path))return method==='POST'&&lyricRhyme(value)
 if(/\/lyrics\/rewrite-selection$/.test(path))return method==='POST'&&obj(value)&&text(value.lyrics)&&String(value.lyrics).length<=12000
 if(/^public\/hot-songs\/[a-z0-9-]+\/lyrics$/.test(path)||/\/audio\/[^/]+\/lyrics(?:\/sync)?$/.test(path)||/^public\/artists\/[^/]+\/songs\/[^/]+\/\d+\/lyrics$/.test(path))return obj(value)&&text(value.status)&&str(value.text)&&list(value.lines,v=>obj(v)&&str(v.text)&&num(v.start)&&num(v.end))&&typeof value.canSync==='boolean'
 if(/\/lyrics(?:\/[^/]+(?:\/confirm)?)?$/.test(path)||/\/lyrics\/generate$/.test(path))return method==='GET'&&path.endsWith('/lyrics')?list(value,revision):revision(value)
 if(/\/audio\/[^/]+\/note$/.test(path))return method==='PUT'&&asset(value)&&obj(value)&&str(value.note)&&num(value.noteVersion)&&value.id===path.split('/').at(-2)
 if(/^app\/works\/[^/]+\/audio\/[^/]+$/.test(path)&&method==='DELETE')return obj(value)&&value.deleted===true&&value.assetId===path.split('/').at(-1)
 if(/\/audio\/selection$/.test(path))return obj(value)&&num(value.version)&&(value.assetId===null?value.asset===null:asset(value.asset)&&obj(value.asset)&&value.asset.id===value.assetId)
 if(/\/audio$/.test(path))return method==='GET'?list(value,asset):asset(value)
 if(/\/generations(?:\/[^/]+\/(?:cancel|close-checking))?$/.test(path))return method==='GET'?list(value,task):task(value)
 if(/\/covers\/edits$/.test(path))return coverEdit(value)
 if(/\/covers\/selection$/.test(path))return cover(value)
 if(/\/covers$/.test(path))return method==='GET'?cover(value):coverAsset(value)
 if(/\/shares$/.test(path))return obj(value)&&text(value.id)&&text(value.token)&&text(value.expiresAt)
 if(/\/download$/.test(path))return obj(value)&&typeof value.unlocked==='boolean'&&num(value.price)
 if(/^public\/gifts\//.test(path))return obj(value)&&str(value.title)&&str(value.lyrics)
 return Array.isArray(value)||obj(value)&&Object.keys(value).length>0
}
