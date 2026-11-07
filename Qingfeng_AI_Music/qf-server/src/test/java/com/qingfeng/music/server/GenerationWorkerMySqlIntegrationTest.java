package com.qingfeng.music.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 仅运行于显式启动的临时 MySQL：固定测试库、57xxx 端口，不能指向日常主库隧道。
 * 默认跳过；供应商与下载仍沿用父类的纯离线替身。
 */
@EnabledIfEnvironmentVariable(named="QF_EXECUTION_MYSQL_TEST_PORT", matches="57[0-9]{3}")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_execution_concurrency_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
    "spring.datasource.username=root",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@ActiveProfiles("test")
class GenerationWorkerMySqlIntegrationTest extends GenerationWorkerIntegrationTest {}
