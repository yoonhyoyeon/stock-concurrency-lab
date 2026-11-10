package com.stocklab.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기동 시 애플리케이션 커넥션(실제 세션)으로 MySQL 설정을 조회하고, 기대값과 다르면 기동을 실패시킨다.
 * (계획서 4.4 "MySQL 설정을 실제 세션에서 확인", 체크리스트 C2)
 * 내구성 관련 값은 비교하지 않고 환경 기록용으로 로그에만 남긴다.
 *
 * 커넥션 풀 크기도 함께 확인한다. 풀이 요청 처리 스레드(Tomcat) 수보다 작으면 동시 요청 일부가
 * DB 락이 아니라 커넥션 부족 때문에 기다리게 되어, 동시성 제어 방식의 비용과 구분할 수 없다.
 * 그래서 풀 크기 >= 스레드 수가 아니면 기동을 실패시킨다. (2주차, 지도교수 피드백)
 */
@Component
public class DbSettingsVerifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DbSettingsVerifier.class);

    private final JdbcTemplate jdbcTemplate;
    private final String expectedIsolation;
    private final int expectedLockWaitTimeout;
    private final int minMaxConnections;
    private final DataSource dataSource;
    private final int tomcatMaxThreads;
    private final boolean requirePoolCoversThreads;

    private volatile Map<String, Object> observed;

    public DbSettingsVerifier(JdbcTemplate jdbcTemplate,
                              @Value("${lab.db.expected-isolation}") String expectedIsolation,
                              @Value("${lab.db.expected-lock-wait-timeout}") int expectedLockWaitTimeout,
                              @Value("${lab.db.min-max-connections}") int minMaxConnections,
                              DataSource dataSource,
                              @Value("${server.tomcat.threads.max}") int tomcatMaxThreads,
                              @Value("${lab.db.require-pool-covers-threads}") boolean requirePoolCoversThreads) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.tomcatMaxThreads = tomcatMaxThreads;
        this.requirePoolCoversThreads = requirePoolCoversThreads;
        this.expectedIsolation = expectedIsolation;
        this.expectedLockWaitTimeout = expectedLockWaitTimeout;
        this.minMaxConnections = minMaxConnections;
    }

    @Override
    public void afterPropertiesSet() throws java.sql.SQLException {
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
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        Map<String, Object> all = new LinkedHashMap<>(row);
        all.put("hikari_maximum_pool_size", poolSize);
        all.put("tomcat_max_threads", tomcatMaxThreads);
        this.observed = Map.copyOf(all);
        log.info("DB settings (session): {}", all);

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
        if (maxConnections < poolSize) {
            throw new IllegalStateException("max_connections=" + maxConnections + " < hikari pool " + poolSize);
        }
        if (requirePoolCoversThreads && poolSize < tomcatMaxThreads) {
            throw new IllegalStateException("hikari pool " + poolSize + " < tomcat threads " + tomcatMaxThreads
                    + ": 요청 스레드가 커넥션을 기다리는 구간이 생긴다");
        }
    }

    public Map<String, Object> observed() {
        return observed;
    }
}
