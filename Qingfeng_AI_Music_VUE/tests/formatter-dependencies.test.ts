// @vitest-environment node
import {expect,it} from 'vitest'
import {execFileSync} from 'node:child_process'
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {fileURLToPath} from 'node:url'

it('patched formatter dependencies preserve glob input and EditorConfig matching',()=>{
 const dir=mkdtempSync(join(tmpdir(),'qf-format-'))
 try{
  writeFileSync(join(dir,'.editorconfig'),'root = true\n[*.html]\nindent_style = space\nindent_size = 3\n')
  writeFileSync(join(dir,'sample.html'),'<div><div>text</div></div>')
  const cli=fileURLToPath(new URL('../node_modules/js-beautify/js/bin/html-beautify.js',import.meta.url))
  const output=execFileSync(process.execPath,[cli,'--file',join(dir,'*.html').replace(/\\/g,'/'),'--editorconfig'],{cwd:dir,encoding:'utf8',timeout:10000})
  expect(output).toContain('beautified sample.html')
  expect(readFileSync(join(dir,'sample.html'),'utf8').replace(/\r\n/g,'\n')).toContain('\n   <div>text</div>')
 }finally{rmSync(dir,{recursive:true,force:true})}
})
