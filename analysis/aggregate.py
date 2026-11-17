"""실험 2 결과 집계 (PROJECT_PLAN 6.1·6.2, 7장 summary.csv 규격).

사용법: python3 analysis/aggregate.py --root results            # 본 실험
        python3 analysis/aggregate.py --root results/prelim     # 예비실험

출력
- <root>/summary/summary.csv          실행 1회당 한 줄 (모든 상태 포함)
- <root>/summary/condition_stats.csv  조건(방식·VU·K)별 valid 실행의 중앙값·최솟값·최댓값

지표 정의 (6.1)
- 성공 TPS = main 구간 SUCCESS 수 / 측정 시간, 전체 응답 RPS = main 구간 완료 응답 수 / 측정 시간
- 성공률 = main SUCCESS / main 완료 응답 × 100
- 평균 시도 횟수 = main attempts 합 / main 완료 응답 수, 관측 충돌률 = main conflicts 합 / main attempts 합 × 100
- p95는 실행마다 계산한 값을 그대로 쓰고, 여러 실행의 응답을 합쳐 하나의 p95를 만들지 않는다 (6.2)
"""
import argparse
import csv
import json
import statistics
from pathlib import Path

RESULTS = ['SUCCESS', 'SOLD_OUT', 'CONFLICT', 'LOCK_TIMEOUT', 'ERROR', 'CLIENT_TIMEOUT', 'CLIENT_ERROR']
SUMMARY_FIELDS = [
    'run_id', 'status', 'invalid_reason', 'code_version', 'strategy', 'vu', 'k', 'repeat', 'order_index', 'k6_seed',
    'max_retries', 'retry_delay_ms', 'pool_size', 'warmup_s', 'measure_s', 'started_at',
    'success_tps', 'total_rps', 'success_rate',
    'lat_all_avg_ms', 'lat_all_p95_ms', 'lat_success_avg_ms', 'lat_success_p95_ms',
    'avg_attempts', 'conflict_rate',
    'n_success', 'n_conflict', 'n_sold_out', 'n_lock_timeout', 'n_error', 'n_client_timeout', 'n_client_error',
    'n_interrupted', 'drain_responses', 'server_commits', 'stock_decrement', 'stock_check_pass',
    # 자원·환경 (예비실험 점검과 측정 오염 판단용)
    'connection_timeouts', 'hikari_pending_max', 'host_cpu_busy_avg', 'host_cpu_busy_max', 'java_cpu_avg',
    'k6_cpu_avg', 'k6_cpu_max', 'vm_cpu_avg', 'forward_cpu_avg', 'external_cores_avg', 'external_cores_max',
    'mysql_cpu_avg', 'heap_used_max_mib', 'gc_pause_count',
    'gc_pause_total_ms', 'gc_pause_max_ms', 'power_source', 'low_power_mode', 'load1_start', 'thermal_warning',
    'server_restarted', 'restart_warmup_s',
]
STAT_METRICS = ['success_tps', 'total_rps', 'success_rate', 'lat_success_p95_ms', 'lat_all_p95_ms',
                'lat_success_avg_ms', 'lat_all_avg_ms', 'avg_attempts', 'conflict_rate']


def values(summary, name):
    return summary['metrics'].get(name, {}).get('values', {})


def count(summary, name):
    return values(summary, name).get('count', 0)


def rnd(x, n=2):
    return '' if x is None else round(x, n)


