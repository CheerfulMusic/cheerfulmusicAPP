package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.geo.GeoIpResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * 离线 GeoIP 解析器的显式装配。
 *
 * <p>qf-infrastructure 位于 {@code com.qingfeng.music.infrastructure}，不在启动类
 * {@code MusicApplication}（{@code com.qingfeng.music.server}）的默认组件扫描范围内，
 * 因此这里按项目既有约定用 {@code @Bean} 装配。若不注册，{@code SiteEventController}
 * 的 {@code ObjectProvider<GeoIpResolver>} 会静默取到 null，导致 site_event.country
 * 永远为 null、看板「国家分布」恒为空。
 *
 * <p>只派生并持久化国家名，原始 IP 不入库、不进日志；资源缺失时 resolver 自行降级，
 * 不影响埋点主流程与启动。
 */
@Configuration(proxyBeanMethods = false)
public class GeoIpConfiguration {

    @Bean
    GeoIpResolver geoIpResolver() {
        return new GeoIpResolver(new ClassPathResource("ip2region.xdb"));
    }
}
