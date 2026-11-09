package com.qingfeng.music.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 只能显式连接 57xxx 回环端口和固定验证库，禁止使用主库隧道。 */
@EnabledIfEnvironmentVariable(named="QF_QUICK_MYSQL_TEST_PORT",matches="57[0-9]{3}")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_QUICK_MYSQL_TEST_PORT}/qf_quick_creation_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
    "spring.datasource.username=root","spring.datasource.password=",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "qingfeng.lyrics.enabled=true","qingfeng.generation.scheduler-enabled=false","qingfeng.credits.enabled=false"
})
@ActiveProfiles("test")
class QuickCreationMySqlIntegrationTest extends QuickCreationIntegrationTest {}
