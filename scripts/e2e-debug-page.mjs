import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'
const CHROME = 'C:/Users/yth/AppData/Local/ms-playwright/chromium_headless_shell-1228/chrome-headless-shell-win64/chrome-headless-shell.exe'
const PORT = 9334
const chrome = spawn(CHROME, ['--headless','--disable-gpu','--no-sandbox','--hide-scrollbars',`--remote-debugging-port=${PORT}`,'--user-data-dir=D:/it_ai/_backup_lyrics_credits/cdp-debug','about:blank'], { stdio: 'ignore' })
let ws, nextId = 0; const pending = new Map(); const logs = []
function send(method, params = {}, sessionId) { const id = ++nextId; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params, sessionId })) }) }
for (let i = 0; i < 40; i++) { try { const v = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); if (v.webSocketDebuggerUrl) { ws = new WebSocket(v.webSocketDebuggerUrl); break } } catch {} await sleep(300) }
await new Promise(r => { ws.onopen = r })
ws.onmessage = (e) => { const m = JSON.parse(e.data)
  if (m.method === 'Runtime.consoleAPICalled') logs.push('console:' + m.params.args.map(a => a.value ?? a.description ?? '').join(' '))
  if (m.method === 'Runtime.exceptionThrown') logs.push('exception:' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text))
  if (m.method === 'Log.entryAdded') logs.push('log:' + m.params.entry.level + ':' + m.params.entry.text)
  if (m.id && pending.has(m.id)) { const { res, rej } = pending.get(m.id); pending.delete(m.id); m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result) } }
const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
await send('Page.enable', {}, sessionId); await send('Runtime.enable', {}, sessionId); await send('Log.enable', {}, sessionId)
await send('Page.navigate', { url: process.argv[2] || 'http://127.0.0.1:5173/create' }, sessionId)
await sleep(9000)
const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }, sessionId)).result?.value
console.log('URL      :', await ev('location.href'))
console.log('标题     :', await ev('document.title'))
console.log('body 文本:', JSON.stringify((await ev('document.body.innerText') || '').slice(0, 400)))
console.log('输入框   :', await ev("!!document.querySelector('#inspiration-input')"))
console.log('改写按钮 :', await ev("!!document.querySelector('.inspiration-rewrite')"))
console.log('API 直连 :', await ev("fetch('/api/app/credits').then(r=>r.status).catch(e=>'ERR:'+e.message)"))
console.log('\n--- 控制台/日志 ---'); logs.slice(0, 15).forEach(l => console.log(' ', l.slice(0, 220)))
ws.close(); chrome.kill(); process.exit(0)
