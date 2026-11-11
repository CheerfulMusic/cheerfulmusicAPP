package com.qingfeng.music.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 验收 1 与用户新增的「双轨 DDL 比对」：V32 在 H2 执行、版本唯一、两份 DDL 语义一致。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:agent_parity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AgentMigrationParityTest {

    private static final String FLYWAY = "db/migration/V32__agent_conversation.sql";
    private static final String DBA = "db/migration/V32_agent_conversation.dba.sql";

    @Autowired JdbcTemplate db;

    private static String read(String path) throws Exception {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉注释、IF NOT EXISTS 与空白差异，只比对真正的 DDL 语义。 */
    private static String normalize(String sql) {
        return Arrays.stream(sql.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.startsWith("--"))
                .map(line -> line.replace("IF NOT EXISTS ", ""))
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"))
                .replaceAll("\\s+", " ");
    }

    @Test
    void flywayAppliedV32ExactlyOnce() {
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='32'", Integer.class);
        assertEquals(1, count, "V32 只能有一条 Flyway 记录；.dba.sql 不得被 Flyway 解析");
        String description = db.queryForObject(
                "SELECT description FROM flyway_schema_history WHERE version='32'", String.class);
        assertEquals("agent conversation", description);
    }

    @Test
    void dbaDdlIsSemanticallyIdenticalToFlywayDdl() throws Exception {
        String flyway = normalize(read(FLYWAY));
        String dba = normalize(read(DBA));
        assertEquals(flyway, dba, "两份 DDL 除 IF NOT EXISTS 外必须逐字一致");
        assertTrue(flyway.contains("CONSTRAINT uq_agent_message_seq UNIQUE(conversation_id, seq)"));
        assertTrue(flyway.contains("CONSTRAINT uq_agent_conversation_creator UNIQUE(creator_id)"));
    }

    @Test
    void threeTablesExistInH2() {
        for (String table : List.of("agent_conversation", "agent_message", "agent_conversation_state")) {
            Integer count = db.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)=?",
                    Integer.class, table);
            assertEquals(1, count, "缺少表：" + table);
        }
        Integer columns = db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='agent_message' AND LOWER(column_name)='state'",
                Integer.class);
        assertEquals(1, columns);
    }
}
