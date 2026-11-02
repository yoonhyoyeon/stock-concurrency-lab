package com.stocklab.api;

import com.stocklab.purchase.PurchaseResult;

/** 응답 본문. 재시도 횟수는 attempts - 1이다. (계획서 4.5) */
public record PurchaseResponse(String result, long productId, int attempts, int conflicts) {

    public static PurchaseResponse from(PurchaseResult result) {
        return new PurchaseResponse(result.outcome().name(), result.productId(), result.attempts(), result.conflicts());
    }
}
