package com.stocklab.purchase;

/**
 * 서비스가 상품을 조회한 직후 호출하는 확장 지점.
 * 운영 구현은 아무 일도 하지 않으며(NoOpAfterReadHook), 구현 확인 테스트에서만
 * 배리어·외부 갱신을 넣어 두 트랜잭션의 실행 순서를 재현 가능하게 만든다.
 * 네 방식에 동일하게 들어가므로 성능 비교에 주는 영향은 같다.
 */
public interface AfterReadHook {

    void afterRead(long productId);
}
