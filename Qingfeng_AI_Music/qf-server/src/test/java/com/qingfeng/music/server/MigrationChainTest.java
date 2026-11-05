package com.qingfeng.music.server;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:migration_chain;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class MigrationChainTest {
    @Autowired JdbcTemplate db;
    @Test void paymentMigrationsIncludePreservedV17() {
        List<String> versions=db.queryForList("SELECT version FROM flyway_schema_history WHERE success=true AND version IN ('15','16','17','18','19') ORDER BY installed_rank",String.class);
        assertEquals(List.of("15","16","17","18","19"),versions);
        assertEquals(1163199747, db.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='17'",Integer.class));
    }
}
