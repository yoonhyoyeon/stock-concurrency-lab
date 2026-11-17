"""예비실험 확인 항목: k6 원시 출력(--out csv)과 커스텀 메트릭 집계가 일치하는지 교차 검증한다 (PROJECT_PLAN 5.5).

사용법: python3 analysis/crosscheck_raw.py <run 폴더>    (raw.csv와 k6_summary.json이 있는 폴더)

비교 항목
1. 집계 검증: 원시 출력에 남은 lat_all_main 표본으로 직접 계산한 건수·p95 = 요약의 lat_all_main 건수·p95
2. 독립 검증: k6 기본 지표 http_req_duration 표본(우리 스크립트의 구간 분류를 거치지 않은 값)을
   시나리오 시작 시각 기준으로 main 구간에 나눠 센 건수·p95 ≈ 요약의 lat_all_main.
   k6는 표본 시각을 응답 완료 시각으로 기록한다(예비실험에서 이 기준일 때 건수가 정확히 일치함을 확인).
   원시 출력 시각은 ms 단위이고 시작 시각을 첫 표본으로 추정하므로, 경계 근처 몇 건의 차이는 허용한다
3. 전체 요청 수: http_reqs 표본 수 = req_returned
"""
import csv
import json
import sys
from pathlib import Path


def pct(xs, p):
    """k6와 같은 방식(정렬 후 선형 보간)의 백분위수."""
    xs = sorted(xs)
    if not xs:
        return None
    i = p * (len(xs) - 1)
    lo = int(i)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (i - lo)


def main(run_dir):
    run_dir = Path(run_dir)
    summary = json.loads((run_dir / 'k6_summary.json').read_text())
    duration_ms = summary['lab']['duration_s'] * 1000
    samples = {'http_req_duration': [], 'lat_all_main': [], 'http_reqs': 0}
    t0 = None
    with open(run_dir / 'raw.csv', newline='') as f:
        for r in csv.DictReader(f):
            ts = int(r['timestamp'])
            t0 = ts if t0 is None else min(t0, ts)
            name = r['metric_name']
            if name in ('http_req_duration', 'lat_all_main'):
                samples[name].append((ts, float(r['metric_value'])))
            elif name == 'http_reqs':
                samples['http_reqs'] += 1
    s_lat = summary['metrics']['lat_all_main']['values']
    raw_lat = [v for _, v in samples['lat_all_main']]
    # 독립 검증: 표본 시각(= 응답 완료 시각) 기준으로 main 구간에 넣는다
    indep = [v for ts, v in samples['http_req_duration'] if ts - t0 <= duration_ms]
    # 참고: 표본 시각을 요청 시작 시각으로 잘못 보면(시각 + 응답 시간) 경계 근처 응답이 빠진다
    indep_alt = [v for ts, v in samples['http_req_duration'] if ts + v - t0 <= duration_ms]
    rows = [
        ('1. 집계 검증 (lat_all_main 원시 표본)', s_lat['count'], len(raw_lat), s_lat['p(95)'], pct(raw_lat, 0.95)),
        ('2. 독립 검증 (http_req_duration, 표본 시각 기준)', s_lat['count'], len(indep), s_lat['p(95)'], pct(indep, 0.95)),
        ('   참고 (표본 시각 + 응답 시간 기준)', s_lat['count'], len(indep_alt), s_lat['p(95)'], pct(indep_alt, 0.95)),
    ]
    print(f'run: {run_dir.name}')
    print('| 비교 | 요약 건수 | 원시 건수 | 차이 | 요약 p95 (ms) | 원시 p95 (ms) |')
    print('| --- | --- | --- | --- | --- | --- |')
    for name, a, b, pa, pb in rows:
        print(f'| {name} | {a} | {b} | {b - a} | {pa:.3f} | {pb:.3f} |')
    returned = summary['metrics']['req_returned']['values']['count']
    print(f'| 3. 전체 요청 수 (http_reqs vs req_returned) | {returned} | {samples["http_reqs"]} | '
          f'{samples["http_reqs"] - returned} | - | - |')


if __name__ == '__main__':
    main(sys.argv[1])
