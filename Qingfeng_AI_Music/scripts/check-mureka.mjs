// 仅使用本地私有配置。默认只读，付费请求需明确 --execute。
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const config = await readFile(path.join(project, 'config/local.properties'), 'utf8');
const key = config.split(/\r?\n/).find(line => line.startsWith('qingfeng.providers.mureka.api-key='))?.split('=').slice(1).join('=').trim();
if (!key) throw new Error('请先配置Mureka API Key');
const execute = process.argv.includes('--execute');
const body = execute ? JSON.parse(await readFile(path.join(project, 'scripts/mureka-smoke-request.json'), 'utf8')) : undefined;
const response = await fetch(`https://api.mureka.ai/v1/${execute ? 'song/generate' : 'account/billing'}`, {
  method: execute ? 'POST' : 'GET',
  headers: { Authorization: `Bearer ${key}`, Accept: 'application/json', ...(execute ? { 'Content-Type': 'application/json' } : {}) },
  body: body ? JSON.stringify(body) : undefined,
  signal: AbortSignal.timeout(30000),
});
const result = await response.json().catch(() => ({}));
// 不打印原始响应、账号标识、密钥、签名下载URL或个人故事。
console.log(JSON.stringify({ httpStatus: response.status, ...(execute ? { taskId: result.id, state: result.status } : { authenticated: response.ok, concurrentRequestLimit: result.concurrent_request_limit }) }));
if (!response.ok) process.exitCode = 1;
