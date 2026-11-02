package com.stocklab.purchase;

import com.stocklab.domain.plain.PlainProduct;
import com.stocklab.domain.plain.PlainProductRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M3 비관적 락: 쓰기 락을 획득한 조회(SELECT ... FOR UPDATE) → 재고 확인 → 차감 → 커밋 시 락 해제.
 * 다른 트랜잭션이 락을 보유하면 대기하며, 3초(innodb_lock_wait_timeout)를 넘으면 LOCK_TIMEOUT이 된다.
 */
@Service
@Profile("m3")
public class PessimisticPurchaseService implements TransactionalPurchaseService {

    private final PlainProductRepository productRepository;
    private final AfterReadHook afterReadHook;
    private final PurchaseLogRecorder purchaseLogRecorder;

    public PessimisticPurchaseService(PlainProductRepository productRepository,
                                      AfterReadHook afterReadHook,
                                      PurchaseLogRecorder purchaseLogRecorder) {
        this.productRepository = productRepository;
        this.afterReadHook = afterReadHook;
        this.purchaseLogRecorder = purchaseLogRecorder;
    }

    @Override
    @Transactional
    public void decrease(long productId) {
        PlainProduct product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        afterReadHook.afterRead(productId);
        if (product.getStock() <= 0) {
            throw new SoldOutException(productId);
        }
        product.decreaseStock();
        purchaseLogRecorder.record(productId);
    }
}
