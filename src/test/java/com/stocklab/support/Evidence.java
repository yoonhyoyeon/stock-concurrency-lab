package com.stocklab.support;

/** 주간 보고서에 옮길 관찰값을 표준 출력에 남긴다. Gradle 테스트 리포트(XML)의 system-out에서 추출한다. */
public final class Evidence {

    private Evidence() {
    }

    public static void log(String checkId, String message) {
        System.out.println("EVIDENCE|" + checkId + "|" + message);
    }
}
