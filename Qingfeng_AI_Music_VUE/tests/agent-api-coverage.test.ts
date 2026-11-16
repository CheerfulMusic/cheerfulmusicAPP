import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { resolve, relative } from 'node:path'
const backend = resolve(process.cwd(), '../Qingfeng_AI_Music')
interface Entry { controller: string; source: string; mappingDeclarations: string[]; mode: string; flows: string[] }
const catalog = JSON.parse(readFileSync(resolve(backend, 'config/agent-api-coverage.json'), 'utf8')) as { controllers: Entry[] }
describe('Agent API coverage inventory', () => {
 it('requires every controller and every route change to have an explicit reviewed coverage entry', () => {
  const root = resolve(backend, 'qf-server/src/main/java')
  const files = readdirSync(root, { recursive: true }).filter(f => typeof f === 'string' && f.endsWith('Controller.java')) as string[]
  const seen: string[] = []
  for (const file of files) {
   const source = readFileSync(resolve(root, file), 'utf8')
   const mappings = [...source.matchAll(/@(?:Get|Post|Put|Delete|Patch|Request)Mapping(?:\((?:[^()]|\([^()]*\))*\))?/g)].map(m => m[0].replace(/\s+/g, ' '))
   if (!mappings.length) continue
   const path = relative(root, resolve(root, file)).replaceAll('\\', '/')
   const entry = catalog.controllers.find(c => c.source === path)
   expect(entry, `Uncovered controller: ${path}`).toBeDefined()
   expect(entry!.mappingDeclarations, `New or changed route: ${path}`).toEqual(mappings)
   expect(entry!.flows.length).toBeGreaterThan(0)
   seen.push(path)
  }
  expect(catalog.controllers.map(c => c.source).sort()).toEqual(seen.sort())
 })
})
