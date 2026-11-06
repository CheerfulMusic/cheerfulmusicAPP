package com.qingfeng.music.server.config;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.identity.application.IdentityService;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.FirstLoginCredits;
import com.qingfeng.music.infrastructure.persistence.identity.*;
import com.qingfeng.music.infrastructure.persistence.work.*;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.work.application.WorkService;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.mybatis.spring.annotation.MapperScan;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
@EnableTransactionManagement
@MapperScan("com.qingfeng.music.infrastructure.persistence")
public class BusinessConfiguration {
    @Bean FirstLoginCredits firstLoginCredits(CreditsLedger credits) { return new FirstLoginCredits(credits); }
    @Bean IdentityApi identityApi(IdentityMapper mapper, FirstLoginCredits firstLoginCredits) { return new IdentityService(new MyBatisIdentityRepository(mapper), firstLoginCredits); }
    @Bean WorkApi workApi(WorkMapper mapper) { return new WorkService(new MyBatisWorkRepository(mapper)); }
}
