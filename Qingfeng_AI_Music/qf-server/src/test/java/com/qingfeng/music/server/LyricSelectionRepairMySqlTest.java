package com.qingfeng.music.server;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/** Fixed loopback test port range and synthetic schema; cannot use the production SSH tunnel. */
@EnabledIfEnvironmentVariable(named="QF_LYRIC_REPAIR_MYSQL_PORT", matches="57[0-9]{3}")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_LYRIC_REPAIR_MYSQL_PORT}/qf_lyric_selection_repair_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
    "spring.datasource.username=root", "spring.datasource.password=", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "qingfeng.credits.enabled=true", "qingfeng.credits.rewrite-cost=2", "qingfeng.credits.rewrite-free-per-day=1"
})
class LyricSelectionRepairMySqlTest extends LyricSelectionRepairIntegrationTest {}
