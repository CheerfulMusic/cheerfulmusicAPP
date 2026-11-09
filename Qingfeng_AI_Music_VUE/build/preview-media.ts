import {createHash} from 'node:crypto'
import {readFileSync,realpathSync} from 'node:fs'
import {isAbsolute,relative,resolve} from 'node:path'
import * as ts from 'typescript'
import type {Plugin} from 'vite'

export function previewMediaManifest(root:string) {
 const source=ts.createSourceFile('tracks.ts',readFileSync(resolve(root,'src/modules/home/model/tracks.ts'),'utf8'),ts.ScriptTarget.Latest,true)
 const declaration=source.statements.filter(ts.isVariableStatement).flatMap(s=>s.declarationList.declarations).find(d=>ts.isIdentifier(d.name)&&d.name.text==='tracks')
 if(!declaration?.initializer||!ts.isArrayLiteralExpression(declaration.initializer)||!declaration.initializer.elements.length)throw new Error('Cannot verify preview track assets')
 const paths=new Set<string>()
 for(const track of declaration.initializer.elements){
  if(!ts.isObjectLiteralExpression(track))throw new Error('Preview tracks must declare verifiable assets')
  for(const key of ['audio','cover']){
   const field=track.properties.find(p=>ts.isPropertyAssignment(p)&&p.name.getText(source)===key)
   if(!field||!ts.isPropertyAssignment(field)||!ts.isStringLiteral(field.initializer))throw new Error('Missing static preview '+key)
   const path=field.initializer.text
   if(!path.startsWith('/media/')||path.split('/').some(s=>s==='..'||s==='.'))throw new Error('Invalid preview asset path: '+path)
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
 return{version:1,assets}
}

export function previewMediaPlugin():Plugin {
 let root='',manifest:ReturnType<typeof previewMediaManifest>
 return{name:'verify-preview-media',apply:'build',configResolved(config){root=config.root},buildStart(){manifest=previewMediaManifest(root)},generateBundle(){this.emitFile({type:'asset',fileName:'preview-media-manifest.json',source:JSON.stringify(manifest,null,2)})}}
}
