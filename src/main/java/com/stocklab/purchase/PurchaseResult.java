package com.stocklab.purchase;

/**
 * @param attempts  이 요청에서 수행한 트랜잭션 시도 수 (재시도 횟수 = attempts - 1)
 * @param conflicts 이 요청에서 발생한 버전 충돌 시도 수
 */
public record PurchaseResult(Outcome outcome, long productId, int attempts, int conflicts) {
}
