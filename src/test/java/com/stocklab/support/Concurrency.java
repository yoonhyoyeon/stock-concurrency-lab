package com.stocklab.support;

import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class Concurrency {

    private Concurrency() {
    }

    /** n개 스레드에서 같은 상품을 동시에 구매하고 결과를 모은다. */
    public static List<PurchaseResult> purchaseConcurrently(PurchaseFacade facade, long productId, int n) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<PurchaseResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> facade.purchase(productId)));
            }
            List<PurchaseResult> results = new ArrayList<>();
            for (Future<PurchaseResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    public static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barrier failed", e);
        }
    }
}
