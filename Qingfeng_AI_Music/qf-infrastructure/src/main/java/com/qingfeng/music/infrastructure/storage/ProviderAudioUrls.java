package com.qingfeng.music.infrastructure.storage;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** 仅供已认证的供应商查询适配器登记结果；不向Web层提供登记入口。 */
public final class ProviderAudioUrls {
    private final ConcurrentHashMap<String,Instant> approved=new ConcurrentHashMap<>();
    public void approve(String value) {
        URI uri=URI.create(value);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
            ||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||value.length()>4096)
            throw new IllegalArgumentException("供应商音频地址无效");
        Instant now=Instant.now();approved.entrySet().removeIf(entry->entry.getValue().isBefore(now));
        if(approved.size()>=4096&&!approved.containsKey(value))throw new IllegalStateException("供应商音频登记容量已满");
        approved.put(value,now.plusSeconds(43200));
    }
    public boolean contains(URI uri) {var expiry=approved.get(uri.toString());return expiry!=null&&expiry.isAfter(Instant.now());}
}
