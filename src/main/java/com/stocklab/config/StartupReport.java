package com.stocklab.config;

import com.stocklab.purchase.TransactionalPurchaseService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * 방식 프로파일(m0~m3)이 정확히 하나만 켜졌는지 확인하고, 등록된 엔티티와 서비스 구현을 로그로 남긴다.
 * (체크리스트 C3)
 */
@Component
public class StartupReport implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupReport.class);
    private static final List<String> STRATEGIES = List.of("m0", "m1", "m2", "m3");

    private final Environment environment;
    private final EntityManagerFactory entityManagerFactory;
    private final TransactionalPurchaseService purchaseService;
    private final int maxRetries;

    private volatile String strategy;
    private volatile TreeSet<String> entityNames;

    public StartupReport(Environment environment,
                         EntityManagerFactory entityManagerFactory,
                         TransactionalPurchaseService purchaseService,
                         @Value("${lab.retry.max-retries}") int maxRetries) {
        this.environment = environment;
        this.entityManagerFactory = entityManagerFactory;
        this.purchaseService = purchaseService;
        this.maxRetries = maxRetries;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> active = Arrays.stream(environment.getActiveProfiles())
                .filter(STRATEGIES::contains)
                .toList();
        if (active.size() != 1) {
            throw new IllegalStateException("방식 프로파일(m0~m3)을 정확히 하나 지정해야 합니다. 현재: " + active);
        }
        this.strategy = active.get(0);

        TreeSet<String> names = new TreeSet<>();
        for (EntityType<?> type : entityManagerFactory.getMetamodel().getEntities()) {
            names.add(type.getName());
        }
        this.entityNames = names;

        log.info("strategy={} profiles={} service={} maxRetries={} entities={}",
                strategy, Arrays.toString(environment.getActiveProfiles()),
                AopUtils.getTargetClass(purchaseService).getSimpleName(), maxRetries, names);
    }

    public String strategy() {
        return strategy;
    }

    public TreeSet<String> entityNames() {
        return entityNames;
    }

    public int maxRetries() {
        return maxRetries;
    }
}
