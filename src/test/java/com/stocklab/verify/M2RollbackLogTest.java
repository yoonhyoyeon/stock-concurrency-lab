package com.stocklab.verify;

import com.stocklab.admin.AdminService;
import com.stocklab.metrics.LabCounters;
import com.stocklab.purchase.Outcome;
import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;
import com.stocklab.support.Concurrency;
import com.stocklab.support.DbProbe;
import com.stocklab.support.Evidence;
import com.stocklab.support.SwitchableAfterReadHook;
import com.stocklab.support.VerifyTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C9 (M2): 롤백된 시도는 성공 집계와 구매 기록에 남지 않는다.
 * C11: 재고 없음(SOLD_OUT)은 재시도하지 않는다.
 * 구매 기록 ON(correctness).
 */
@VerifyTest
@ActiveProfiles({"m2", "verify", "correctness"})
class M2RollbackLogTest {

    @Autowired
    PurchaseFacade facade;
    @Autowired
    AdminService admin;
    @Autowired
    DbProbe db;
    @Autowired
    SwitchableAfterReadHook hook;
    @Autowired
    LabCounters counters;

    @AfterEach
    void clearHook() {
        hook.clear();
    }

    @Test
    @DisplayName("C9: 4번 모두 충돌해 CONFLICT로 끝난 요청은 구매 기록을 하나도 남기지 않는다")
    void c9_allAttemptsRolledBack() {
        admin.reset(1, 50);
        hook.set(db::bumpVersionInOtherTransaction);   // 시도마다 충돌 강제

        PurchaseResult result = facade.purchase(1);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(counters.snapshot().commitSuccess()).isZero();
        assertThat(db.stock(1)).isEqualTo(50);
        assertThat(db.purchaseLogCount()).isZero();
        Evidence.log("C9", "M2 4회 충돌: result=" + result + ", 성공 A=0, 재고 50, 구매 기록 L=" + db.purchaseLogCount());
    }

    @Test
    @DisplayName("C9: 첫 시도는 롤백되고 재시도에서 성공한 요청은 구매 기록을 정확히 1건 남긴다")
    void c9_retriedSuccessLogsOnce() {
        admin.reset(1, 50);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        hook.set(productId -> {
            if (reads.incrementAndGet() <= 2) {
                Concurrency.await(barrier);
            }
        });

        List<PurchaseResult> results = Concurrency.purchaseConcurrently(facade, 1, 2);

        assertThat(results).allMatch(r -> r.outcome() == Outcome.SUCCESS);
        assertThat(results).extracting(PurchaseResult::attempts).containsExactlyInAnyOrder(1, 2);
        long success = counters.snapshot().commitSuccess();
        long deducted = 50 - db.stock(1);
        long logs = db.purchaseLogCount();
        assertThat(success).isEqualTo(2);
        assertThat(deducted).isEqualTo(2);
        assertThat(logs).isEqualTo(2);
        Evidence.log("C9", "M2 첫 시도 충돌 후 성공: 시도 " + results.stream().map(PurchaseResult::attempts).sorted().toList()
                + ", 성공 A=" + success + ", 차감 D=" + deducted + ", 구매 기록 L=" + logs);
    }

    @Test
    @DisplayName("C11: 재고가 0이면 M2도 재시도하지 않고 시도 1번에 SOLD_OUT을 반환한다")
    void c11_soldOutIsNotRetried() {
        admin.reset(1, 0);

        PurchaseResult result = facade.purchase(1);

        assertThat(result.outcome()).isEqualTo(Outcome.SOLD_OUT);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.conflicts()).isZero();
        assertThat(counters.snapshot().totalAttempts()).isEqualTo(1);
        assertThat(db.stock(1)).isZero();
        assertThat(db.purchaseLogCount()).isZero();
        Evidence.log("C11", "재고 0에서 M2 구매: result=" + result);
    }
}
