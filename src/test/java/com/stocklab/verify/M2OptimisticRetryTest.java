package com.stocklab.verify;

import com.stocklab.admin.AdminService;
import com.stocklab.config.StartupReport;
import com.stocklab.purchase.OptimisticPurchaseService;
import com.stocklab.purchase.Outcome;
import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;
import com.stocklab.purchase.TransactionalPurchaseService;
import com.stocklab.support.Concurrency;
import com.stocklab.support.DbProbe;
import com.stocklab.support.Evidence;
import com.stocklab.support.SqlCapture;
import com.stocklab.support.SwitchableAfterReadHook;
import com.stocklab.support.VerifyTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@VerifyTest
@ActiveProfiles({"m2", "verify"})
class M2OptimisticRetryTest {

    @Autowired
    PurchaseFacade facade;
    @Autowired
    AdminService admin;
    @Autowired
    DbProbe db;
    @Autowired
    SwitchableAfterReadHook hook;
    @Autowired
    StartupReport startup;
    @Autowired
    TransactionalPurchaseService service;

    @AfterEach
    void clearHook() {
        hook.clear();
    }

    @Test
    @DisplayName("C3: m2 실행에는 VersionedProduct만 등록되고 재시도 상한은 3이다")
    void c3_entityRegistration() {
        assertThat(startup.entityNames()).containsExactly("PurchaseLog", "VersionedProduct");
        assertThat(AopUtils.getTargetClass(service)).isEqualTo(OptimisticPurchaseService.class);
        assertThat(facade.maxRetries()).isEqualTo(3);
        Evidence.log("C3", "m2 entities=" + startup.entityNames() + ", service=" + AopUtils.getTargetClass(service).getSimpleName()
                + ", maxRetries=" + facade.maxRetries());
    }

    @Test
    @DisplayName("C6: 매 시도마다 충돌하면 시도마다 새로 조회하고, 총 4회 시도 후 CONFLICT를 반환한다")
    void c6_retryRereadsAndStopsAtFourAttempts() {
        admin.reset(1, 50);
        // 조회 직후마다 다른 트랜잭션이 버전을 올리고 커밋한다 → 이번 시도의 버전 검사 UPDATE는 항상 실패
        hook.set(db::bumpVersionInOtherTransaction);
        SqlCapture.clear();

        long start = System.nanoTime();
        PurchaseResult result = facade.purchase(1);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(result.conflicts()).isEqualTo(4);
        // 시도마다 서비스 본문에서 새로 조회한다(직전 엔티티 재사용 없음).
        assertThat(SqlCapture.productSelects(SqlCapture.Phase.BODY)).hasSize(4);
        // 시도마다 커밋 단계에서 버전 검사 UPDATE가 실행된다.
        assertThat(SqlCapture.productUpdates()).hasSize(4);
        // Hibernate는 커밋 실패 시 StaleObjectStateException을 JPA OptimisticLockException으로 바꾸면서
        // 같은 행을 한 번 더 조회한다(ExceptionConverterImpl.wrapStaleStateException). 충돌 1회당 SELECT 1회 추가.
        assertThat(SqlCapture.productSelects(SqlCapture.Phase.COMMIT)).hasSize(4);
        assertThat(db.stock(1)).isEqualTo(50);                 // 모두 롤백
        assertThat(db.version(1)).isEqualTo(4);                // 외부 트랜잭션이 올린 값만 남음
        assertThat(elapsedMs).isGreaterThanOrEqualTo(15);      // 재시도 전 5ms 대기 × 3회
        Evidence.log("C6", "강제 충돌: result=" + result + ", 본문 SELECT " + SqlCapture.productSelects(SqlCapture.Phase.BODY).size()
                + "회, 커밋 단계 UPDATE " + SqlCapture.productUpdates().size() + "회, 커밋 단계(예외 변환) SELECT "
                + SqlCapture.productSelects(SqlCapture.Phase.COMMIT).size() + "회, 재고=" + db.stock(1)
                + ", version=" + db.version(1) + ", 소요 " + elapsedMs + "ms");
        Evidence.log("C6", "SQL 순서: " + SqlCapture.sequence());
    }

    @Test
    @DisplayName("C6: 첫 시도에서 충돌한 요청은 새 트랜잭션에서 최신 재고를 다시 읽어 성공한다")
    void c6_retrySucceedsWithFreshRead() {
        admin.reset(1, 50);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        hook.set(productId -> {
            if (reads.incrementAndGet() <= 2) {   // 두 요청의 첫 시도만 같은 버전을 읽도록 맞춘다
                Concurrency.await(barrier);
            }
        });

        List<PurchaseResult> results = Concurrency.purchaseConcurrently(facade, 1, 2);

        assertThat(results).allMatch(r -> r.outcome() == Outcome.SUCCESS);
        assertThat(results).extracting(PurchaseResult::attempts).containsExactlyInAnyOrder(1, 2);
        assertThat(db.stock(1)).isEqualTo(48);
        assertThat(db.version(1)).isEqualTo(2);
        Evidence.log("C6", "첫 시도 충돌 후 재시도: results=" + results + ", 재고 50 → " + db.stock(1) + ", version=" + db.version(1));
    }
}
