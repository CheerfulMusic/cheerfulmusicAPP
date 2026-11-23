(() => {
  const meta = field => document.querySelector('meta[property="og:' + field + '"]').content
  const url = meta('url')
  const title = meta('title')
  const wechat = /MicroMessenger/i.test(navigator.userAgent)
  const status = document.getElementById('status')
  const manual = document.getElementById('manual')
  document.querySelector('img').addEventListener('error', event => { event.target.src = '/brand/share-default-cover.png' }, { once: true })
  async function copy() {
    try {
      await navigator.clipboard.writeText(url)
      status.textContent = '链接已复制，可粘贴到微信中打开。'
    } catch {
      manual.hidden = false
      manual.select()
      status.textContent = '请复制选中的歌曲链接，在微信中打开。'
    }
  }
  async function share(timeline) {
    if (wechat) {
      status.textContent = '点击微信右上角「…」，选择「' + (timeline ? '分享到朋友圈' : '发送给朋友') + '」。'
    } else if (navigator.share) {
      try { await navigator.share({ title, url, text: meta('description') }) }
      catch (error) { if (error.name !== 'AbortError') await copy() }
    } else await copy()
  }
  document.getElementById('friend').onclick = () => share(false)
  document.getElementById('timeline').onclick = () => share(true)
  document.getElementById('copy').onclick = copy
  if (!wechat) return
  const sdk = document.createElement('script')
  sdk.src = 'https://res.wx.qq.com/open/js/jweixin-1.6.0.js'
  const report = reason => {
    fetch('/api/wechat/jssdk-error?reason=' + reason, { method: 'POST', keepalive: true }).catch(() => {})
  }
  sdk.onerror = () => report('SDK_LOAD')
  sdk.onload = async () => {
    try {
      const response = await fetch('/api/wechat/jssdk-config?url=' + encodeURIComponent(location.href.split('#')[0]))
      if (!response.ok) throw new Error('HTTP ' + response.status)
      const config = await response.json()
      wx.ready(() => {
        const card = { title, link: url, imgUrl: meta('image') }
        wx.updateAppMessageShareData({ ...card, desc: meta('description') })
        wx.updateTimelineShareData(card)
      })
      wx.error(error => {
        const message = String(error.errMsg || '')
        console.warn('WeChat wx.config', message)
        report(/invalid signature/i.test(message) ? 'INVALID_SIGNATURE' :
          /permission|denied/i.test(message) ? 'PERMISSION_DENIED' : 'CONFIG_FAILED')
      })
      wx.config({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
    } catch (error) { report('CONFIG_FAILED'); console.warn('WeChat share configuration', error.message) }
  }
  document.head.appendChild(sdk)
})()