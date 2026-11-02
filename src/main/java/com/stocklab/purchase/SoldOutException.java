package com.stocklab.purchase;

/** 재고가 없을 때 서비스가 던져 트랜잭션을 롤백시킨다. 재시도하지 않는다. */
public class SoldOutException extends RuntimeException {

    public SoldOutException(long productId) {
        super("sold out: product " + productId, null, false, false);
    }
}
