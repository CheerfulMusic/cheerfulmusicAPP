package com.qingfeng.music.server;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class LyricsRecoveryMigrationTest {
    @Test void legacyUnknownBecomesTerminalWithoutDeletingHistoryOrChangingOtherStatuses(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:lyrics_recovery_migration;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE lyrics_composition_request(work_id VARCHAR(36),request_key VARCHAR(36),status VARCHAR(16),revision_id VARCHAR(36),created_at TIMESTAMP(6))");
        jdbc.update("INSERT INTO lyrics_composition_request VALUES('work','failed','UNKNOWN',NULL,CURRENT_TIMESTAMP),('work','live','PENDING',NULL,CURRENT_TIMESTAMP),('work','done','SUCCEEDED','revision',CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V11__lyrics_request_recovery.sql")).execute(ds);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM lyrics_composition_request",Integer.class));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM lyrics_composition_request WHERE request_key='failed'",String.class));
        assertEquals("LYRICS_LEGACY_RESULT_UNKNOWN",jdbc.queryForObject("SELECT error_code FROM lyrics_composition_request WHERE request_key='failed'",String.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM lyrics_composition_request WHERE request_key='live'",String.class));
        assertEquals("revision",jdbc.queryForObject("SELECT revision_id FROM lyrics_composition_request WHERE request_key='done'",String.class));
    }
}
