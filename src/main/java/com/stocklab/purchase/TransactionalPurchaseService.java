package com.stocklab.purchase;

/**
 * 재고 1개 차감을 하나의 트랜잭션으로 수행한다. 방식별 구현은 프로파일로 하나만 등록된다.
 * 구현체의 decrease()는 @Transactional이며, PurchaseFacade가 별도 빈으로 호출하므로
 * 호출 1회 = 트랜잭션 1개이다. 정상 반환은 커밋 완료를 뜻한다. (계획서 4.4)
 */
public interface TransactionalPurchaseService {

    void decrease(long productId);
}
