package com.stocklab.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * M0·M3 실행에서만 활성화된다. @Version이 없는 PlainProduct와 공통 엔티티만 등록한다.
 * Spring Boot의 기본 엔티티 스캔은 애플리케이션 패키지 전체를 대상으로 하므로,
 * 여기서 스캔 범위를 제한해 VersionedProduct가 함께 등록되지 않게 한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("plain-entity")
@EntityScan(basePackages = {"com.stocklab.domain.common", "com.stocklab.domain.plain"})
@EnableJpaRepositories(basePackages = {"com.stocklab.domain.common", "com.stocklab.domain.plain"})
public class PlainEntityConfig {
}
