package com.stocklab.purchase;

import java.sql.SQLException;

/** 예외의 원인 체인에서 MySQL 오류 코드를 찾아 실패 유형을 판별한다. */
public final class FailureClassifier {

    /** MySQL ER_LOCK_WAIT_TIMEOUT: innodb_lock_wait_timeout 초과 */
    public static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;

    private FailureClassifier() {
    }

    /** 원인 체인에서 처음 만나는 0이 아닌 SQLException 오류 코드. 없으면 null. */
    public static Integer sqlErrorCode(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof SQLException sql && sql.getErrorCode() != 0) {
                return sql.getErrorCode();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    public static boolean isLockWaitTimeout(Throwable throwable) {
        Integer code = sqlErrorCode(throwable);
        return code != null && code == MYSQL_LOCK_WAIT_TIMEOUT;
    }

    /** 집계용 실패 유형 이름. 예: "CannotAcquireLockException#1205" */
    public static String describe(Throwable throwable) {
        Integer code = sqlErrorCode(throwable);
        return throwable.getClass().getSimpleName() + (code == null ? "" : "#" + code);
    }
}
