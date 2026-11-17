package com.qingfeng.music.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the same admission and recovery cases on the dedicated loopback MySQL schema. */
@EnabledIfEnvironmentVariable(named="QF_LYRICS_MYSQL_TEST_PORT",matches="57[0-9]{3}")
class UsageBudgetMySqlIntegrationTest extends UsageBudgetIntegrationTest {
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String port=System.getenv("QF_LYRICS_MYSQL_TEST_PORT");
        properties.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+port+
            "/qf_lyrics_recovery_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->"");
        properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
    }
}
