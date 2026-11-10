package com.stocklab.correctness;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 실험 1 전용: 커넥션을 빌릴 때마다 HikariCP가 알려 주는 "커넥션을 받기까지 걸린 시간"의 최댓값을 기록한다.
 * 주의: macOS에서 HikariCP는 밀리초 단위 시계(System.currentTimeMillis)를 쓰므로 값의 해상도가 1ms이다.
 * 그래서 "몇 ms 이상이면 대기"처럼 기준값으로 세지 않고, 최댓값만 비교한다.
 */
final class AcquireWaitTracker {

    private static final LongAccumulator MAX_NANOS = new LongAccumulator(Math::max, 0);
    private static final LongAdder ACQUISITIONS = new LongAdder();

    private AcquireWaitTracker() {
    }

    static void reset() {
        MAX_NANOS.reset();
        ACQUISITIONS.reset();
    }

    static Snapshot snapshot() {
        return new Snapshot(ACQUISITIONS.sum(), MAX_NANOS.get() / 1_000_000.0);
    }

    record Snapshot(long acquisitions, double maxMs) {
    }

    private static final class Tracker implements IMetricsTracker {
        @Override
        public void recordConnectionAcquiredNanos(long elapsedAcquiredNanos) {
            ACQUISITIONS.increment();
            MAX_NANOS.accumulate(elapsedAcquiredNanos);
        }
    }

    /** 풀이 시작되기 전에 측정기를 붙인다. Spring Boot의 Micrometer 연결은 이미 측정기가 있으면 건너뛴다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        static BeanPostProcessor acquireWaitTrackerInstaller() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof HikariDataSource hikari && hikari.getMetricsTrackerFactory() == null) {
                        MetricsTrackerFactory factory = (String poolName, PoolStats stats) -> new Tracker();
                        hikari.setMetricsTrackerFactory(factory);
                    }
                    return bean;
                }
            };
        }
    }
}
