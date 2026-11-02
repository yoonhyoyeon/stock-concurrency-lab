package com.stocklab.verify;

import com.stocklab.admin.AdminService;
import com.stocklab.config.StartupReport;
import com.stocklab.metrics.LabCounters;
import com.stocklab.purchase.Outcome;
import com.stocklab.purchase.PessimisticPurchaseService;
import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;
import com.stocklab.purchase.TransactionalPurchaseService;
import com.stocklab.support.DbProbe;
import com.stocklab.support.Evidence;
import com.stocklab.support.SqlCapture;
import com.stocklab.support.VerifyTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@VerifyTest
@ActiveProfiles({"m3", "verify"})
class M3PessimisticTest {

    @Autowired
    PurchaseFacade facade;
    @Autowired
    AdminService admin;
    @Autowired
    DbProbe db;
    @Autowired
    StartupReport startup;
    @Autowired
    TransactionalPurchaseService service;
    @Autowired
    LabCounters counters;
    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("C3: m3 실행에는 @Version이 없는 PlainProduct만 등록된다")
    void c3_entityRegistration() {
        assertThat(startup.entityNames()).containsExactly("PlainProduct", "PurchaseLog");
        assertThat(AopUtils.getTargetClass(service)).isEqualTo(PessimisticPurchaseService.class);
        Evidence.log("C3", "m3 entities=" + startup.entityNames() + ", service=" + AopUtils.getTargetClass(service).getSimpleName());
    }

    @Test
    @DisplayName("C7: 다른 트랜잭션이 락을 보유하면 대기한 뒤, 커밋된 최신 재고를 읽어 차감한다")
    void c7_waitsForLockThenReadsLatest() throws Exception {
        admin.reset(1, 50);
        long elapsedMs;
        PurchaseResult result;
        try (DbProbe.RowLock lock = db.lockRow(1)) {
            lock.decrementStock();                        // 락 보유 트랜잭션이 재고를 49로 바꿔 둔다(미커밋)
            long start = System.nanoTime();
            CompletableFuture<PurchaseResult> purchase = CompletableFuture.supplyAsync(() -> facade.purchase(1));
            Thread.sleep(1000);
            assertThat(purchase).isNotDone();             // 1초 동안 락 대기 중
            lock.commit();                                // 락 해제
            result = purchase.get(10, TimeUnit.SECONDS);
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
        }
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1000);
        assertThat(db.stock(1)).isEqualTo(48);           // 49(최신 값)에서 차감. 50을 읽었다면 49가 된다
        Evidence.log("C7", "락 보유 1초: result=" + result + ", 응답까지 " + elapsedMs + "ms, 재고 50 → (외부 −1) → " + db.stock(1));
    }

    @Test
    @DisplayName("C8: 락이 3초를 넘게 보유되면 503 LOCK_TIMEOUT을 반환하고 재시도하지 않는다")
    @SuppressWarnings("unchecked")
    void c8_lockTimeoutAfterThreeSeconds() throws Exception {
        admin.reset(1, 50);
        ResponseEntity<Map> response;
        long elapsedMs;
        try (DbProbe.RowLock lock = db.lockRow(1)) {
            long start = System.nanoTime();
            CompletableFuture<ResponseEntity<Map>> call =
                    CompletableFuture.supplyAsync(() -> rest.postForEntity("/products/1/purchase", null, Map.class));
            response = call.get(10, TimeUnit.SECONDS);    // 락은 이 시점까지(>3초) 계속 보유
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(4500);
        }
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("result", "LOCK_TIMEOUT").containsEntry("attempts", 1).containsEntry("conflicts", 0);
        assertThat(elapsedMs).isBetween(2800L, 4500L);
        assertThat(db.stock(1)).isEqualTo(50);
        Map<String, Long> failureTypes = counters.snapshot().failureTypes();
        assertThat(failureTypes.keySet()).anyMatch(k -> k.endsWith("#1205"));
        Evidence.log("C8", "락 계속 보유: HTTP " + response.getStatusCode().value() + " " + body + ", 응답까지 " + elapsedMs
                + "ms, 예외 유형=" + failureTypes + ", 재고=" + db.stock(1));
    }

    @Test
    @DisplayName("C10: M3의 조회는 SELECT ... FOR UPDATE이고 UPDATE에는 버전 조건이 없다")
    void c10_sql() {
        admin.reset(1, 50);
        SqlCapture.clear();
        facade.purchase(1);
        List<String> selects = SqlCapture.productSelects();
        List<String> updates = SqlCapture.productUpdates();
        assertThat(selects).hasSize(1);
        assertThat(selects.get(0)).endsWith("for update");
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).doesNotContain("version");
        Evidence.log("C10", "M3 SELECT: " + selects.get(0));
        Evidence.log("C10", "M3 UPDATE: " + updates.get(0));
    }
}
