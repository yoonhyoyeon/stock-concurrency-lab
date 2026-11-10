package com.stocklab.correctness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 결과 CSV에 한 줄씩 덧붙인다. 파일 경로는 Gradle 태스크(correctnessExperiment)가 실행 시각을 넣어 정한다.
 * 첫 줄에는 실행한 코드 버전을 주석으로 남긴다.
 */
final class CorrectnessCsv {

    private static final String OUTPUT_PROPERTY = "lab.correctness.output";

    private CorrectnessCsv() {
    }

    static synchronized void append(CorrectnessRow row) {
        Path path = Path.of(System.getProperty(OUTPUT_PROPERTY, "build/correctness/correctness_local.csv"));
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            if (Files.notExists(path)) {
                Files.writeString(path, "# code_version=" + System.getProperty("lab.code.version", "unknown")
                        + "\n" + CorrectnessRow.HEADER + "\n", StandardCharsets.UTF_8);
            }
            Files.writeString(path, row.toCsv() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
