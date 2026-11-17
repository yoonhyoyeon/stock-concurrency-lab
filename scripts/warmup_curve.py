"""예비실험 보조: 서버 재기동 직후부터 시간에 따른 처리량 변화를 잰다 (JVM 워밍업 시간 결정용).

사용법: python3 scripts/warmup_curve.py <방식 m1|m2|m3> <VU> <K> <초> <출력.csv>

서버를 새로 띄우고 바로 k6 부하를 건 뒤, 2초마다 서버의 커밋 성공 수를 읽어 구간 TPS를 기록한다.
서버 집계를 읽으므로 k6 원시 출력이 필요 없다.
"""
import csv
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_matrix as rm  # noqa: E402


def main(method, vu, k, seconds, out):
    server = rm.Server('1g', rm.ROOT / 'results' / 'prelim' / 'warmup_curve' / 'logs')
    server.ensure(method)
    rm.http_json('POST', '/admin/reset', {'productCount': k, 'initialStock': rm.INITIAL_STOCK})
    env = rm.k6_env(vu, k, 1, seconds, f'curve_{method}', Path(out).with_suffix('.k6.json'))
    log = open(Path(out).with_suffix('.k6.log'), 'w')
    proc = subprocess.Popen(['k6', 'run', '-q', '--no-color', str(rm.K6_SCRIPT)], stdout=log, stderr=subprocess.STDOUT,
                            env=env, cwd=rm.ROOT)
    t0 = time.monotonic()
    prev_t, prev_n = t0, 0
    rows = []
    try:
        while proc.poll() is None:
            time.sleep(2)
            n = rm.http_json('GET', '/admin/result')['counters']['commitSuccess']
            now = time.monotonic()
            rows.append({'t_s': round(now - t0, 1), 'tps': round((n - prev_n) / (now - prev_t), 1)})
            prev_t, prev_n = now, n
    finally:
        proc.wait()
        log.close()
        server.stop()
    with open(out, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['t_s', 'tps'])
        w.writeheader()
        w.writerows(rows)
    # 10초 구간 평균
    for start in range(0, seconds, 10):
        xs = [r['tps'] for r in rows if start < r['t_s'] <= start + 10]
        if xs:
            print(f'{start:3d}~{start + 10:3d}s  TPS {sum(xs) / len(xs):8.1f}')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5])
