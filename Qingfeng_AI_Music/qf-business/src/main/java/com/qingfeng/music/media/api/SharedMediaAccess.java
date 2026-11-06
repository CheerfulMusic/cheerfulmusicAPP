package com.qingfeng.music.media.api;
/** 仅供已验证分享授权的服务使用；不得直接映射为Web接口。 */
public interface SharedMediaAccess {
    MediaApi.Content content(String work,String asset);
    MediaApi.MediaRef ref(String work,String asset);
}
