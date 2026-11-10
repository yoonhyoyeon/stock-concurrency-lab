package com.stocklab.correctness;

import org.springframework.test.context.ActiveProfiles;

/** 실험 1 — M3 (비관적 락). 커넥션 풀 100. */
@ActiveProfiles({"m3", "correctness"})
class M3CorrectnessExperiment extends CorrectnessExperiment {

    @Override
    boolean assertConsistency() {
        return true;
    }
}
