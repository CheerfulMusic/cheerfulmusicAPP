(() => {
  const meta = field => document.querySelector('meta[property="og:' + field + '"]').content
  const url = meta('url')
  const titleMeta = document.querySelector('meta[property="og:title"]')
  const timelineTitle = titleMeta.content
  const friendTitle = titleMeta.dataset.songCredit || timelineTitle
  const wechat = /MicroMessenger|WindowsWechat|MacWechat|\bWeChat\//i.test(navigator.userAgent)
  const windowsWechat = wechat && /Windows NT|WindowsWechat/i.test(navigator.userAgent)
  const byId = id => document.getElementById(id)
  const status = byId('status'), manual = byId('manual')
  const guide = byId('wechat-guide'), qr = byId('wechat-qr')
  const instruction = byId('channel-instruction'), feedback = byId('wechat-feedback')
  const launch = byId('open-desktop-wechat'), handoffStatus = byId('handoff-status'), handoffLink = byId('handoff-link')
  const desktopDraft = byId('desktop-draft'), copyDraft = byId('copy-draft'), format = byId('friend-format')
  const handoff = desktopDraft && typeof window.cheerfulWechat?.draft === 'function' ? window.cheerfulWechat : undefined
  const friendData = { title: friendTitle, url, text: meta('description') }
  const timelineData = { ...friendData, title: timelineTitle }
  const clientMode = destination => !wechat && handoff?.mode(destination) === 'client'
  const timelineHint = byId('timeline-menu-hint')
  if (clientMode('friend')) {
    format.textContent = '打开微信'
    handoffLink.onfocus = () => handoffLink.select()
    if (handoff.isWindows) {
      const linkLabel = desktopDraft.querySelector('label[for="handoff-link"]')
      if (linkLabel) linkLabel.textContent = '歌曲分享链接'
      if (copyDraft) copyDraft.textContent = '复制分享链接'
    }
  }
  if (clientMode('timeline') && timelineHint) {
    timelineHint.textContent = '打开微信'
    timelineHint.hidden = false
  }
  if (wechat && timelineHint) {
    format.textContent = windowsWechat ? '微信菜单转发' : '右上角菜单发送'
    if (windowsWechat) timelineHint.textContent = '微信菜单发表'
    timelineHint.hidden = false
  }
  let selected = '', sdkState = 'loading', qrRequest = 0
  const artwork = document.getElementById('artwork') || document.querySelector('img')
  for (const img of [artwork, document.querySelector('.share-card-art img, .share-track img')].filter(Boolean)) {
    img.addEventListener('error', () => { img.src = '/brand/share-default-cover.png' }, { once: true })
  }
  const system = byId('system-share')
  system.hidden = wechat || !navigator.share
  system.onclick = async () => {
    status.textContent = ''
    try { await navigator.share({ title: timelineTitle, url, text: meta('description') }) }
    catch (error) { if (error.name !== 'AbortError') status.textContent = '系统分享暂不可用，可选择微信好友或朋友圈。' }
  }
  function sdkFeedback() {
    // Windows WeChat can build a card from the page metadata even if JS-SDK setup fails.
    if (wechat && !windowsWechat && selected) feedback.textContent = sdkState === 'failed' ? '歌曲卡片暂时无法准备，可稍后重试或使用音乐海报。' :
      sdkState === 'loading' ? '正在准备歌曲卡片…' : ''
  }
  function resetDestination() {
    qrRequest++
    selected = ''
    guide.hidden = true
    qr.hidden = true
    qr.removeAttribute('src')
    byId('friend').setAttribute('aria-pressed', 'false')
    byId('timeline').setAttribute('aria-pressed', 'false')
    feedback.textContent = ''
    instruction.textContent = ''
    if (desktopDraft) {
      handoffStatus.textContent = ''
      desktopDraft.hidden = true
      launch.hidden = true
    }
  }
  function showFeedback(result, request) {
    if (!result || request !== qrRequest || !byId('share-sheet').hasAttribute('open')) return
    handoffStatus.textContent = result.message
    if (result.clipboard === 'manual') { handoffLink.focus(); handoffLink.select() }
  }
  if (copyDraft) copyDraft.onclick = () => {
    if (!clientMode(selected)) return
    const request = qrRequest
    const data = selected === 'friend' ? friendData : timelineData
    void handoff.copyDraft(data, selected).then(result => showFeedback(result, request))
  }
  async function choose(destination, userClicked) {
    resetDestination()
    const request = qrRequest
    selected = destination
    byId(destination).setAttribute('aria-pressed', 'true')
    byId('poster-preview').hidden = true
    byId('more-share').open = false
    manual.hidden = true
    status.textContent = ''
    guide.hidden = false
    const menuItem = destination === 'friend' ? '发送给朋友' : '分享到朋友圈'
    if (wechat) {
      const desktopMenuItem = destination === 'friend' ? '转发…' : menuItem
      instruction.textContent = windowsWechat
        ? '打开微信浏览器的「…」菜单，选择「' + desktopMenuItem + '」。'
        : '点微信右上角「…」，选择「' + menuItem + '」。'
      sdkFeedback()
      return
    }
    const canOpenClient = clientMode(destination)
    const windowsHandoff = canOpenClient && handoff.isWindows
    if (canOpenClient) {
      const data = destination === 'friend' ? friendData : timelineData
      handoffLink.value = handoff.draft(data, destination)
      desktopDraft.hidden = false
      launch.hidden = false
      if (userClicked) void handoff.open(destination, data).then(result => showFeedback(result, request))
    }
    instruction.textContent = windowsHandoff ? handoff.windowsInstruction
      : '用微信扫一扫打开歌曲，再点右上角「…」选择「' + menuItem + '」即可分享歌曲卡片。'
    feedback.textContent = '正在生成二维码…'
    try {
      const renderer = await import('/brand/song-poster.js')
      const data = await renderer.wechatShareQr(url, destination)
      if (request !== qrRequest) return
      qr.src = data
      qr.hidden = false
      feedback.textContent = windowsHandoff ? '也可用手机微信扫码打开歌曲。' : '同一部手机上可截图，再从微信扫一扫的相册打开。'
    } catch {
      if (request === qrRequest) feedback.textContent = '二维码暂时无法生成，可重新点选分享方式。'
    }
  }
  // The player previews destinations from the URL hash with a synthetic click.
  byId('friend').onclick = event => choose('friend', event.isTrusted)
  byId('timeline').onclick = event => choose('timeline', event.isTrusted)
  byId('open-share').addEventListener('click', () => {
    resetDestination()
    byId('more-share').open = false
  })
  byId('poster').addEventListener('click', resetDestination)
  byId('more-share').addEventListener('toggle', () => { if (byId('more-share').open) resetDestination() })
  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
      status.textContent = '链接已复制，可粘贴到微信中打开。'
    } catch {
      manual.hidden = false
      manual.focus()
      manual.select()
      status.textContent = '请复制选中的歌曲链接，在微信中打开。'
    }
  }
  byId('copy').onclick = copy
  const audio = document.querySelector('audio')
  let playCounted = false
  audio.addEventListener('playing', () => {
    if (playCounted) return
    playCounted = true
    const endpoint = new URL('play', audio.src)
    fetch(endpoint, { method: 'POST', mode: 'same-origin', credentials: 'omit', keepalive: true }).catch(() => {})
  })
  audio.addEventListener('ended', () => { playCounted = false })
  if (!wechat) return
  const sdk = document.createElement('script')
  sdk.src = 'https://res.wx.qq.com/open/js/jweixin-1.6.0.js'
  const report = reason => {
    sdkState = 'failed'
    sdkFeedback()
    fetch('/api/wechat/jssdk-error?reason=' + reason, { method: 'POST', keepalive: true }).catch(() => {})
  }
  sdk.onerror = () => report('SDK_LOAD')
  sdk.onload = async () => {
    try {
      const response = await fetch('/api/wechat/jssdk-config?url=' + encodeURIComponent(location.href.split('#')[0]))
      if (!response.ok) throw new Error('HTTP ' + response.status)
      const config = await response.json()
      const failure = error => {
        const message = String(error?.errMsg || '')
        console.warn('WeChat JS-SDK', message)
        report(/invalid signature/i.test(message) ? 'INVALID_SIGNATURE' :
          /permission|denied/i.test(message) ? 'PERMISSION_DENIED' : 'CONFIG_FAILED')
      }
      wx.ready(() => {
        sdkState = 'ready'
        sdkFeedback()
        const card = { link: url, imgUrl: meta('image'), fail: failure }
        wx.updateAppMessageShareData({ ...card, title: friendTitle, desc: meta('description') })
        wx.updateTimelineShareData({ ...card, title: timelineTitle })
      })
      wx.error(failure)
      wx.config({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
    } catch (error) { report('CONFIG_FAILED'); console.warn('WeChat share configuration', error.message) }
  }
  document.head.appendChild(sdk)
})()
