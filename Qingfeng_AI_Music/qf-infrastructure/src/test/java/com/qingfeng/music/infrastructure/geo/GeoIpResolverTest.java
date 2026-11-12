package com.qingfeng.music.infrastructure.geo;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证内嵌 ip2region.xdb 的离线解析（资源随 JAR 打包，测试从 classpath 读取）。 */
class GeoIpResolverTest {

    private GeoIpResolver load() throws Exception {
        var resolver = new GeoIpResolver(new ClassPathResource("ip2region.xdb"));
        resolver.init();
        return resolver;
    }

    @Test
    void resolvesKnownIpv4() throws Exception {
        var resolver = load();
        assertEquals("美国", resolver.resolveCountry("8.8.8.8"));
        assertEquals("中国", resolver.resolveCountry("114.114.114.114"));
    }

    @Test
    void privateAndReservedReturnNull() throws Exception {
        var resolver = load();
        assertNull(resolver.resolveCountry("192.168.1.1"), "私有地址不计入国家分布");
        assertNull(resolver.resolveCountry("203.0.113.7"), "保留/文档地址不计入国家分布");
    }

    @Test
    void ipv6AndInvalidReturnNull() throws Exception {
        var resolver = load();
        assertNull(resolver.resolveCountry("2408:400a:10f:10::1"), "该数据文件不含 IPv6，返回 null");
        assertNull(resolver.resolveCountry("not-an-ip"));
        assertNull(resolver.resolveCountry(null));
        assertNull(resolver.resolveCountry(""));
    }
}