def run_row(index_row, run_dir):
    cond = json.loads((run_dir / 'condition.json').read_text())
    summ = json.loads((run_dir / 'k6_summary.json').read_text())
    srv = json.loads((run_dir / 'server_result.json').read_text())
    ver = json.loads((run_dir / 'verify.json').read_text())
    met = json.loads((run_dir / 'server_metrics.json').read_text()) if (run_dir / 'server_metrics.json').exists() else {}
    measure = float(cond['measure_s'])
    main = {r: count(summ, f'res_main_{r}') for r in RESULTS}
    completed = sum(main.values())
    attempts, conflicts = count(summ, 'attempts_main'), count(summ, 'conflicts_main')
    lat_all, lat_ok = values(summ, 'lat_all_main'), values(summ, 'lat_success_main')
    mon = ver.get('monitor') or {}
    gc_before = met.get('before', {}).get('jvm.gc.pause', {})
    gc_after = met.get('after', {}).get('jvm.gc.pause', {})

    def mon_stat(name, stat):
        return (mon.get(name) or {}).get(stat, '')

    return {
        'run_id': index_row['run_id'], 'status': index_row['status'], 'invalid_reason': index_row['invalid_reason'],
        'code_version': cond.get('code_version', ''), 'strategy': cond['method'].upper(),
        'vu': cond['vu'], 'k': cond['k'], 'repeat': cond['repeat'], 'order_index': cond['order_index'],
        'k6_seed': cond['k6_seed'], 'max_retries': srv['maxRetries'], 'retry_delay_ms': 5,
        'pool_size': srv['dbSettings'].get('hikari_maximum_pool_size', ''),
        'warmup_s': cond['warmup_s'], 'measure_s': cond['measure_s'], 'started_at': cond['started_at'],
        'success_tps': rnd(main['SUCCESS'] / measure), 'total_rps': rnd(completed / measure),
        'success_rate': rnd(main['SUCCESS'] / completed * 100 if completed else None),
        'lat_all_avg_ms': rnd(lat_all.get('avg')), 'lat_all_p95_ms': rnd(lat_all.get('p(95)')),
        'lat_success_avg_ms': rnd(lat_ok.get('avg')), 'lat_success_p95_ms': rnd(lat_ok.get('p(95)')),
        'avg_attempts': rnd(attempts / completed if completed else None, 3),
        'conflict_rate': rnd(conflicts / attempts * 100 if attempts else None),
        'n_success': main['SUCCESS'], 'n_conflict': main['CONFLICT'], 'n_sold_out': main['SOLD_OUT'],
        'n_lock_timeout': main['LOCK_TIMEOUT'], 'n_error': main['ERROR'], 'n_client_timeout': main['CLIENT_TIMEOUT'],
        'n_client_error': main['CLIENT_ERROR'],
        'n_interrupted': count(summ, 'req_started') - count(summ, 'req_returned'),
        'drain_responses': sum(count(summ, f'res_drain_{r}') for r in RESULTS),
        'server_commits': srv['counters']['commitSuccess'],
        'stock_decrement': ver['info']['stock_decrement'],
        'stock_check_pass': ver['checks']['V2_total_stock'] and ver['checks']['V3_product_stock'],
        'connection_timeouts': srv['counters']['connectionTimeouts'],
        'hikari_pending_max': mon_stat('hikari_pending', 'max'),
        'host_cpu_busy_avg': mon_stat('host_cpu_busy_pct', 'avg'), 'host_cpu_busy_max': mon_stat('host_cpu_busy_pct', 'max'),
        'java_cpu_avg': mon_stat('java_cpu_pct', 'avg'), 'k6_cpu_avg': mon_stat('k6_cpu_pct', 'avg'),
        'k6_cpu_max': mon_stat('k6_cpu_pct', 'max'), 'vm_cpu_avg': mon_stat('vm_cpu_pct', 'avg'),
        'forward_cpu_avg': mon_stat('forward_cpu_pct', 'avg'),
        'external_cores_avg': mon_stat('external_cores', 'avg'), 'external_cores_max': mon_stat('external_cores', 'max'),
        'mysql_cpu_avg': mon_stat('mysql_cpu_pct', 'avg'), 'heap_used_max_mib': mon_stat('heap_used_mib', 'max'),
        'gc_pause_count': rnd(gc_after.get('COUNT', 0) - gc_before.get('COUNT', 0), 0),
        'gc_pause_total_ms': rnd((gc_after.get('TOTAL_TIME', 0) - gc_before.get('TOTAL_TIME', 0)) * 1000, 1),
        'gc_pause_max_ms': rnd(gc_after.get('MAX', 0) * 1000, 1) if gc_after else '',
        'power_source': cond['env']['power_source'], 'low_power_mode': cond['env']['low_power_mode'],
        'load1_start': cond['env']['load1'],
        'thermal_warning': bool(cond['env'].get('thermal_warning') or ver.get('env_after', {}).get('thermal_warning')),
        'server_restarted': cond['server_restarted'], 'restart_warmup_s': cond.get('restart_warmup_s', 0),
    }


def condition_stats(rows):
    groups = {}
    for r in rows:
        groups.setdefault((r['strategy'], int(r['vu']), int(r['k'])), []).append(r)
    out = []
    for (strategy, vu, k), rs in sorted(groups.items()):
        valid = [r for r in rs if r['status'] == 'valid']
        row = {'strategy': strategy, 'vu': vu, 'k': k, 'n_runs': len(rs), 'n_valid': len(valid)}
        for m in STAT_METRICS:
            xs = [float(r[m]) for r in valid if r[m] != '']
            row[f'{m}_median'] = rnd(statistics.median(xs), 3) if xs else ''
            row[f'{m}_min'] = rnd(min(xs), 3) if xs else ''
            row[f'{m}_max'] = rnd(max(xs), 3) if xs else ''
        out.append(row)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default='results')
    args = ap.parse_args()
    root = Path(args.root)
    with open(root / 'run_index.csv', newline='') as f:
        index = list(csv.DictReader(f))
    rows = [run_row(r, root / 'runs' / r['run_id']) for r in index]
    out_dir = root / 'summary'
    out_dir.mkdir(exist_ok=True)
    with open(out_dir / 'summary.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=SUMMARY_FIELDS)
        w.writeheader()
        w.writerows(rows)
    stats = condition_stats(rows)
    with open(out_dir / 'condition_stats.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(stats[0].keys()))
        w.writeheader()
        w.writerows(stats)
    print(f'{len(rows)}개 실행 → {out_dir / "summary.csv"}, {len(stats)}개 조건 → {out_dir / "condition_stats.csv"}')


if __name__ == '__main__':
    main()
