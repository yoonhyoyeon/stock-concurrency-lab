package com.stocklab.correctness;

import com.stocklab.admin.AdminService;
import com.stocklab.config.StartupReport;
import com.stocklab.metrics.LabCounters;
import com.stocklab.purchase.AfterReadHook;
import com.stocklab.purchase.NoOpAfterReadHook;
import com.stocklab.purchase.Outcome;
import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;
import com.stocklab.support.Evidence;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실험 1: 동시 구매의 정합성 검증 (PROJECT_PLAN 4장).
 *
 * 한 번의 실행:
 *  1. reset(상품 1개, 재고 50)
 *  2. 스레드 N개를 미리 만들고, 각 스레드가 ready.countDown() 후 start.await()로 대기
 *  3. 모두 준비되면 start.countDown()으로 동시에 facade.purchase(1) 호출 (작업마다 독립 트랜잭션)
 *  4. 모두 끝나면 별도 조회(JdbcTemplate)로 최종 재고 F와 구매 기록 수 L을 읽는다
 *  5. 정합성 기준 A = D = L, 0 ≤ F ≤ 50 을 판정하고 CSV 한 줄을 남긴다
 *
 * 운영 경로 그대로 실행한다: 테스트용 훅·배리어를 넣지 않는다(NoOpAfterReadHook).
 * 커넥션 부족 여부를 두 값으로 기록한다.
 *  - pending_max: 커넥션을 기다리는 스레드 수(Hikari pending)를 약 0.1ms 간격으로 표본 추출한 최댓값.
 *    Hikari는 바로 커넥션을 받는 스레드도 아주 잠깐 이 값에 세므로, 0~2 정도의 값은 대기가 아니다.
 *  - acquire_max_ms: 커넥션을 받기까지 걸린 시간의 최댓값(AcquireWaitTracker, 1ms 해상도).
 * M0은 위반이 나와도 실패로 처리하지 않고 기록만 한다. M1~M3는 정합성을 단언한다.
 */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(AcquireWaitTracker.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class CorrectnessExperiment {

    static final int INITIAL_STOCK = 50;
    static final int REPEATS = 5;

    @Autowired
    PurchaseFacade facade;
    @Autowired
    AdminService admin;
    @Autowired
    LabCounters counters;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    DataSource dataSource;
    @Autowired
    StartupReport startup;
    @Autowired
    AfterReadHook afterReadHook;
    @Autowired
    Environment environment;

    /** 정합성을 단언할지 여부. M0은 기록만 한다. */
    abstract boolean assertConsistency();

    /** 동시 구매 작업 수 N. 보조 실험은 N=100만 실행한다. */
    int[] ns() {
        return new int[]{10, 50, 100};
    }

    String experiment() {
        return "main";
    }

    Stream<Arguments> conditions() {
        return IntStream.of(ns()).boxed()
                .flatMap(n -> IntStream.rangeClosed(1, REPEATS).mapToObj(r -> Arguments.of(n, r)));
    }

    @ParameterizedTest(name = "N={0}, 반복 {1}")
    @MethodSource("conditions")
    void run(int n, int repeat) throws Exception {
        // 운영 경로 그대로인지 실행 중에 확인한다: 조회 직후 훅은 아무 일도 하지 않는 운영 구현이어야 하고,
        // SQL 로그를 켜는 verify 프로파일은 꺼져 있어야 한다.
        assertThat(afterReadHook).isExactlyInstanceOf(NoOpAfterReadHook.class);
        assertThat(environment.getActiveProfiles()).doesNotContain("verify");

        HikariDataSource hikari = (HikariDataSource) dataSource;
        admin.reset(1, INITIAL_STOCK);
        waitForFullPool(hikari);
        AcquireWaitTracker.reset();

        RunResult run = executeConcurrently(n, hikari.getHikariPoolMXBean());

        int finalStock = jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id = 1", Integer.class);
        long logs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_log", Long.class);
        Map<Outcome, Integer> byOutcome = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) {
            byOutcome.put(o, 0);
        }
        run.results.forEach(r -> byOutcome.merge(r.outcome(), 1, Integer::sum));
        LabCounters.Snapshot snapshot = counters.snapshot();
        AcquireWaitTracker.Snapshot acquire = AcquireWaitTracker.snapshot();

        int a = byOutcome.get(Outcome.SUCCESS);
        int d = INITIAL_STOCK - finalStock;
        boolean pass = a == d && d == logs && finalStock >= 0 && finalStock <= INITIAL_STOCK;
        CorrectnessRow row = new CorrectnessRow(
                experiment(), startup.strategy().toUpperCase(), hikari.getMaximumPoolSize(), n, repeat,
                a, finalStock, d, logs,
                byOutcome.get(Outcome.SOLD_OUT), byOutcome.get(Outcome.CONFLICT), byOutcome.get(Outcome.LOCK_TIMEOUT),
                byOutcome.get(Outcome.ERROR), snapshot.connectionTimeouts(),
                a - d, a > INITIAL_STOCK, Math.min(n, INITIAL_STOCK) - a, pass,
                snapshot.totalAttempts(), snapshot.conflictAttempts(), snapshot.maxAttempts(),
                acquire.acquisitions(), acquire.maxMs(),
                run.pendingMax, run.elapsedMs);
        CorrectnessCsv.append(row);
        Evidence.log("EXP1", row.toCsv());

        // 집계 자체의 일관성: 서버 집계의 커밋 성공 수와 응답의 SUCCESS 수가 같아야 한다.
        assertThat(snapshot.commitSuccess()).isEqualTo(a);
        assertThat(snapshot.requests()).isEqualTo(n);
        if (assertConsistency()) {
            assertThat(pass).as("A=D=L, 0<=F<=50: " + row.toCsv()).isTrue();
        }
    }

    private RunResult executeConcurrently(int n, HikariPoolMXBean pool) throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(n, n, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        executor.prestartAllCoreThreads();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicInteger pendingMax = new AtomicInteger();
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                pendingMax.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                LockSupport.parkNanos(100_000);   // 약 0.1ms 간격
            }
        }, "pending-sampler");
        try {
            List<Future<PurchaseResult>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return facade.purchase(1);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            sampler.start();
            long t0 = System.nanoTime();
            start.countDown();
            List<PurchaseResult> results = new ArrayList<>(n);
            for (Future<PurchaseResult> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            return new RunResult(results, pendingMax.get(), elapsedMs);
        } finally {
            sampling.set(false);
            sampler.join(1_000);
            executor.shutdownNow();
        }
    }

    /** 이전 실행이나 기동 직후의 커넥션 생성이 끝나 풀이 가득 찬 상태에서 시작한다. */
    private static void waitForFullPool(HikariDataSource hikari) throws InterruptedException {
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (pool.getIdleConnections() < hikari.getMaximumPoolSize()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("pool not full: idle=" + pool.getIdleConnections());
            }
            Thread.sleep(20);
        }
    }

    private record RunResult(List<PurchaseResult> results, int pendingMax, long elapsedMs) {
    }
}
