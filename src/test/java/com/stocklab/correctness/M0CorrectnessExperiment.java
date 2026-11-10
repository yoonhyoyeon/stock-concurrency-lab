package com.stocklab.correctness;

import org.springframework.test.context.ActiveProfiles;

/** 실험 1 — M0 (무제어. 위반이 나와도 기록만 한다). 커넥션 풀 100. */
@ActiveProfiles({"m0", "correctness"})
class M0CorrectnessExperiment extends CorrectnessExperiment {

    @Override
    boolean assertConsistency() {
        return false;
    }
}
