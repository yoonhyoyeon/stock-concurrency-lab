package com.stocklab.purchase;

import com.stocklab.metrics.LabCounters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 트랜잭션이 없는 진입점. 재시도와 집계를 담당한다. (계획서 4.4)
 *
 * <pre>
 * 시도 n: TransactionalPurchaseService.decrease() — 트랜잭션 시작 → 조회 → 차감 → 커밋
 *   성공          : 커밋 완료 후 SUCCESS
 *   버전 충돌      : 롤백 완료 후 충돌 집계 → (재시도 가능하면) 트랜잭션 밖에서 5ms 대기 → 새 트랜잭션으로 시도 n+1
 *   재시도 소진    : CONFLICT
 *   재고 없음      : SOLD_OUT, 재시도하지 않음
 *   락 대기 초과   : LOCK_TIMEOUT, 재시도하지 않음
 *   기타 오류      : ERROR, 재시도하지 않음
 * </pre>
 *
 * 커밋 시점에 발생하는 버전 충돌도 프록시 호출에서 예외로 올라오므로 여기서 잡힌다.
 * 각 시도는 새로 조회하므로 직전 시도의 엔티티를 재사용하지 않는다(OSIV off).
 */
@Component
public class PurchaseFacade {

    private final TransactionalPurchaseService purchaseService;
    private final LabCounters counters;
    private final int maxRetries;
    private final long retryDelayMs;

    public PurchaseFacade(TransactionalPurchaseService purchaseService,
                          LabCounters counters,
                          @Value("${lab.retry.max-retries}") int maxRetries,
                          @Value("${lab.retry.delay-ms}") long retryDelayMs) {
        this.purchaseService = purchaseService;
        this.counters = counters;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    public PurchaseResult purchase(long productId) {
        counters.enter();
        int attempts = 0;
        int conflicts = 0;
        Outcome outcome = Outcome.ERROR;
        try {
            while (true) {
                attempts++;
                try {
                    purchaseService.decrease(productId);
                    outcome = Outcome.SUCCESS;
                    break;
                } catch (OptimisticLockingFailureException e) {
                    // 버전 검사 실패로 분류된 경우만 충돌로 센다.
                    conflicts++;
                    counters.recordFailureType(e);
                    if (attempts > maxRetries) {
                        outcome = Outcome.CONFLICT;
                        break;
                    }
                    if (!sleepBeforeRetry()) {
                        outcome = Outcome.ERROR;
                        break;
                    }
                } catch (SoldOutException e) {
                    outcome = Outcome.SOLD_OUT;
                    break;
                } catch (RuntimeException e) {
                    counters.recordFailureType(e);
                    outcome = FailureClassifier.isLockWaitTimeout(e) ? Outcome.LOCK_TIMEOUT : Outcome.ERROR;
                    break;
                }
            }
            return new PurchaseResult(outcome, productId, attempts, conflicts);
        } finally {
            counters.exit(productId, outcome, attempts, conflicts);
        }
    }

    /** 이전 시도의 트랜잭션·커넥션이 반환된 뒤, 트랜잭션 밖에서 고정 간격만큼 대기한다. */
    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(retryDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public int maxRetries() {
        return maxRetries;
    }
}
