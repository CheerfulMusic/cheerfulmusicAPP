package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.server.MusicApplication;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** 仅用于57xxx端口、固定独立测试库；默认不运行。 */
@EnabledIfEnvironmentVariable(named="QF_EXECUTION_MYSQL_TEST_PORT",matches="57[0-9]{3}")
@SpringBootTest(classes=MusicApplication.class,properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_execution_concurrency_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
    "spring.datasource.username=root","spring.datasource.password=",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
class KieRequestLimiterMySqlTest extends KieRequestLimiterTest {}
