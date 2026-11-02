package com.stocklab.support;

import com.stocklab.purchase.AfterReadHook;

import java.util.function.LongConsumer;

/** 테스트에서 조회 직후 동작(배리어, 외부 갱신 등)을 바꿔 끼울 수 있는 훅. 기본은 아무 일도 하지 않는다. */
public class SwitchableAfterReadHook implements AfterReadHook {

    private static final LongConsumer NO_OP = productId -> { };

    private volatile LongConsumer action = NO_OP;

    @Override
    public void afterRead(long productId) {
        action.accept(productId);
    }

    public void set(LongConsumer action) {
        this.action = action;
    }

    public void clear() {
        this.action = NO_OP;
    }
}
