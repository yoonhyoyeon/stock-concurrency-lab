package com.stocklab.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 구현 확인 테스트 공통 설정.
 * - 실제 MySQL(compose.yaml, 3307)에 연결한다.
 * - SqlCapture로 Hibernate SQL을 수집한다.
 * - 클래스가 끝나면 컨텍스트를 닫는다. 컨텍스트마다 커넥션 64개를 유지하므로,
 *   테스트 컨텍스트 캐시가 여러 개를 동시에 보유하면 max_connections(200)를 넘을 수 있다.
 * 방식 프로파일은 각 테스트 클래스의 @ActiveProfiles로 지정한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = SqlCapture.PROPERTY)
@Import(LabTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public @interface VerifyTest {
}
