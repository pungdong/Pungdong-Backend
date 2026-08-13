package com.diving.pungdong.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 하네스 스모크 — 실 MySQL + Flyway(V1~) + validate 로 컨텍스트가 뜨고 최신 마이그레이션이 적용되는지. */
class MySqlHarnessSmokeTest extends MySqlConcurrencyTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("실 MySQL 에 Flyway 마이그레이션이 다 적용되고 컨텍스트가 뜬다 (V20 version·V22 승인원장)")
    void bootsAgainstRealMySql() {
        Integer approvalTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_approval'",
                Integer.class);
        Integer versionCol = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='payment_order' AND COLUMN_NAME='version'",
                Integer.class);
        assertThat(approvalTable).isEqualTo(1); // V22
        assertThat(versionCol).isEqualTo(1);    // V20
    }
}
