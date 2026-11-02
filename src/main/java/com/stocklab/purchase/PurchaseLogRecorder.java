package com.stocklab.purchase;

import com.stocklab.domain.common.PurchaseLog;
import com.stocklab.domain.common.PurchaseLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * lab.purchase-log.enabled=true일 때만 구매 기록을 남긴다. 서비스의 트랜잭션 안에서 호출되므로
 * 충돌·오류로 롤백되면 기록도 함께 롤백된다. 성능 실험에서는 모든 방식에서 끈다. (계획서 4.1)
 */
@Component
public class PurchaseLogRecorder {

    private final PurchaseLogRepository purchaseLogRepository;
    private final boolean enabled;

    public PurchaseLogRecorder(PurchaseLogRepository purchaseLogRepository,
                               @Value("${lab.purchase-log.enabled}") boolean enabled) {
        this.purchaseLogRepository = purchaseLogRepository;
        this.enabled = enabled;
    }

    public void record(long productId) {
        if (enabled) {
            purchaseLogRepository.save(new PurchaseLog(productId));
        }
    }

    public boolean enabled() {
        return enabled;
    }
}
