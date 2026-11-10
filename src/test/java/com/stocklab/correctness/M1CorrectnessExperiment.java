package com.stocklab.correctness;

import org.springframework.test.context.ActiveProfiles;

/** 실험 1 — M1 (낙관적 락, 재시도 없음). 커넥션 풀 100. */
@ActiveProfiles({"m1", "correctness"})
class M1CorrectnessExperiment extends CorrectnessExperiment {

    @Override
    boolean assertConsistency() {
        return true;
    }
}
