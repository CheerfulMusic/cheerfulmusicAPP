package com.qingfeng.music.media.application.port;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

/** 业务层先做资源访问授权，再请求短期签名链接；数据库保存key而非临时URL。 */
public interface ObjectStorage {
    boolean isConfigured();
    String put(String key, InputStream content, long size, String contentType);
    URI createReadUrl(String key, Duration validity);
}
