package com.stocklab.metrics;

import com.stocklab.purchase.FailureClassifier;
import com.stocklab.purchase.Outcome;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 스레드 안전한 메모리 카운터. 요청별 로그나 크기 제한 없는 버퍼를 쓰지 않는다. (계획서 4.5)
 * 커밋 성공 수는 SUCCESS 결과 수와 같다(성공은 커밋 완료 후에만 집계).
 */
@Component
public class LabCounters {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final LongAdder requests = new LongAdder();
    private final LongAdder totalAttempts = new LongAdder();
    private final LongAdder conflictAttempts = new LongAdder();
    private final LongAccumulator maxAttempts = new LongAccumulator(Math::max, 0);
    private final Map<Outcome, LongAdder> outcomes = new EnumMap<>(Outcome.class);
    private final ConcurrentHashMap<Long, LongAdder> commitsByProduct = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> failureTypes = new ConcurrentHashMap<>();

    public LabCounters() {
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, new LongAdder());
        }
    }

    public void enter() {
        inFlight.incrementAndGet();
        requests.increment();
    }

    public void exit(long productId, Outcome outcome, int attempts, int conflicts) {
        outcomes.get(outcome).increment();
        totalAttempts.add(attempts);
        conflictAttempts.add(conflicts);
        maxAttempts.accumulate(attempts);
        if (outcome == Outcome.SUCCESS) {
            commitsByProduct.computeIfAbsent(productId, id -> new LongAdder()).increment();
        }
        inFlight.decrementAndGet();
    }

    /** 실패 시도의 예외 유형별 건수 (예: ObjectOptimisticLockingFailureException, CannotAcquireLockException#1205) */
    public void recordFailureType(Throwable throwable) {
        failureTypes.computeIfAbsent(FailureClassifier.describe(throwable), key -> new LongAdder()).increment();
    }

    public int inFlight() {
        return inFlight.get();
    }

    /** 진행 중인 요청이 없을 때만 호출해야 한다(AdminService가 확인). */
    public void reset() {
        requests.reset();
        totalAttempts.reset();
        conflictAttempts.reset();
        maxAttempts.reset();
        outcomes.values().forEach(LongAdder::reset);
        commitsByProduct.clear();
        failureTypes.clear();
    }

    public Snapshot snapshot() {
        Map<String, Long> outcomeCounts = new LinkedHashMap<>();
        outcomes.forEach((outcome, adder) -> outcomeCounts.put(outcome.name(), adder.sum()));
        Map<Long, Long> commits = new TreeMap<>();
        commitsByProduct.forEach((id, adder) -> commits.put(id, adder.sum()));
        Map<String, Long> failures = new TreeMap<>();
        failureTypes.forEach((type, adder) -> failures.put(type, adder.sum()));
        return new Snapshot(
                requests.sum(),
                inFlight.get(),
                outcomes.get(Outcome.SUCCESS).sum(),
                outcomeCounts,
                totalAttempts.sum(),
                conflictAttempts.sum(),
                maxAttempts.get(),
                commits,
                failures);
    }

    public record Snapshot(long requests,
                           int inFlight,
                           long commitSuccess,
                           Map<String, Long> outcomes,
                           long totalAttempts,
                           long conflictAttempts,
                           long maxAttempts,
                           Map<Long, Long> commitsByProduct,
                           Map<String, Long> failureTypes) {
    }
}
