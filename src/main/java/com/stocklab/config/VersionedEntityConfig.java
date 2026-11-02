package com.stocklab.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** M1·M2 실행에서만 활성화된다. @Version이 있는 VersionedProduct와 공통 엔티티만 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile("versioned-entity")
@EntityScan(basePackages = {"com.stocklab.domain.common", "com.stocklab.domain.versioned"})
@EnableJpaRepositories(basePackages = {"com.stocklab.domain.common", "com.stocklab.domain.versioned"})
public class VersionedEntityConfig {
}
