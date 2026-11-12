import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer as createHttpServer} from 'node:http';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {withLocalPhotoCapability,withLocalWorkMetadata,workMetadataRule} from './preview-work-routing.mjs';

test('metadata routing is exact, repeatable and rejects unknown config',()=>{
  const original='const target = "http://127.0.0.1:8080"; const config={proxy: {}}';
  const config=withLocalWorkMetadata(original);
  assert.equal(withLocalWorkMetadata(config),config);
  const oldConfig=original.replace('proxy: {',`proxy: {'${workMetadataRule}':{target},`);
  assert.equal(withLocalWorkMetadata(oldConfig).split(workMetadataRule).length,2);
  assert.match(withLocalWorkMetadata(oldConfig),/local-creation/);
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
    for(const [method,path] of [
      ['GET','/api/app/music-settings/catalog'],['POST','/api/app/music-settings/preview'],
      ['PUT','/api/app/works/abc/music-settings'],['POST','/api/app/works/abc/music-settings/preview'],
      ['GET','/api/app/works/abc/creative-direction'],['POST','/api/app/works/abc/lyrics/generate'],
      ['POST','/api/app/works/abc/lyrics/rewrite-line'],
      ['GET','/api/app/works/abc/lyrics/requests/key?check=1'],['POST','/api/app/works/abc/lyrics/revision/confirm'],
      ['POST','/api/app/works/abc/generations/preflight'],['POST','/api/app/works/abc/generations'],
    ]){
      const response=await fetch(url+path,{method});
      assert.deepEqual(await response.json(),{upstream:'local',method,path});
      assert.equal(response.headers.get('x-qf-preview-api'),'local-creation');
    }
    for(const path of ['/api/app/works/abc/audio/asset','/api/app/works/abc/covers/cover','/api/app/music-settings/simplify'])assert.equal((await(await fetch(url+path)).json()).upstream,'remote');
  }finally{await vite?.close();await Promise.all([local.server,remote.server].map(server=>new Promise(resolve=>server.close(resolve))));}
});

test('photo capability resolves locally while the original system request is waiting upstream',async()=>{
  let releaseRemote, notifyRemote;
  const remoteGate=new Promise(resolve=>{releaseRemote=resolve;});
  const remoteStarted=new Promise(resolve=>{notifyRemote=resolve;});
  const serve=async handler=>{
    const server=createHttpServer(handler);
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    return {server,url:'http://127.0.0.1:'+server.address().port};
  };
  const local=await serve((req,res)=>res.end(JSON.stringify({path:req.url,capabilities:{photoLyrics:true,quickCreation:false}})));
  const remote=await serve(async(req,res)=>{
    notifyRemote();
    await remoteGate;
    res.end(JSON.stringify({path:req.url,capabilities:{quickCreation:true}}));
  });
  let vite, quick;
  try{
    const original=`const target = ${JSON.stringify(local.url)}; export default {server:{proxy: {'^/api/public':{target:${JSON.stringify(remote.url)}}}}};`;
    const patched=withLocalPhotoCapability(original);
    assert.equal(withLocalPhotoCapability(patched),patched);
    const {default:config}=await import('data:text/javascript,'+encodeURIComponent(patched));
    assert.equal(config.define['import.meta.env.VITE_LOCAL_PHOTO_CAPABILITY'],'"true"');
    const require=createRequire(new URL('../Qingfeng_AI_Music_VUE/package.json',import.meta.url));
    const {createServer}=await import(pathToFileURL(require.resolve('vite')).href);
    vite=await createServer({...config,configFile:false,envDir:false,cacheDir:'.cache/preview-proxy-test',
      optimizeDeps:{noDiscovery:true,include:[]},server:{...config.server,host:'127.0.0.1',port:0,strictPort:true}});
    await vite.listen();
    const url='http://127.0.0.1:'+vite.httpServer.address().port;
    quick=fetch(url+'/api/public/system/info',{signal:AbortSignal.timeout(8000)}).then(response=>response.json());
    await Promise.race([remoteStarted,quick.then(()=>assert.fail('Expected the system request to wait upstream'))]);
    const photo=await fetch(url+'/api/public/system/photo-preview',{signal:AbortSignal.timeout(3000)}).then(response=>response.json());
    assert.deepEqual(photo,{path:'/api/public/system/info',capabilities:{photoLyrics:true,quickCreation:false}});
    releaseRemote();
    assert.deepEqual(await quick,{path:'/api/public/system/info',capabilities:{quickCreation:true}});
  }finally{
    releaseRemote();
    await quick?.catch(()=>{});
    await vite?.close();
    await Promise.all([local.server,remote.server].map(server=>new Promise(resolve=>server.close(resolve))));
  }
});
