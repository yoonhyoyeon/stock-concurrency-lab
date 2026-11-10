package com.stocklab.correctness;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 보조 실험 — M2, 커넥션 풀 64(1주차 설정), N=100.
 * 동시 작업 수가 풀보다 많을 때 커넥션을 기다리는 스레드가 생기는지 확인한다 (지도교수 피드백).
 * 본 실험(T1) 수치에는 넣지 않는다.
 */
@ActiveProfiles({"m2", "correctness"})
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=64",
        "spring.datasource.hikari.minimum-idle=64",
        "lab.db.require-pool-covers-threads=false"
})
class M2Pool64SupplementExperiment extends CorrectnessExperiment {

    @Override
    boolean assertConsistency() {
        return true;
    }

    @Override
    int[] ns() {
        return new int[]{100};
    }

    @Override
    String experiment() {
        return "pool64";
    }
}
