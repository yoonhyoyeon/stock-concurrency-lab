"""실험 1 결과 CSV에서 정합성 결과표(T1)와 커넥션 풀 비교표를 Markdown으로 만든다.

사용법:
    python3 analysis/correctness_t1.py results/correctness/correctness_<시각>.csv
출력: 같은 폴더의 T1_<시각>.md (표준 라이브러리만 사용)
"""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

METHOD_NAMES = {"M0": "M0 무제어", "M1": "M1 낙관적", "M2": "M2 낙관적+재시도", "M3": "M3 비관적"}


def read_rows(path: Path):
    with path.open(encoding="utf-8") as f:
        lines = [line for line in f if not line.startswith("#")]
    return list(csv.DictReader(lines))


def code_version(path: Path) -> str:
    first = path.open(encoding="utf-8").readline().strip()
    return first.split("=", 1)[1] if first.startswith("# code_version=") else "unknown"


def summarize(values):
    """5회 결과를 '중앙값 (최솟값~최댓값)'으로 표기한다. 모두 같으면 값 하나만."""
    lo, hi = min(values), max(values)
    med = statistics.median(values)
    med_text = f"{med:g}"
    return med_text if lo == hi else f"{med_text} ({lo:g}~{hi:g})"


def group(rows, experiment):
    groups = defaultdict(list)
    for r in rows:
        if r["experiment"] == experiment:
            groups[(r["method"], int(r["n"]))].append(r)
    return dict(sorted(groups.items()))


def ints(rows, column):
    return [int(r[column]) for r in rows]


def floats(rows, column):
    return [float(r[column]) for r in rows]


def t1_table(rows):
    out = [
        "| 방식 | N | 성공 A | 실제 차감 D | 최종 재고 F | 구매 기록 L | 재고 없음 | 충돌 | 락 timeout | 갱신 손실 (A−D) | 초과 판매 | 정합성 통과 | 요청당 평균 시도 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for (method, n), rs in group(rows, "main").items():
        attempts = [int(r["total_attempts"]) / n for r in rs]
        oversell = sum(r["oversell"] == "true" for r in rs)
        passed = sum(r["pass"] == "true" for r in rs)
        out.append(
            f"| {METHOD_NAMES[method]} | {n} | {summarize(ints(rs, 'A'))} | {summarize(ints(rs, 'D'))} | "
            f"{summarize(ints(rs, 'F'))} | {summarize(ints(rs, 'L'))} | {summarize(ints(rs, 'sold_out'))} | "
            f"{summarize(ints(rs, 'conflict'))} | {summarize(ints(rs, 'lock_timeout'))} | "
            f"{summarize(ints(rs, 'lost_update'))} | {oversell}/{len(rs)}회 | {passed}/{len(rs)}회 | "
            f"{summarize([round(a, 2) for a in attempts])} |"
        )
    return "\n".join(out)


def pool_table(rows):
    main = group(rows, "main")
    pool64 = group(rows, "pool64")
    out = [
        "| 방식 (N=100) | 풀 크기 | 커넥션 대기 스레드 최대 | 커넥션 획득 최대 시간 (ms) | 전체 소요 시간 (ms) | 정합성 통과 |",
        "|---|---|---|---|---|---|",
    ]
    for (method, n), rs64 in pool64.items():
        for pool, rs in ((100, main.get((method, n), [])), (64, rs64)):
            if not rs:
                continue
            passed = sum(r["pass"] == "true" for r in rs)
            out.append(
                f"| {METHOD_NAMES[method]} | {pool} | {summarize(ints(rs, 'pending_max'))} | "
                f"{summarize(floats(rs, 'acquire_max_ms'))} | {summarize(ints(rs, 'elapsed_ms'))} | {passed}/{len(rs)}회 |"
            )
    return "\n".join(out)


def main():
    path = Path(sys.argv[1])
    rows = read_rows(path)
    main_runs = sum(r["experiment"] == "main" for r in rows)
    pool_runs = sum(r["experiment"] == "pool64" for r in rows)
    stamp = path.stem.replace("correctness_", "")
    text = f"""# 실험 1 결과표 (T1)

- 원본: `{path.name}` (코드 버전 `{code_version(path)}`)
- 본 실험 {main_runs}회 (4 방식 × N{{10, 50, 100}} × 5회, 커넥션 풀 100), 보조 실험 {pool_runs}회 (커넥션 풀 64, N=100)
- 표기: 5회 반복의 중앙값 (최솟값~최댓값). 5회가 모두 같으면 값 하나만 적는다.
- 정합성 기준: A = D = L, 0 ≤ F ≤ 50 (초기 재고 50)

## T1. 방식 × N별 정합성 결과 (커넥션 풀 100)

{t1_table(rows)}

## 보조. 커넥션 풀 크기에 따른 커넥션 대기 (N=100)

{pool_table(rows)}

- 커넥션 대기 스레드: HikariCP가 집계하는 "커넥션을 기다리는 스레드 수"를 약 0.1ms 간격으로 읽은 최댓값. 바로 커넥션을 받는 스레드도 아주 잠깐 집계되므로 0~2는 대기로 보지 않는다.
- 커넥션 획득 최대 시간: 요청이 커넥션을 받기까지 걸린 시간의 최댓값. macOS에서 HikariCP는 1ms 단위 시계를 쓰므로 해상도는 1ms이다.
"""
    out_path = path.with_name(f"T1_{stamp}.md")
    out_path.write_text(text, encoding="utf-8")
    print(out_path)


if __name__ == "__main__":
    main()
