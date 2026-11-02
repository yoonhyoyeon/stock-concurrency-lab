package com.stocklab.admin;

import com.stocklab.config.DbSettingsVerifier;
import com.stocklab.config.StartupReport;
import com.stocklab.metrics.LabCounters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 로컬 실험용 관리 기능. JPA 엔티티를 거치지 않고 JdbcTemplate으로 직접 처리하므로
 * 방식(엔티티 프로파일)과 무관하게 같은 동작을 한다.
 */
@Service
public class AdminService {

    private static final int MAX_PRODUCTS = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final LabCounters counters;
    private final StartupReport startupReport;
    private final DbSettingsVerifier dbSettingsVerifier;

    public AdminService(JdbcTemplate jdbcTemplate,
                        TransactionTemplate transactionTemplate,
                        LabCounters counters,
                        StartupReport startupReport,
                        DbSettingsVerifier dbSettingsVerifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.counters = counters;
        this.startupReport = startupReport;
        this.dbSettingsVerifier = dbSettingsVerifier;
    }

    /** 상품 수·초기 재고·버전·구매 기록·집계를 초기화한다. 진행 중인 요청이 있으면 거부한다. */
    public synchronized void reset(int productCount, int initialStock) {
        if (productCount < 1 || productCount > MAX_PRODUCTS) {
            throw new IllegalArgumentException("productCount must be 1.." + MAX_PRODUCTS);
        }
        if (initialStock < 0) {
            throw new IllegalArgumentException("initialStock must be >= 0");
        }
        int inFlight = counters.inFlight();
        if (inFlight > 0) {
            throw new ResetRejectedException(inFlight);
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM purchase_log");
            jdbcTemplate.update("DELETE FROM product");
            List<Object[]> rows = new ArrayList<>(productCount);
            for (long id = 1; id <= productCount; id++) {
                rows.add(new Object[]{id, "product-" + id, initialStock});
            }
            jdbcTemplate.batchUpdate("INSERT INTO product (id, name, stock, version) VALUES (?, ?, ?, 0)", rows);
        });
        counters.reset();
    }

    public AdminResult result() {
        List<ProductState> products = jdbcTemplate.query(
                "SELECT id, stock, version FROM product ORDER BY id",
                (rs, rowNum) -> new ProductState(rs.getLong("id"), rs.getInt("stock"), rs.getLong("version")));
        Long purchaseLogCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_log", Long.class);
        return new AdminResult(
                startupReport.strategy(),
                startupReport.maxRetries(),
                startupReport.entityNames(),
                products,
                purchaseLogCount == null ? 0 : purchaseLogCount,
                counters.inFlight(),
                counters.snapshot(),
                dbSettingsVerifier.observed());
    }

    public record ProductState(long id, int stock, long version) {
    }

    public record AdminResult(String strategy,
                              int maxRetries,
                              TreeSet<String> entities,
                              List<ProductState> products,
                              long purchaseLogCount,
                              int inFlight,
                              LabCounters.Snapshot counters,
                              Map<String, Object> dbSettings) {
    }
}
