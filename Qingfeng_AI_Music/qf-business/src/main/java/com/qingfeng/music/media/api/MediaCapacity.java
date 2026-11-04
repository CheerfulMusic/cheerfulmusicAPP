package com.qingfeng.music.media.api;
/** 音频与封面共用的创建者存储容量，不向调用模块暴露媒体登记细节。 */
public interface MediaCapacity {
 boolean reserve(String work,long bytes,long capacity);
 void release(String work,long bytes);
}
