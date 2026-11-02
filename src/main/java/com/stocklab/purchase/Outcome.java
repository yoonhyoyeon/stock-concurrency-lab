package com.stocklab.purchase;

/** 구매 요청의 최종 결과. 같은 HTTP 상태라도 응답 본문의 result로 구분한다. (계획서 4.5) */
public enum Outcome {
    SUCCESS(200),
    SOLD_OUT(409),
    CONFLICT(409),
    LOCK_TIMEOUT(503),
    ERROR(500);

    private final int httpStatus;

    Outcome(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
