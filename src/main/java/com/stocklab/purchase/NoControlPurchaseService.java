package com.stocklab.purchase;

import com.stocklab.domain.plain.PlainProduct;
import com.stocklab.domain.plain.PlainProductRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M0 무제어: 일반 조회 → 재고 확인 → 읽은 값에서 1 차감 → 변경 감지로 저장.
 * UPDATE에 버전 조건이 없고 조회 전에 명시적으로 잠그지 않는다. 정합성 문제 재현 기준선.
 */
@Service
@Profile("m0")
public class NoControlPurchaseService implements TransactionalPurchaseService {

    private final PlainProductRepository productRepository;
    private final AfterReadHook afterReadHook;
    private final PurchaseLogRecorder purchaseLogRecorder;

    public NoControlPurchaseService(PlainProductRepository productRepository,
                                    AfterReadHook afterReadHook,
                                    PurchaseLogRecorder purchaseLogRecorder) {
        this.productRepository = productRepository;
        this.afterReadHook = afterReadHook;
        this.purchaseLogRecorder = purchaseLogRecorder;
    }

    @Override
    @Transactional
    public void decrease(long productId) {
        PlainProduct product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        afterReadHook.afterRead(productId);
        if (product.getStock() <= 0) {
            throw new SoldOutException(productId);
        }
        product.decreaseStock();
        purchaseLogRecorder.record(productId);
    }
}
