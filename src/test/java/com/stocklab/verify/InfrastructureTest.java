package com.stocklab.verify;

import com.stocklab.metrics.LabCounters;
import com.stocklab.purchase.Outcome;
import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;
import com.stocklab.support.Evidence;
import com.stocklab.support.SwitchableAfterReadHook;
import com.stocklab.support.VerifyTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** C1: Spring Boot·MySQL 연결과 reset·result API / C2: 실제 세션의 DB 설정 */
@VerifyTest
@ActiveProfiles({"m0", "verify"})
class InfrastructureTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    PurchaseFacade facade;
    @Autowired
    LabCounters counters;
    @Autowired
    SwitchableAfterReadHook hook;

    @AfterEach
    void clearHook() {
        hook.clear();
    }

    @Test
    @DisplayName("C1: reset으로 상품 K개와 초기 재고를 만들고 result로 재고·버전·집계를 조회한다")
    @SuppressWarnings("unchecked")
    void c1_resetAndResult() {
        ResponseEntity<Map> reset = rest.postForEntity("/admin/reset", Map.of("productCount", 3, "initialStock", 100), Map.class);
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);

        rest.postForEntity("/products/2/purchase", null, Map.class);

        Map<String, Object> result = rest.getForObject("/admin/result", Map.class);
        List<Map<String, Object>> products = (List<Map<String, Object>>) result.get("products");
        assertThat(products).hasSize(3);
        assertThat(products).extracting(p -> p.get("stock")).containsExactly(100, 99, 100);
        assertThat(products).extracting(p -> p.get("version")).containsOnly(0);
        Map<String, Object> c = (Map<String, Object>) result.get("counters");
        assertThat(c.get("requests")).isEqualTo(1);
        assertThat(c.get("commitSuccess")).isEqualTo(1);
        assertThat((Map<String, Object>) c.get("commitsByProduct")).containsEntry("2", 1);
        Evidence.log("C1", "reset(3,100) 후 상품2 구매 1회 → stocks=" + products.stream().map(p -> p.get("stock")).toList()
                + ", counters=" + c);

        // 다시 reset하면 집계도 0으로 돌아간다.
        rest.postForEntity("/admin/reset", Map.of("productCount", 1, "initialStock", 5), Map.class);
        Map<String, Object> after = rest.getForObject("/admin/result", Map.class);
        assertThat(((Map<String, Object>) after.get("counters")).get("requests")).isEqualTo(0);
        assertThat((List<?>) after.get("products")).hasSize(1);
    }

    @Test
    @DisplayName("C1: 진행 중인 요청이 있으면 reset을 거부한다(409)")
    void c1_resetRejectedWhileInFlight() throws Exception {
        rest.postForEntity("/admin/reset", Map.of("productCount", 1, "initialStock", 10), Map.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hook.set(productId -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<PurchaseResult> inFlight = CompletableFuture.supplyAsync(() -> facade.purchase(1));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(counters.inFlight()).isEqualTo(1);

        ResponseEntity<Map> rejected = rest.postForEntity("/admin/reset", Map.of("productCount", 1, "initialStock", 10), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        release.countDown();
        assertThat(inFlight.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.SUCCESS);
        ResponseEntity<Map> accepted = rest.postForEntity("/admin/reset", Map.of("productCount", 1, "initialStock", 10), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        Evidence.log("C1", "요청 진행 중 reset → " + rejected.getStatusCode().value() + " " + rejected.getBody()
                + ", 요청 종료 후 reset → " + accepted.getStatusCode().value());
    }

    @Test
    @DisplayName("C2: 애플리케이션 커넥션의 격리 수준·lock wait·max_connections가 계획 값과 같다")
    void c2_sessionSettings() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT @@transaction_isolation AS iso, @@innodb_lock_wait_timeout AS lw, @@max_connections AS mc, @@version AS v");
        assertThat(row.get("iso")).isEqualTo("REPEATABLE-READ");
        assertThat(((Number) row.get("lw")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("mc")).intValue()).isGreaterThanOrEqualTo(100);
        Evidence.log("C2", "session: " + row);
    }
}
