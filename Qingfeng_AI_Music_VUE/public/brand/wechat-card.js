// Shared by the standalone listener and the Discover song detail.
(() => {
  const entryUrl = location.href.split('#')[0]

  function prepare({ link, imgUrl, friendTitle, timelineTitle, description }, update) {
    const sdk = document.createElement('script')
    sdk.src = 'https://res.wx.qq.com/open/js/jweixin-1.6.0.js'
    let active = true
    const report = reason => {
      if (!active) return
      update('failed')
      fetch('/api/wechat/jssdk-error?reason=' + reason, { method: 'POST', keepalive: true }).catch(() => {})
    }
    const failure = error => {
      const message = String(error?.errMsg || '')
      report(/invalid signature/i.test(message) ? 'INVALID_SIGNATURE' :
        /permission|denied/i.test(message) ? 'PERMISSION_DENIED' : 'CONFIG_FAILED')
    }
    sdk.onerror = () => report('SDK_LOAD')
    sdk.onload = async () => {
      try {
        const response = await fetch('/api/wechat/jssdk-config?url=' + encodeURIComponent(entryUrl))
        if (!response.ok) throw new Error('HTTP ' + response.status)
        const config = await response.json()
        if (!active) return
        const wx = window.wx
        wx.ready(() => {
          if (!active) return
          const card = { link, imgUrl, fail: failure }
          try {
            update('ready')
            wx.updateAppMessageShareData({ ...card, title: friendTitle, desc: description })
            wx.updateTimelineShareData({ ...card, title: timelineTitle })
          } catch (error) { failure(error) }
        })
        wx.error(failure)
        wx.config({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
      } catch (error) { failure(error) }
    }
    document.head.appendChild(sdk)
    return () => { active = false; sdk.remove() }
  }

  window.cheerfulWechatCard = { entryUrl, prepare }
})()
