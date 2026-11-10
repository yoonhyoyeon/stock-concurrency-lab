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

import static org.assertj.core.api.Assertions.assertThat;

/** C9 (M1): 충돌로 롤백된 시도는 성공 집계와 구매 기록에 남지 않는다. 구매 기록 ON(correctness). */
@VerifyTest
@ActiveProfiles({"m1", "verify", "correctness"})
class M1RollbackLogTest {

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
    @DisplayName("C9: M1에서 같은 버전을 읽은 두 요청 중 충돌한 쪽의 구매 기록은 롤백된다")
    void c9_conflictedAttemptLeavesNoPurchaseLog() {
        admin.reset(1, 50);
        CyclicBarrier barrier = new CyclicBarrier(2);
        hook.set(productId -> Concurrency.await(barrier));

        List<PurchaseResult> results = Concurrency.purchaseConcurrently(facade, 1, 2);

        assertThat(results).extracting(PurchaseResult::outcome).containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.CONFLICT);
        long success = counters.snapshot().commitSuccess();
        long deducted = 50 - db.stock(1);
        long logs = db.purchaseLogCount();
        assertThat(success).isEqualTo(1);
        assertThat(deducted).isEqualTo(1);
        assertThat(logs).isEqualTo(1);
        Evidence.log("C9", "M1 충돌 1건: 성공 A=" + success + ", 차감 D=" + deducted + ", 구매 기록 L=" + logs);
    }
}
