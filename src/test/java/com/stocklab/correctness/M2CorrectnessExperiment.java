package com.stocklab.correctness;

import org.springframework.test.context.ActiveProfiles;

/** 실험 1 — M2 (낙관적 락, 최대 3회 재시도). 커넥션 풀 100. */
@ActiveProfiles({"m2", "correctness"})
class M2CorrectnessExperiment extends CorrectnessExperiment {

    @Override
    boolean assertConsistency() {
        return true;
    }
}
