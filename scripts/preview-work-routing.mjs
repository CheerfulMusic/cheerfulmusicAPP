import {readFile,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

export const workMetadataRule='^/api/app/works(?:/[^/?]+)?(?:[?]|$)';
export const creationSettingsRule='^/api/app/(?:music-settings/(?:catalog|preview)|works/[^/?]+/(?:music-settings(?:/preview)?|creative-direction|lyrics(?:/[^/?]+)*|generations(?:/preflight)?))(?:[?]|$)';
const photoCapabilityRule='^/api/public/system/photo-preview(?:[?]|$)';
const photoCapabilityFlag="'import.meta.env.VITE_LOCAL_PHOTO_CAPABILITY': JSON.stringify('true')";

export function withLocalPhotoCapability(config){
  const hasRoute=config.includes(photoCapabilityRule),hasFlag=config.includes(photoCapabilityFlag);
  if(hasRoute&&hasFlag)return config;
  const root='export default {',proxy='proxy: {';
  if(hasRoute||hasFlag||config.split(root).length!==2||config.split(proxy).length!==2
    ||!config.includes('const target =')||/\bdefine\s*:/.test(config))throw new Error('Unrecognized photo preview configuration');
  return config.replace(root,root+`\n  define: { ${photoCapabilityFlag} },`)
    .replace(proxy,proxy+`\n      '${photoCapabilityRule}': { target, changeOrigin: true, rewrite: () => '/api/public/system/info' },`);
}

export function withLocalWorkMetadata(config){
  const missing=[workMetadataRule,creationSettingsRule].filter(rule=>!config.includes(rule));
  if(!missing.length)return config;
  const marker='proxy: {';
  if(config.split(marker).length!==2||!config.includes('const target ='))throw new Error('Unrecognized preview proxy configuration');
  // 保存、写词和提交共用新版曲风校验。媒体仍交给原代理。
  const routes=missing.map(rule=>{
    const source=rule===workMetadataRule?'local-work':'local-creation';
    return `\n      '${rule}': { target, changeOrigin: true, configure(proxy) { proxy.on('proxyRes', response => { response.headers['x-qf-preview-api']='${source}'; }); } },`;
  });
  return config.replace(marker,marker+routes.join(''));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [input,output]=process.argv.slice(2);
  if(!input||!output)throw new Error('Expected input and output config paths');
  await writeFile(output,withLocalPhotoCapability(withLocalWorkMetadata(await readFile(input,'utf8'))),'utf8');
  console.log('Local preview work metadata routing prepared');
}
