import {expect,it} from 'vitest'
import {readFileSync} from 'node:fs'
it.each(['cheerfulmusic.nginx.conf','logto/logto.nginx.conf'])('ingress %s overwrites spoofable forwarding headers',name=>{
 const text=readFileSync(new URL(`../../Qingfeng_AI_Music/deploy/${name}`,import.meta.url),'utf8')
 const blocks=text.split(/location\s/).filter(block=>block.includes('proxy_pass'))
 expect(blocks.length).toBeGreaterThan(0)
 for(const block of blocks){
  expect(block).toContain('proxy_set_header X-Forwarded-For $remote_addr;')
  expect(block).toContain('proxy_set_header Forwarded "";')
  expect(block).toContain('proxy_set_header X-Forwarded-Port $server_port;')
  expect(block).toMatch(/proxy_set_header X-Forwarded-Host \$(host|http_host);/)
  expect(block).not.toContain('$proxy_add_x_forwarded_for')
 }
})
