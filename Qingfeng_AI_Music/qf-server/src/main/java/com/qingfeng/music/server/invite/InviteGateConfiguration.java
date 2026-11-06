package com.qingfeng.music.server.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** 门禁默认关闭；生产由 /etc/qingfeng-music/invite-gate.properties 或同名环境变量开启。 */
@Configuration(proxyBeanMethods = false)
public class InviteGateConfiguration {

    @Bean
    InviteGateSettings inviteGateSettings(
            @Value("${qingfeng.invite-gate.enabled:false}") boolean enabled,
            @Value("${qingfeng.invite-gate.codes:}") String codes,
            @Value("${qingfeng.invite-gate.signing-key:}") String signingKey,
            @Value("${qingfeng.invite-gate.session-ttl:7d}") Duration sessionTtl,
            @Value("${qingfeng.invite-gate.attempts-per-minute:10}") int attemptsPerMinute,
            @Value("${qingfeng.invite-gate.secure-cookie:false}") boolean secureCookie) {
        List<String> parsed = codes == null || codes.isBlank()
                ? List.of()
                : Arrays.stream(codes.split(",")).map(String::trim).filter(code -> !code.isEmpty()).toList();
        return new InviteGateSettings(enabled, parsed, signingKey, sessionTtl, attemptsPerMinute, secureCookie);
    }

    @Bean
    InviteGateService inviteGateService(InviteGateSettings settings) {
        return new InviteGateService(settings);
    }

    @Bean
    InviteGateFilter inviteGateFilter(InviteGateService service) {
        return new InviteGateFilter(service);
    }

    @Bean
    FilterRegistrationBean<InviteGateFilter> inviteGateFilterRegistration(InviteGateFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
