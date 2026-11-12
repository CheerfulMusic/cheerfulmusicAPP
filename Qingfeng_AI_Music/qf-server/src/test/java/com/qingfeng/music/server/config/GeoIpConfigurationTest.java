package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.geo.GeoIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 装配回归：GeoIpResolver 不在启动类默认扫描包内，必须由 GeoIpConfiguration 显式注册，
 * 且 @PostConstruct 真正执行（否则 resolveCountry 恒为 null，国家分布看板永远为空）。
 * 这是纯单测能覆盖不到的「接线」缺陷，故用轻量 Spring 容器断言兜底。
 */
class GeoIpConfigurationTest {

    @Test
    void beanIsRegisteredAndGeoIpIsUsable() {
        try (var ctx = new AnnotationConfigApplicationContext(GeoIpConfiguration.class)) {
            GeoIpResolver resolver = ctx.getBean(GeoIpResolver.class);
            assertNotNull(resolver);
            // init() 未执行时下面两个断言必然失败 → 可捕获「Bean 在但没初始化」的降级。
            assertEquals("美国", resolver.resolveCountry("8.8.8.8"));
            assertEquals("中国", resolver.resolveCountry("114.114.114.114"));
            assertNull(resolver.resolveCountry("192.168.1.1"), "私有地址不计入国家分布");
        }
    }
}
