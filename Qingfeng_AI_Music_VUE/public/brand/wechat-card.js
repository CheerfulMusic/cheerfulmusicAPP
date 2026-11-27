// Shared by the standalone listener and the Discover song detail.
(() => {
  const entryUrl = location.href.split('#')[0]

  function prepare({ link, imgUrl, friendTitle, timelineTitle, description }, update) {
    const sdk = document.createElement('script')
    sdk.src = 'https://res.wx.qq.com/open/js/jweixin-1.6.0.js'
    let active = true
    let sdkLoaded = false
    let failed = false
    const reported = new Set()
    const timer = setTimeout(() => report(sdkLoaded ? 'CONFIG_FAILED' : 'SDK_LOAD'), 12000)
    const report = reason => {
      if (!active || reported.has(reason)) return
      reported.add(reason)
      failed = true
      clearTimeout(timer)
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
      sdkLoaded = true
      if (!active || failed) return
      try {
        const response = await fetch('/api/wechat/jssdk-config?url=' + encodeURIComponent(entryUrl))
        if (!response.ok) throw new Error('HTTP ' + response.status)
        const config = await response.json()
        if (!active || failed) return
        const wx = window.wx
        wx.ready(() => {
          if (!active || failed) return
          const card = { link, imgUrl, fail: failure }
          const pending = new Set(['friend', 'timeline'])
          const ready = destination => {
            if (!active || failed || !pending.delete(destination) || pending.size) return
            clearTimeout(timer)
            update('ready')
          }
          try {
            wx.updateAppMessageShareData({ ...card, title: friendTitle, desc: description, success: () => ready('friend') })
            wx.updateTimelineShareData({ ...card, title: timelineTitle, success: () => ready('timeline') })
          } catch (error) { failure(error) }
        })
        wx.error(failure)
        wx.config({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
      } catch (error) { failure(error) }
    }
    document.head.appendChild(sdk)
    return () => { active = false; clearTimeout(timer); sdk.remove() }
  }

  window.cheerfulWechatCard = { entryUrl, prepare }
})()
