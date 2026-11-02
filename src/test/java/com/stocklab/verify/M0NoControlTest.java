package com.stocklab.verify;

import com.stocklab.admin.AdminService;
import com.stocklab.config.StartupReport;
import com.stocklab.purchase.NoControlPurchaseService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

@VerifyTest
@ActiveProfiles({"m0", "verify"})
class M0NoControlTest {

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
    @DisplayName("C3: m0 실행에는 @Version이 없는 PlainProduct만 등록된다")
    void c3_entityRegistration() {
        assertThat(startup.entityNames()).containsExactly("PlainProduct", "PurchaseLog");
        assertThat(AopUtils.getTargetClass(service)).isEqualTo(NoControlPurchaseService.class);
        Evidence.log("C3", "m0 entities=" + startup.entityNames() + ", service=" + AopUtils.getTargetClass(service).getSimpleName());
    }

    @Test
    @DisplayName("C4: M0에서 순차 구매 10회 후 재고가 정확히 10 감소한다")
    void c4_sequentialTenPurchases() {
        admin.reset(1, 50);
        List<PurchaseResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(facade.purchase(1));
        }
        assertThat(results).allMatch(r -> r.outcome() == Outcome.SUCCESS && r.attempts() == 1);
        assertThat(db.stock(1)).isEqualTo(40);
        assertThat(db.version(1)).isZero();
        Evidence.log("C4", "M0 순차 10회: SUCCESS " + results.size() + "건, 재고 50 → " + db.stock(1) + ", version=" + db.version(1));
    }

    @Test
    @DisplayName("C10: M0의 UPDATE에는 버전 조건이 없고, 조회는 일반 SELECT이다")
    void c10_sql() {
        admin.reset(1, 50);
        SqlCapture.clear();
        facade.purchase(1);
        List<String> selects = SqlCapture.productSelects();
        List<String> updates = SqlCapture.productUpdates();
        assertThat(selects).hasSize(1);
        assertThat(selects.get(0)).doesNotContain("for update");
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).doesNotContain("version");
        assertThat(updates.get(0)).matches("update product set .*stock=\\? where id=\\?");
        Evidence.log("C10", "M0 SELECT: " + selects.get(0));
        Evidence.log("C10", "M0 UPDATE: " + updates.get(0));
    }

    @Test
    @DisplayName("참고(M0 최소 재현): 두 트랜잭션이 같은 재고를 읽으면 둘 다 성공하지만 재고는 1만 감소한다")
    void m0_lostUpdateMinimalReproduction() {
        admin.reset(1, 50);
        CyclicBarrier barrier = new CyclicBarrier(2);
        hook.set(productId -> Concurrency.await(barrier));
        List<PurchaseResult> results = Concurrency.purchaseConcurrently(facade, 1, 2);
        long success = results.stream().filter(r -> r.outcome() == Outcome.SUCCESS).count();
        int decrement = 50 - db.stock(1);
        assertThat(success).isEqualTo(2);
        assertThat(decrement).isEqualTo(1);
        Evidence.log("M0-REPRO", "조회 직후 배리어: 성공 A=" + success + ", 실제 차감 D=" + decrement + " → 갱신 손실 A-D=" + (success - decrement));
    }
}
