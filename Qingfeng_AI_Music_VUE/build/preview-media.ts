import {createHash} from 'node:crypto'
import {existsSync,readFileSync,realpathSync} from 'node:fs'
import {isAbsolute,relative,resolve} from 'node:path'
import * as ts from 'typescript'
import type {Plugin} from 'vite'

export function previewMediaManifest(root:string) {
 const source=ts.createSourceFile('tracks.ts',readFileSync(resolve(root,'src/modules/home/model/tracks.ts'),'utf8'),ts.ScriptTarget.Latest,true)
 const declaration=source.statements.filter(ts.isVariableStatement).flatMap(s=>s.declarationList.declarations).find(d=>ts.isIdentifier(d.name)&&d.name.text==='tracks')
 if(!declaration?.initializer||!ts.isArrayLiteralExpression(declaration.initializer)||!declaration.initializer.elements.length)throw new Error('Cannot verify preview track assets')
 const paths=new Set<string>()
 const remoteAssets: {path:string;key:string;bytes:number;sha256:string}[]=[]
 const remoteFile=resolve(root,'public/recommendation-assets.json')
 const remoteManifest=existsSync(remoteFile)?JSON.parse(readFileSync(remoteFile,'utf8')):{assets:[]}
 for(const track of declaration.initializer.elements){
  if(!ts.isObjectLiteralExpression(track))throw new Error('Preview tracks must declare verifiable assets')
  for(const key of ['audio','cover']){
   const field=track.properties.find(p=>ts.isPropertyAssignment(p)&&p.name.getText(source).replace(/^['"]|['"]$/g,'')===key)
   if(!field||!ts.isPropertyAssignment(field)||!ts.isStringLiteral(field.initializer))throw new Error('Missing static preview '+key)
   const path=field.initializer.text
   if(!path.startsWith('/media/')||path.split('/').some(s=>s==='..'||s==='.'))throw new Error('Invalid preview asset path: '+path)
   const delivery=track.properties.find(p=>ts.isPropertyAssignment(p)&&p.name.getText(source).replace(/^['"]|['"]$/g,'')==='delivery')
   if(delivery&&ts.isPropertyAssignment(delivery)&&ts.isStringLiteral(delivery.initializer)&&delivery.initializer.text==='CDN'){
    const id=track.properties.find(p=>ts.isPropertyAssignment(p)&&p.name.getText(source).replace(/^['"]|['"]$/g,'')==='id')
    const trackId=id&&ts.isPropertyAssignment(id)&&ts.isStringLiteral(id.initializer)?id.initializer.text:''
    const suffix=key==='audio'?'-preview-128.mp3':'-cover.jpg'
    const asset=remoteManifest.assets.find((entry:{path:string})=>entry.path===path)
    if(!/^[a-z0-9-]+$/.test(trackId)||!asset
      ||asset.trackId!==trackId||asset.kind!==key||!Number.isSafeInteger(asset.bytes)||asset.bytes<=0
      ||!/^[a-f0-9]{64}$/.test(asset.sha256)){
     throw new Error('Invalid CDN preview asset: '+path)
    }
    const hash=asset.sha256.slice(0,12),date=String(asset.key).split('/')[2]||''
    const filename=path.slice(path.lastIndexOf('/')+1)
    const validPath=path===`/media/sample-${trackId}-v1${suffix}`
      ||path===`/media/recommendations/${trackId}-${hash}-${key==='audio'?'preview-128.mp3':'cover.jpg'}`
    if(!validPath||!/^\d{8}$/.test(date)
      ||asset.key!==`qingfeng/recommendation-samples/${date}/${key}/${hash}/${filename}`){
     throw new Error('Invalid CDN preview asset: '+path)
    }
    // Local fallback copies, when present, must be the same bytes as the uploaded object.
    const localFile=resolve(root,'public','.'+path)
    if(existsSync(localFile)){
     const bytes=readFileSync(localFile)
     if(bytes.length!==asset.bytes||createHash('sha256').update(bytes).digest('hex')!==asset.sha256)
      throw new Error('CDN fallback asset differs from uploaded object: '+path)
    }
    remoteAssets.push({path,key:asset.key,bytes:asset.bytes,sha256:asset.sha256})
    continue
   }
   paths.add(path)
  }
 }
 const publicRoot=realpathSync(resolve(root,'public'))
 const assets=[...paths].sort().map(path=>{
  let bytes:Buffer
  try{
   const filename=realpathSync(resolve(publicRoot,'.'+path)),local=relative(publicRoot,filename)
   if(local.startsWith('..')||isAbsolute(local))throw new Error('Asset outside public')
   bytes=readFileSync(filename)
  }catch{throw new Error('Missing preview asset: '+path+'; restore full Git checkout before building')}
  if(!bytes.length)throw new Error('Empty preview asset: '+path)
  if(path.endsWith('.mp3')&&!(bytes.subarray(0,3).toString()==='ID3'||bytes[0]===0xff&&(bytes[1]!&0xe0)===0xe0))throw new Error('Invalid MP3 preview asset: '+path)
  return{path,bytes:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')}
 })
 return{version:1,assets,remoteAssets}
}

export function previewMediaPlugin():Plugin {
 let root='',manifest:ReturnType<typeof previewMediaManifest>
 return{name:'verify-preview-media',apply:'build',configResolved(config){root=config.root},buildStart(){manifest=previewMediaManifest(root)},generateBundle(){this.emitFile({type:'asset',fileName:'preview-media-manifest.json',source:JSON.stringify(manifest,null,2)})}}
}
