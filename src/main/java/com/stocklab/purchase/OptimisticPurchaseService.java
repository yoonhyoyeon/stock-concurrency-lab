package com.stocklab.purchase;

import com.stocklab.domain.versioned.VersionedProduct;
import com.stocklab.domain.versioned.VersionedProductRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M1·M2 낙관적 락: 버전 포함 조회 → 차감 → 커밋 시 버전 검사 UPDATE.
 * 갱신 대상이 없으면(다른 트랜잭션이 먼저 커밋) 커밋 단계에서 낙관적 락 예외가 발생하고 롤백된다.
 * 재시도 여부는 이 클래스가 아니라 PurchaseFacade가 결정한다(M1: 0회, M2: 최대 3회).
 */
@Service
@Profile({"m1", "m2"})
public class OptimisticPurchaseService implements TransactionalPurchaseService {

    private final VersionedProductRepository productRepository;
    private final AfterReadHook afterReadHook;
    private final PurchaseLogRecorder purchaseLogRecorder;

    public OptimisticPurchaseService(VersionedProductRepository productRepository,
                                     AfterReadHook afterReadHook,
                                     PurchaseLogRecorder purchaseLogRecorder) {
        this.productRepository = productRepository;
        this.afterReadHook = afterReadHook;
        this.purchaseLogRecorder = purchaseLogRecorder;
    }

    @Override
    @Transactional
    public void decrease(long productId) {
        VersionedProduct product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        afterReadHook.afterRead(productId);
        if (product.getStock() <= 0) {
            throw new SoldOutException(productId);
        }
        product.decreaseStock();
        purchaseLogRecorder.record(productId);
    }
}
