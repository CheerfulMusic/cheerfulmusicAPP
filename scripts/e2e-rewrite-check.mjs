// 真实浏览器验证 AI 改写的积分扣费：用 CDP 驱动 chrome-headless-shell，
// 真的去点「AI 润色描述」按钮（/agent 页与 /create 页走同一个 /api/app/inspiration/rewrite 接口），
// 再回读 /api/app/credits 核对余额与每日免费次数。
//
// 用法：node scripts/e2e-rewrite-check.mjs [baseUrl]
// 前提：后端 8080（本地 H2，credits.enabled=true、lyrics.enabled=true）与前端 dev 5173 已就绪。
import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'

const BASE = process.argv[2] || 'http://127.0.0.1:5173'
const CHROME = 'C:/Users/yth/AppData/Local/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-win64/chrome-headless-shell.exe'
const PORT = 9335

const chrome = spawn(CHROME, [
  '--headless', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
  `--remote-debugging-port=${PORT}`, '--user-data-dir=D:/it_ai/_backup_lyrics_credits/cdp-e2e', 'about:blank',
], { stdio: 'ignore' })

let ws, nextId = 0
const pending = new Map()
function send(method, params = {}, sessionId) {
  const id = ++nextId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params, sessionId }))
  })
}
async function evaluate(sessionId, expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId)
  if (r.exceptionDetails) throw new Error('页面脚本异常：' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text))
  return r.result.value
}
const waitFor = (sessionId, expr, tries = 60) => evaluate(sessionId, `(async()=>{for(let i=0;i<${tries};i++){if(${expr})return true;await new Promise(r=>setTimeout(r,500))}return false})()`)

try {
  let wsUrl
  for (let i = 0; i < 40; i++) {
    try { wsUrl = (await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()).webSocketDebuggerUrl; if (wsUrl) break } catch {}
    await sleep(300)
  }
  ws = new WebSocket(wsUrl)
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data)
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id); pending.delete(m.id)
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
    }
  }
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
  await send('Page.enable', {}, sessionId)
  await send('Runtime.enable', {}, sessionId)

  console.log('== 打开 Agent 页 ==')
  await send('Page.navigate', { url: `${BASE}/agent` }, sessionId)
  const hasInput = await waitFor(sessionId, `!!document.querySelector('.agent-composer-box input')`)
  if (!hasInput) throw new Error('未渲染出输入框：' + (await evaluate(sessionId, 'location.href')))
  console.log('  页面就绪：' + (await evaluate(sessionId, 'location.href')))

  const readCredits = `fetch('/api/app/credits').then(r=>r.json()).then(c=>({balance:c.balance,rewriteCost:c.rewriteCost,freeRemaining:c.rewriteFreeRemaining}))`
  let state = await evaluate(sessionId, readCredits)
  console.log(`  初始积分：余额=${state.balance} 改写单价=${state.rewriteCost} 免费剩余=${state.freeRemaining}`)

  // 发一条消息，让助手整理出创作需求（brief），润色按钮才会出现。
  await evaluate(sessionId, `(async()=>{
    const box=document.querySelector('.agent-composer-box input');
    const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
    setter.call(box,'我想写一首给妈妈的歌，感谢她这些年的照顾，记得第一次带我去看海。');
    box.dispatchEvent(new Event('input',{bubbles:true}));
    await new Promise(r=>setTimeout(r,300));
    document.querySelector('.agent-composer-box').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
    return true;
  })()`)
  const hasBrief = await waitFor(sessionId, `!!document.querySelector('.agent-brief-card')`, 80)
  if (!hasBrief) throw new Error('助手未产出创作需求卡片')
  console.log('  已产出创作需求卡片')

  const hintBefore = await evaluate(sessionId, `(document.querySelector('.agent-brief-cost .credit-cost-text')||{}).textContent||'(未渲染)'`)
  console.log('  润色按钮旁提示：' + hintBefore)

  console.log('== 连续点击「AI 润色描述」 ==')
  for (let i = 1; i <= 7; i++) {
    const outcome = await evaluate(sessionId, `(async()=>{
      const before=(document.querySelector('.agent-brief-story')||{}).textContent||'';
      const btn=document.querySelector('.agent-brief-actions button');
      if(!btn) return {ok:false,why:'没有找到润色按钮'};
      btn.click();
      for(let t=0;t<120;t++){
        await new Promise(r=>setTimeout(r,500));
        const err=document.querySelector('.agent-brief-error');
        if(err&&err.textContent.trim()) return {ok:false,msg:err.textContent.trim()};
        const now=(document.querySelector('.agent-brief-story')||{}).textContent||'';
        if(now&&now!==before) return {ok:true,msg:'已更新内容'};
      }
      return {ok:false,why:'等待 60 秒仍无结果'};
    })()`)
    const after = await evaluate(sessionId, readCredits)
    const hint = await evaluate(sessionId, `(document.querySelector('.agent-brief-cost .credit-cost-text')||{}).textContent||''`)
    const recharge = await evaluate(sessionId, `!!document.querySelector('.agent-brief-error a[href="/recharge"]')`)
    console.log(`  第 ${i} 次润色：${outcome.ok ? '成功' : '失败'}｜余额 ${state.balance}→${after.balance}｜免费剩余 ${state.freeRemaining}→${after.freeRemaining}`)
    console.log(`      提示文案：「${hint}」${recharge ? '｜含「去充值」入口' : ''}｜${outcome.msg || outcome.why || ''}`)
    state = after
  }
  const final = await evaluate(sessionId, readCredits)
  console.log('\n== 结论 ==')
  console.log(`  最终余额=${final.balance}  改写单价=${final.rewriteCost}  剩余免费次数=${final.freeRemaining}`)
} catch (e) {
  console.error('执行失败：' + e.message)
  process.exitCode = 1
} finally {
  try { ws?.close() } catch {}
  chrome.kill()
}
