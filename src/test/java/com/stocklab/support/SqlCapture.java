package com.stocklab.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트 전용 Hibernate StatementInspector. Hibernate가 실제로 실행하려는 SQL을 수집해
 * 방식별 SQL 형태(버전 조건, FOR UPDATE)를 로그 형식에 의존하지 않고 확인한다. (C10)
 * JdbcTemplate(관리 API, 테스트 보조 커넥션)의 SQL은 수집되지 않는다.
 *
 * 각 SQL이 어느 단계에서 나왔는지도 기록한다.
 * - BODY  : 서비스 메서드 본문(조회 등)
 * - COMMIT: 트랜잭션 커밋 단계(flush의 UPDATE, 커밋 실패 시 Hibernate 예외 변환 과정의 조회 등)
 */
public class SqlCapture implements StatementInspector {

    public static final String PROPERTY =
            "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.stocklab.support.SqlCapture";

    public enum Phase { BODY, COMMIT }

    public record Captured(String sql, Phase phase) {
    }

    private static final List<Captured> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(new Captured(normalize(sql), phaseOfCurrentCall()));
        return sql;
    }

    private static Phase phaseOfCurrentCall() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().equals("org.hibernate.engine.transaction.internal.TransactionImpl")
                    && frame.getMethodName().equals("commit")) {
                return Phase.COMMIT;
            }
        }
        return Phase.BODY;
    }

    private static String normalize(String sql) {
        return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    public static List<Captured> all() {
        return List.copyOf(STATEMENTS);
    }

    public static List<String> productSelects() {
        return STATEMENTS.stream().map(Captured::sql)
                .filter(s -> s.startsWith("select") && s.contains(" from product ")).toList();
    }

    public static List<String> productSelects(Phase phase) {
        return STATEMENTS.stream().filter(c -> c.phase() == phase).map(Captured::sql)
                .filter(s -> s.startsWith("select") && s.contains(" from product ")).toList();
    }

    public static List<String> productUpdates() {
        return STATEMENTS.stream().map(Captured::sql).filter(s -> s.startsWith("update product ")).toList();
    }

    /** 실행 순서를 "BODY:select, COMMIT:update, ..." 형태로 요약한다. */
    public static String sequence() {
        return STATEMENTS.stream()
                .map(c -> c.phase() + ":" + c.sql().split(" ")[0])
                .toList()
                .toString();
    }
}
