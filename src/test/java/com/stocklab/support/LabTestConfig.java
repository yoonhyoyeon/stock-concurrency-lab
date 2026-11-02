package com.stocklab.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@TestConfiguration(proxyBeanMethods = false)
public class LabTestConfig {

    /** 운영용 NoOpAfterReadHook 대신 서비스에 주입된다. */
    @Bean
    @Primary
    public SwitchableAfterReadHook switchableAfterReadHook() {
        return new SwitchableAfterReadHook();
    }

    @Bean
    public DbProbe dbProbe(DataSource dataSource) {
        return new DbProbe(dataSource);
    }
}
