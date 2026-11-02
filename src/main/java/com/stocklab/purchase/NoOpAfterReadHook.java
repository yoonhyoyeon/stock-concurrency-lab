package com.stocklab.purchase;

import org.springframework.stereotype.Component;

@Component
public class NoOpAfterReadHook implements AfterReadHook {

    @Override
    public void afterRead(long productId) {
        // 운영 경로에서는 아무 일도 하지 않는다.
    }
}
