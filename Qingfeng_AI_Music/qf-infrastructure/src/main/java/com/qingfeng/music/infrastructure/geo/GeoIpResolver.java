package com.qingfeng.music.infrastructure.geo;

import jakarta.annotation.PostConstruct;
import org.lionsoul.ip2region.xdb.LongByteArray;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * 离线 IP → 国家解析（ip2region v2）。对外只暴露「国家名」（中文，如 中国 / 美国），
 * 绝不对外暴露、也绝不持久化原始 IP——符合看板隐私最小化约定：site_event 只落 country 列。
 * 数据文件 ip2region.xdb 随 JAR 打包（src/main/resources）。若资源缺失或加载失败，
 * resolver 降级为不可用（resolveCountry 返回 null），不影响主流程与启动。
 *
 * <p>本类不在启动类 {@code com.qingfeng.music.server} 的默认组件扫描范围内，
 * 由 {@code com.qingfeng.music.server.config.GeoIpConfiguration} 以 {@code @Bean}
 * 显式装配；不要改回 {@code @Component}（扫描不到，会导致国家分布恒为空）。
 */
public class GeoIpResolver {
    private final Resource resource;
    private volatile Searcher searcher;
    private volatile boolean available;

    public GeoIpResolver(Resource resource) {
        this.resource = resource;
    }

    @PostConstruct
    public void init() {
        try (InputStream in = resource.getInputStream()) {
            byte[] data = in.readAllBytes();
            var buffer = new LongByteArray(data.length);
            buffer.append(data);
            searcher = Searcher.newWithBuffer(Version.IPv4, buffer);
            available = true;
        } catch (Exception e) {
            // 资源缺失 / 解析失败：降级为不可用，绝不抛错阻断启动。
            available = false;
            searcher = null;
        }
    }

    /**
     * 解析客户端 IP 所属国家（中文名）。无法解析、或非 IPv4（该数据文件不含 IPv6）一律返回 null，
     * 这些请求仅不计入国家分布，其余埋点数据照常落库。
     */
    public String resolveCountry(String ip) {
        Searcher current = searcher;
        if (ip == null || ip.isBlank() || !available || current == null) return null;
        // 该数据文件仅含 IPv4 段；IPv6 返回 null（不计入国家分布）。
        if (ip.indexOf(':') >= 0) return null;
        try {
            String region;
            synchronized (current) {
                region = current.search(ip);
            }
            if (region == null || region.isBlank()) return null;
            String country = region.split("\\|", -1)[0];
            return "0".equals(country) ? null : country;
        } catch (Exception e) {
            return null;
        }
    }
}
