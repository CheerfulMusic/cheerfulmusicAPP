(() => {
  const meta = field => document.querySelector('meta[property="og:' + field + '"]').content
  const url = meta('url')
  const title = meta('title')
  const wechat = /MicroMessenger/i.test(navigator.userAgent)
  const byId = id => document.getElementById(id)
  const status = byId('status'), manual = byId('manual')
  const guide = byId('wechat-guide'), qr = byId('wechat-qr')
  const instruction = byId('channel-instruction'), feedback = byId('wechat-feedback')
  let selected = '', sdkState = 'loading', qrRequest = 0
  const artwork = document.getElementById('artwork') || document.querySelector('img')
  for (const img of [artwork, document.querySelector('.share-track img')].filter(Boolean)) {
    img.addEventListener('error', () => { img.src = '/brand/share-default-cover.png' }, { once: true })
  }
  const system = byId('system-share')
  system.hidden = wechat || !navigator.share
  system.onclick = async () => {
    status.textContent = ''
    try { await navigator.share({ title, url, text: meta('description') }) }
    catch (error) { if (error.name !== 'AbortError') status.textContent = '系统分享暂不可用，可选择微信好友或朋友圈。' }
  }
  function sdkFeedback() {
    if (wechat && selected) feedback.textContent = sdkState === 'failed' ? '歌曲卡片暂时无法准备，可稍后重试或使用音乐海报。' :
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
  }
  async function choose(destination) {
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
      instruction.textContent = '点微信右上角「…」，选择「' + menuItem + '」。'
      sdkFeedback()
      return
    }
    instruction.textContent = '用微信扫一扫打开歌曲，再点右上角「…」选择「' + menuItem + '」。'
    feedback.textContent = '正在生成二维码…'
    try {
      const renderer = await import('/brand/song-poster.js')
      const data = await renderer.wechatShareQr(url, destination)
      if (request !== qrRequest) return
      qr.src = data
      qr.hidden = false
      feedback.textContent = '同一部手机上可截图，再从微信扫一扫的相册打开。'
    } catch {
      if (request === qrRequest) feedback.textContent = '二维码暂时无法生成，可重新点选分享方式。'
    }
  }
  byId('friend').onclick = () => choose('friend')
  byId('timeline').onclick = () => choose('timeline')
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
        const card = { title, link: url, imgUrl: meta('image'), fail: failure }
        wx.updateAppMessageShareData({ ...card, desc: meta('description') })
        wx.updateTimelineShareData(card)
      })
      wx.error(failure)
      wx.config({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
    } catch (error) { report('CONFIG_FAILED'); console.warn('WeChat share configuration', error.message) }
  }
  document.head.appendChild(sdk)
})()
