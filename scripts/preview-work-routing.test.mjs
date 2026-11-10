import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer as createHttpServer} from 'node:http';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {withLocalWorkMetadata,workMetadataRule} from './preview-work-routing.mjs';

test('metadata routing is exact, repeatable and rejects unknown config',()=>{
  const original='const target = "http://127.0.0.1:8080"; const config={proxy: {}}';
  const config=withLocalWorkMetadata(original);
  assert.equal(withLocalWorkMetadata(config),config);
  assert.throws(()=>withLocalWorkMetadata('unknown configuration'));
  const match=new RegExp(workMetadataRule);
  for(const p of ['/api/app/works','/api/app/works?page=0','/api/app/works/library?page=0','/api/app/works/abc?acknowledgeCompleted=true'])assert.ok(match.test(p),p);
  for(const p of ['/api/app/works/abc/audio/id','/api/app/works/abc/covers','/api/app/works/abc/quick-creations/latest'])assert.ok(!match.test(p),p);
});

test('real Vite proxy sends metadata GET/POST/PUT/DELETE locally and preserves remote media',async()=>{
  const serve=async label=>{
    const server=createHttpServer((req,res)=>{res.setHeader('Content-Type','application/json');res.end(JSON.stringify({upstream:label,method:req.method,path:req.url}));});
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    return {server,url:'http://127.0.0.1:'+server.address().port};
  };
  const local=await serve('local'),remote=await serve('remote');let vite;
  try{
    const original=`const target = ${JSON.stringify(local.url)}; const production={target:${JSON.stringify(remote.url)},changeOrigin:true}; export default {server:{proxy: {'^/api/app':production,'/api':{target,changeOrigin:true}}}};`;
    const {default:config}=await import('data:text/javascript,'+encodeURIComponent(withLocalWorkMetadata(original)));
    const require=createRequire(new URL('../Qingfeng_AI_Music_VUE/package.json',import.meta.url));
    const {createServer}=await import(pathToFileURL(require.resolve('vite')).href);
    vite=await createServer({...config,configFile:false,envDir:false,cacheDir:'.cache/preview-proxy-test',optimizeDeps:{noDiscovery:true,include:[]},server:{...config.server,host:'127.0.0.1',port:0,strictPort:true}});
    await vite.listen();const url='http://127.0.0.1:'+vite.httpServer.address().port;
    for(const [method,path] of [['GET','/api/app/works/library?page=0'],['POST','/api/app/works'],['PUT','/api/app/works/abc'],['DELETE','/api/app/works/abc?acknowledgeCompleted=true']]){
      const response=await fetch(url+path,{method});const body=await response.json();
      assert.equal(body.upstream,'local');assert.equal(body.method,method);assert.equal(body.path,path);assert.equal(response.headers.get('x-qf-preview-api'),'local-work');
    }
    for(const path of ['/api/app/works/abc/audio/asset','/api/app/works/abc/covers/cover'])assert.equal((await(await fetch(url+path)).json()).upstream,'remote');
  }finally{await vite?.close();await Promise.all([local.server,remote.server].map(server=>new Promise(resolve=>server.close(resolve))));}
});
