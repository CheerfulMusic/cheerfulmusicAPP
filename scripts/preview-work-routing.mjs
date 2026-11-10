import {readFile,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

export const workMetadataRule='^/api/app/works(?:/[^/?]+)?(?:[?]|$)';
export function withLocalWorkMetadata(config){
  if(config.includes(workMetadataRule))return config;
  const marker='proxy: {';
  if(config.split(marker).length!==2||!config.includes('const target ='))throw new Error('Unrecognized preview proxy configuration');
  // 精确匹配作品集合、列表和单个作品；音频、封面、歌词等子路径继续保留原代理。
  const route=`\n      '${workMetadataRule}': { target, changeOrigin: true, configure(proxy) { proxy.on('proxyRes', response => { response.headers['x-qf-preview-api']='local-work'; }); } },`;
  return config.replace(marker,marker+route);
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
  const [input,output]=process.argv.slice(2);
  if(!input||!output)throw new Error('Expected input and output config paths');
  await writeFile(output,withLocalWorkMetadata(await readFile(input,'utf8')),'utf8');
  console.log('Local preview work metadata routing prepared');
}
