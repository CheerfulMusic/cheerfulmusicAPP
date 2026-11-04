/** 复用浏览器已缓冲的实例，不以整首下载为播放前提，不写磁盘缓存。 */
export class NativeAudioPool {
  private entries=new Map<string,HTMLAudioElement>()
  constructor(private create:(source:string)=>HTMLAudioElement,private limit=2){if(limit<1)throw new Error('音频实例上限必须大于零')}
  acquire(source:string):HTMLAudioElement{
    const existing=this.entries.get(source)
    if(existing){this.entries.delete(source);this.entries.set(source,existing);return existing}
    const audio=this.create(source);this.entries.set(source,audio)
    while(this.entries.size>this.limit){const oldest=this.entries.keys().next().value!;this.dispose(this.entries.get(oldest)!);this.entries.delete(oldest)}
    return audio
  }
  private dispose(audio:HTMLAudioElement){audio.pause();audio.removeAttribute('src');audio.load()}
  clear(){for(const audio of this.entries.values())this.dispose(audio);this.entries.clear()}
}
