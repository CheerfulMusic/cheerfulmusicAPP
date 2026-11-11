package com.qingfeng.music.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 验收 1 与用户新增的「双轨 DDL 比对」：V32 在 H2 执行、版本唯一、两份 DDL 语义一致。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:agent_parity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AgentMigrationParityTest {

    private static final String FLYWAY = "db/migration/V32__agent_conversation.sql";
    private static final String DBA = "db/dba/V32_agent_conversation.dba.sql";

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

    /**
     * 防回归（裁决点 2）：db/migration 下除正式的 V&lt;version&gt;__&lt;description&gt;.sql 外，
     * 不应存在会被 Flyway 误识别的「双下划线变体」。Flyway 版本化命名是 V&lt;version&gt;__&lt;description&gt;.sql，
     * 任何同时满足「含双下划线且以 .sql 结尾」的文件都会被当作版本迁移解析；若描述段含点（如 .dba / .copy 变体，
     * 例 V32__agent_conversation.dba.sql），就会与正式迁移同版本冲突导致 Flyway 启动失败。
     * 同时禁止「非规范命名」留在 db/migration：发布门禁 release_gate 的迁移名校验与契约生成器的
     * 版本号解析都要求严格的 V&lt;version&gt;__&lt;description&gt;.sql，DBA 手工副本一律放 db/dba/。
     */
    @Test
    void noDoubleUnderscoreVariantMisrecognizedByFlyway() throws Exception {
        Pattern VERSIONED = Pattern.compile("^V(\\d+)__(.+)\\.sql$");
        List<String> offenders = new ArrayList<>();
        List<String> nonCanonical = new ArrayList<>();
        for (String name : listMigrationFiles()) {
            Matcher m = VERSIONED.matcher(name);
            if (m.matches() && m.group(2).contains(".")) {
                offenders.add(name);
            }
            if (!m.matches()) {
                nonCanonical.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "db/migration 下不应存在被 Flyway 误识别的双下划线变体（描述含点）：" + offenders);
        assertTrue(nonCanonical.isEmpty(),
                "db/migration 下只允许 V<version>__<description>.sql；DBA 手工副本请放 db/dba/：" + nonCanonical);
    }

    private static List<String> listMigrationFiles() throws Exception {
        List<String> names = new ArrayList<>();
        Enumeration<URL> roots = AgentMigrationParityTest.class.getClassLoader().getResources("db/migration");
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if ("file".equals(url.getProtocol())) {
                Path dir = Paths.get(url.toURI());
                try (var stream = Files.list(dir)) {
                    stream.map(p -> p.getFileName().toString())
                            .filter(n -> n.endsWith(".sql"))
                            .forEach(names::add);
                }
            }
        }
        if (names.isEmpty()) {
            // 兜底：classpath 未以文件形式暴露（如 jar 包），至少假定正式迁移存在
            names.add("V32__agent_conversation.sql");
        }
        return names;
    }
}
