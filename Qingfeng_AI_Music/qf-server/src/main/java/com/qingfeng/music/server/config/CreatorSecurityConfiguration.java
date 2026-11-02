package com.qingfeng.music.server.config;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.CreatorSessionFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
public class CreatorSecurityConfiguration {
    @Bean CreatorSessionFilter creatorSessionFilter(IdentityApi identity) { return new CreatorSessionFilter(identity); }
    // 只在Security链内执行，避免Servlet容器重复执行或覆盖认证上下文。
    @Bean FilterRegistrationBean<CreatorSessionFilter> creatorFilterRegistration(CreatorSessionFilter filter) {
        var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }
}
