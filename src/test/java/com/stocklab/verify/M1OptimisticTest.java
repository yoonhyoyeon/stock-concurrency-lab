package com.stocklab.verify;

import com.stocklab.admin.AdminService;
import com.stocklab.config.StartupReport;
import com.stocklab.metrics.LabCounters;
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

import static org.assertj.core.api.Assertions.assertThat;

@VerifyTest
@ActiveProfiles({"m1", "verify"})
class M1OptimisticTest {

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
    @Autowired
    LabCounters counters;

    @AfterEach
    void clearHook() {
        hook.clear();
    }

    @Test
    @DisplayName("C3: m1 실행에는 @Version이 있는 VersionedProduct만 등록되고 재시도 상한은 0이다")
    void c3_entityRegistration() {
        assertThat(startup.entityNames()).containsExactly("PurchaseLog", "VersionedProduct");
        assertThat(AopUtils.getTargetClass(service)).isEqualTo(OptimisticPurchaseService.class);
        assertThat(facade.maxRetries()).isZero();
        Evidence.log("C3", "m1 entities=" + startup.entityNames() + ", service=" + AopUtils.getTargetClass(service).getSimpleName()
                + ", maxRetries=" + facade.maxRetries());
    }

    @Test
    @DisplayName("C5: 같은 버전을 읽은 두 트랜잭션 중 한쪽만 커밋되고 다른 쪽은 CONFLICT(재시도 없음)")
    void c5_onlyOneOfTwoSameVersionCommits() {
        admin.reset(1, 50);
        CyclicBarrier barrier = new CyclicBarrier(2);
        hook.set(productId -> Concurrency.await(barrier));
        SqlCapture.clear();

        List<PurchaseResult> results = Concurrency.purchaseConcurrently(facade, 1, 2);

        assertThat(results).extracting(PurchaseResult::outcome).containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.CONFLICT);
        PurchaseResult conflict = results.stream().filter(r -> r.outcome() == Outcome.CONFLICT).findFirst().orElseThrow();
        assertThat(conflict.attempts()).isEqualTo(1);
        assertThat(conflict.conflicts()).isEqualTo(1);
        assertThat(db.stock(1)).isEqualTo(49);
        assertThat(db.version(1)).isEqualTo(1);
        LabCounters.Snapshot snapshot = counters.snapshot();
        assertThat(snapshot.conflictAttempts()).isEqualTo(1);
        // 두 요청: 본문 SELECT 2 + 커밋 UPDATE 2 + 충돌 쪽 예외 변환 SELECT 1
        assertThat(SqlCapture.productSelects(SqlCapture.Phase.BODY)).hasSize(2);
        assertThat(SqlCapture.productUpdates()).hasSize(2);
        assertThat(SqlCapture.productSelects(SqlCapture.Phase.COMMIT)).hasSize(1);
        Evidence.log("C5", "results=" + results + ", 재고 50 → " + db.stock(1) + ", version=" + db.version(1)
                + ", 충돌 예외 유형=" + snapshot.failureTypes());
        Evidence.log("C5", "SQL(두 요청 합계): 본문 SELECT " + SqlCapture.productSelects(SqlCapture.Phase.BODY).size()
                + ", UPDATE " + SqlCapture.productUpdates().size()
                + ", 커밋 단계 SELECT(충돌 예외 변환) " + SqlCapture.productSelects(SqlCapture.Phase.COMMIT).size());
    }

    @Test
    @DisplayName("C10: M1의 UPDATE는 버전을 SET하고 WHERE에서 버전을 검사한다")
    void c10_sql() {
        admin.reset(1, 50);
        SqlCapture.clear();
        facade.purchase(1);
        List<String> selects = SqlCapture.productSelects();
        List<String> updates = SqlCapture.productUpdates();
        assertThat(selects).hasSize(1);
        assertThat(selects.get(0)).doesNotContain("for update");
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).matches("update product set .*stock=\\?,\\s?version=\\? where id=\\? and version=\\?");
        Evidence.log("C10", "M1 SELECT: " + selects.get(0));
        Evidence.log("C10", "M1 UPDATE: " + updates.get(0));
    }
}
