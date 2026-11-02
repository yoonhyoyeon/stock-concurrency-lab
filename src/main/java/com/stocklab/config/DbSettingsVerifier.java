package com.stocklab.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 기동 시 애플리케이션 커넥션(실제 세션)으로 MySQL 설정을 조회하고, 기대값과 다르면 기동을 실패시킨다.
 * (계획서 4.4 "MySQL 설정을 실제 세션에서 확인", 체크리스트 C2)
 * 내구성 관련 값은 비교하지 않고 환경 기록용으로 로그에만 남긴다.
 */
@Component
public class DbSettingsVerifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DbSettingsVerifier.class);

    private final JdbcTemplate jdbcTemplate;
    private final String expectedIsolation;
    private final int expectedLockWaitTimeout;
    private final int minMaxConnections;

    private volatile Map<String, Object> observed;

    public DbSettingsVerifier(JdbcTemplate jdbcTemplate,
                              @Value("${lab.db.expected-isolation}") String expectedIsolation,
                              @Value("${lab.db.expected-lock-wait-timeout}") int expectedLockWaitTimeout,
                              @Value("${lab.db.min-max-connections}") int minMaxConnections) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedIsolation = expectedIsolation;
        this.expectedLockWaitTimeout = expectedLockWaitTimeout;
        this.minMaxConnections = minMaxConnections;
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT @@version AS version,
                       @@transaction_isolation AS isolation,
                       @@innodb_lock_wait_timeout AS lock_wait_timeout,
                       @@max_connections AS max_connections,
                       @@innodb_flush_log_at_trx_commit AS flush_log_at_trx_commit,
                       @@sync_binlog AS sync_binlog,
                       @@log_bin AS log_bin,
                       @@innodb_buffer_pool_size AS buffer_pool_size
                """);
        this.observed = Map.copyOf(row);
        log.info("DB settings (session): {}", row);

        String isolation = String.valueOf(row.get("isolation"));
        int lockWait = ((Number) row.get("lock_wait_timeout")).intValue();
        int maxConnections = ((Number) row.get("max_connections")).intValue();

        if (!expectedIsolation.equals(isolation)) {
            throw new IllegalStateException("transaction_isolation=" + isolation + ", expected " + expectedIsolation);
        }
        if (lockWait != expectedLockWaitTimeout) {
            throw new IllegalStateException("innodb_lock_wait_timeout=" + lockWait + ", expected " + expectedLockWaitTimeout);
        }
        if (maxConnections < minMaxConnections) {
            throw new IllegalStateException("max_connections=" + maxConnections + ", expected >= " + minMaxConnections);
        }
    }

    public Map<String, Object> observed() {
        return observed;
    }
}
