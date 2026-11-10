package com.stocklab.correctness;

/** 실험 1의 실행 1회 결과 (results/correctness/*.csv의 한 줄). 컬럼 정의는 PROJECT_PLAN 4.3. */
record CorrectnessRow(String experiment, String method, int poolSize, int n, int repeat,
                      int a, int f, int d, long l,
                      int soldOut, int conflict, int lockTimeout, int error, long connectionTimeout,
                      int lostUpdate, boolean oversell, int incomplete, boolean pass,
                      long totalAttempts, long conflictAttempts, long maxAttempts,
                      long acquisitions, double acquireMaxMs,
                      int pendingMax, long elapsedMs) {

    static final String HEADER = "experiment,method,pool_size,n,repeat,A,F,D,L,sold_out,conflict,lock_timeout,error,"
            + "connection_timeout,lost_update,oversell,incomplete,pass,total_attempts,conflict_attempts,max_attempts,"
            + "acquisitions,acquire_max_ms,pending_max,elapsed_ms";

    String toCsv() {
        return String.join(",",
                experiment, method, str(poolSize), str(n), str(repeat),
                str(a), str(f), str(d), str(l),
                str(soldOut), str(conflict), str(lockTimeout), str(error), str(connectionTimeout),
                str(lostUpdate), str(oversell), str(incomplete), str(pass),
                str(totalAttempts), str(conflictAttempts), str(maxAttempts),
                str(acquisitions), String.format(java.util.Locale.ROOT, "%.3f", acquireMaxMs),
                str(pendingMax), str(elapsedMs));
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }
}
