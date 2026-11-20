import {readFile,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

export const workMetadataRule='^/api/app/works(?:/[^/?]+)?(?:[?]|$)';
export const creationSettingsRule='^/api/app/(?:music-settings/(?:catalog|preview)|works/[^/?]+/(?:music-settings(?:/preview)?|creative-direction|lyrics(?:/[^/?]+)*|generations(?:/preflight)?))(?:[?]|$)';
export const recommendationMediaRule='^/media/(?:sample-[a-z0-9-]+-v[12](?:-preview-128\\.mp3|-cover\\.jpg)|explore-(?:(?:love|family|travel)-v1\\.webp|(?:disco|rock|electronic|instrumental)-v1\\.jpg|guofeng-v1\\.svg)|recommendations/[a-z0-9-]+-[a-f0-9]{12}-(?:preview-128\\.mp3|cover\\.jpg))(?:[?]|$)';
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
  const missing=[workMetadataRule,creationSettingsRule,recommendationMediaRule].filter(rule=>!config.includes(JSON.stringify(rule))&&!config.includes(rule));
  if(!missing.length)return config;
  const marker='proxy: {';
  if(config.split(marker).length!==2||!config.includes('const target ='))throw new Error('Unrecognized preview proxy configuration');
  // 当前源码的创作接口和推荐媒体使用本机后端，推荐媒体由后端签发 CDN 地址。
  const routes=missing.map(rule=>{
    const source={
      [workMetadataRule]:'local-work',
      [creationSettingsRule]:'local-creation',
      [recommendationMediaRule]:'local-recommendation-media',
    }[rule];
    return `\n      ${JSON.stringify(rule)}: { target, changeOrigin: true, configure(proxy) { proxy.on('proxyRes', response => { response.headers['x-qf-preview-api']='${source}'; }); } },`;
  });
  return config.replace(marker,marker+routes.join(''));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [input,output]=process.argv.slice(2);
  if(!input||!output)throw new Error('Expected input and output config paths');
  await writeFile(output,withLocalPhotoCapability(withLocalWorkMetadata(await readFile(input,'utf8'))),'utf8');
  console.log('Local preview work metadata routing prepared');
}
