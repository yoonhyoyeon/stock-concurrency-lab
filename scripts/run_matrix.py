#!/usr/bin/env python3
"""실험 2 자동 실행 프로그램 (PROJECT_PLAN 5.3·5.4).

사용법 (먼저 `source scripts/env.sh`, `./gradlew bootJar`):
    # 실행 계획 생성: 반복마다 조건 순서를 Random(seed + 반복)으로 섞는다
    python3 scripts/run_matrix.py plan --root results --name main --seed 20261118 --repeats 5
    python3 scripts/run_matrix.py plan --root results/prelim --name prelim --seed 20261117 --repeats 1 --vus 1,16,64

    # 계획 실행 (이미 valid/review인 조건은 건너뛴다 → 중단돼도 같은 명령으로 이어서 실행)
    python3 scripts/run_matrix.py run --root results --plan results/plan/plan_main_20261118.csv \
        --warmup 20 --measure 60 --monitor --strict-env

    # C12: 조건 1개를 짧게 실행해 저장·검증·CSV 생성까지 확인
    python3 scripts/run_matrix.py selftest

조건 1개의 흐름: (방식이 바뀌면 서버 재기동) → reset(K, 10억) → 워밍업 → inFlight 0 확인 → reset
→ 본 측정 + 종료 대기 → inFlight 0 확인 → /admin/result 저장 → 유효성 판정 → run_index.csv에 한 줄 추가.
검증에 실패해도 원시 파일은 지우지 않는다.
"""
import argparse
import csv
import datetime as dt
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from monitor import Monitor  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / 'build' / 'libs' / 'stock-concurrency-lab.jar'
K6_SCRIPT = ROOT / 'load' / 'purchase.js'
K6_GOMAXPROCS = '2'          # k6가 동시에 쓰는 코어 수 상한 (Go 런타임)
BASE = 'http://localhost:8080'
INITIAL_STOCK = 1_000_000_000
METHODS, VUS, KS = ['m1', 'm2', 'm3'], [1, 2, 4, 8, 16, 32, 64], [1, 10]
MAX_ATTEMPTS = {'m1': 1, 'm2': 4, 'm3': 1}
RESULTS = ['SUCCESS', 'SOLD_OUT', 'CONFLICT', 'LOCK_TIMEOUT', 'ERROR', 'CLIENT_TIMEOUT', 'CLIENT_ERROR']
WINDOWS = ['main', 'drain']
PLAN_FIELDS = ['order_index', 'repeat', 'method', 'vu', 'k', 'k6_seed']
INDEX_FIELDS = ['run_id', 'status', 'invalid_reason', 'method', 'vu', 'k', 'repeat', 'order_index', 'k6_seed',
                'warmup_s', 'measure_s', 'heap', 'server_restarted', 'started_at', 'finished_at']
EXPECTED_COLIMA = {'cpus': '4', 'memory': '4GiB'}


# ---------------------------------------------------------------- HTTP

def http_json(method, path, body=None, timeout=10):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def actuator(name, tag=None):
    try:
        return http_json('GET', f'/actuator/metrics/{name}' + (f'?tag={tag}' if tag else ''))
    except urllib.error.HTTPError:
        return None                                  # 아직 값이 없는 지표 (예: GC가 한 번도 없을 때 jvm.gc.pause)


def metrics_snapshot():
    out = {}
    for name, tag in [('jvm.gc.pause', None), ('jvm.memory.used', 'area:heap'), ('jvm.memory.max', 'area:heap'),
                      ('hikaricp.connections.timeout', None), ('hikaricp.connections.acquire', None),
                      ('hikaricp.connections.pending', None), ('process.cpu.usage', None)]:
        m = actuator(name, tag)
        out[name] = {x['statistic']: x['value'] for x in m['measurements']} if m else {}
    return out


def wait_in_flight_zero(timeout=45):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if http_json('GET', '/admin/result')['inFlight'] == 0:
            return True
        time.sleep(0.2)
    return False


# ---------------------------------------------------------------- 환경 검사

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()


def env_snapshot():
    batt = sh('pmset -g batt')
    low = sh("pmset -g | awk '/lowpowermode/{print $2}'")
    therm = sh('pmset -g therm')
    return {
        'power_source': 'AC' if 'AC Power' in batt else 'Battery',
        'low_power_mode': int(low) if low.isdigit() else None,
        'load1': round(os.getloadavg()[0], 2),
        # 발열·전력 제한이 기록되면 "No ... has been recorded" 대신 수준 값이 나온다
        'thermal_warning': [l.strip() for l in therm.splitlines() if l.strip() and 'No ' not in l],
        'time': dt.datetime.now().isoformat(timespec='seconds'),
    }


def env_check(strict, max_load1):
    """실행 전 환경 검사. strict이면 위반 시 중단하고, 아니면 경고만 기록한다."""
    snap = env_snapshot()
    problems = []
    if snap['power_source'] != 'AC':
        problems.append('전원 어댑터가 연결되어 있지 않음')
    if snap['low_power_mode'] != 0:
        problems.append('저전력 모드가 켜져 있음 (시스템 설정 → 배터리 → 저전력 모드: 안 함)')
    if snap['load1'] > max_load1:
        problems.append(f'시작 전 load average {snap["load1"]} > {max_load1} (다른 앱 종료 필요)')
    colima = sh('colima list')
    row = colima.splitlines()[1].split() if len(colima.splitlines()) > 1 else []
    if len(row) < 5 or row[3] != EXPECTED_COLIMA['cpus'] or row[4] != EXPECTED_COLIMA['memory']:
        problems.append(f'Colima VM 자원이 {EXPECTED_COLIMA}와 다름: {row}')
    if 'healthy' not in sh("docker ps --filter name=stocklab-mysql --format '{{.Status}}'"):
        problems.append('MySQL 컨테이너가 healthy가 아님')
    if sh("lsof -ti tcp:8080 -sTCP:LISTEN"):
        problems.append('8080 포트를 이미 다른 프로세스가 사용 중 (기존 서버 종료 필요)')
    if sh("pgrep -f GradleDaemon"):
        problems.append('Gradle 데몬이 실행 중 (./gradlew --stop)')
    top = sh("ps -Ao pcpu,comm -r | sed -n '2,6p'")
    snap.update({'colima': row, 'top_processes': top.splitlines(), 'problems': problems, 'strict': strict})
    for p in problems:
        print(f'[환경] {p}', file=sys.stderr)
    if strict and problems:
        sys.exit('환경 검사 실패: 위 항목을 고친 뒤 다시 실행한다.')
    return snap


def code_version():
    return sh(f'git -C "{ROOT}" describe --always --dirty') or 'unknown'


# ---------------------------------------------------------------- 서버

class Server:
    def __init__(self, heap, log_dir):
        self.heap, self.log_dir = heap, log_dir
        self.proc, self.method, self.log = None, None, None

    def ensure(self, method):
        """방식이 바뀌었거나 서버가 없으면 재기동한다. 재기동했으면 True."""
        if self.method == method and self.proc and self.proc.poll() is None:
            return False
        self.stop()
        self.log_dir.mkdir(parents=True, exist_ok=True)
        log_path = self.log_dir / f'server_{dt.datetime.now():%Y%m%d_%H%M%S}_{method}.log'
        self.log = open(log_path, 'w')
        cmd = ['java', f'-Xms{self.heap}', f'-Xmx{self.heap}', '-jar', str(JAR),
               f'--spring.profiles.active={method},perf']
        self.proc = subprocess.Popen(cmd, stdout=self.log, stderr=subprocess.STDOUT, cwd=ROOT)
        deadline = time.time() + 90
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f'서버가 시작 중 종료됨. 로그: {log_path}')
            try:
                if http_json('GET', '/actuator/health', timeout=2).get('status') == 'UP':
                    self.method = method
                    return True
            except Exception:
                pass
            time.sleep(0.5)
        raise RuntimeError(f'서버가 90초 안에 UP이 되지 않음. 로그: {log_path}')

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
        if self.log:
            self.log.close()
        self.proc, self.method, self.log = None, None, None


# ---------------------------------------------------------------- k6

def k6_env(vu, k, seed, duration, run_id, summary_out):
    env = dict(os.environ)
    env.update({'BASE_URL': BASE, 'VU': str(vu), 'K': str(k), 'SEED': str(seed), 'DURATION_S': str(duration),
                'RUN_ID': run_id, 'SUMMARY_OUT': str(summary_out), 'K6_NO_USAGE_REPORT': 'true',
                'GOMAXPROCS': K6_GOMAXPROCS})
    return env


def start_k6(env, log_path, raw_csv=None):
    cmd = ['k6', 'run', '-q', '--no-color', str(K6_SCRIPT)]
    if raw_csv:
        cmd[3:3] = ['--out', f'csv={raw_csv}']
        env = dict(env, K6_CSV_TIME_FORMAT='unix_milli')
    log = open(log_path, 'w')
    return subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env, cwd=ROOT), log


# ---------------------------------------------------------------- 검증 (PROJECT_PLAN 5.4)

def count(summary, name):
    return summary['metrics'].get(name, {}).get('values', {}).get('count', 0)


def verify(method, summary, result, external=None, max_external=None, clock=None):
    srv = result['counters']
    k6_res = {w: {r: count(summary, f'res_{w}_{r}') for r in RESULTS} for w in WINDOWS}
    commits = {int(i): v for i, v in srv['commitsByProduct'].items()}
    decrement = {p['id']: INITIAL_STOCK - p['stock'] for p in result['products']}
    versions = {p['id']: p['version'] for p in result['products']}
    k6_success = sum(k6_res[w]['SUCCESS'] for w in WINDOWS)
    client_timeouts = sum(k6_res[w]['CLIENT_TIMEOUT'] for w in WINDOWS)

    checks = {
        'V1_no_sold_out': sum(k6_res[w]['SOLD_OUT'] for w in WINDOWS) == 0 and srv['outcomes']['SOLD_OUT'] == 0,
        'V2_total_stock': sum(decrement.values()) == srv['commitSuccess'],
        'V3_product_stock': all(decrement[i] == commits.get(i, 0) for i in decrement),
        'V4_version': (all(versions[i] == commits.get(i, 0) for i in versions) if method in ('m1', 'm2')
                       else all(v == 0 for v in versions.values())),
        'V5_max_attempts': srv['maxAttempts'] <= MAX_ATTEMPTS[method],
        'V6_no_interrupted': count(summary, 'req_started') == count(summary, 'req_returned') and result['inFlight'] == 0,
        'V9_no_connection_timeout': srv['connectionTimeouts'] == 0,
    }
    # V11 절전 없음: 측정 동안 벽시계와 단조 시계(macOS에서 절전 중 멈춤)의 경과 시간 차이가 1초 이내
    if clock is not None:
        checks['V11_no_sleep'] = abs(clock['wall_s'] - clock['mono_s']) <= 1.0
    # V12 종료 대기 응답 수: 종료 대기 중에는 VU마다 진행 중이던 요청 1건만 끝나야 한다.
    # 구간 분류(Date.now)와 k6의 측정 종료 타이머가 몇 ms 어긋나 경계 근처 응답이 몇 건 더 drain으로 갈 수 있으므로
    # 2 × VU + 2까지 허용한다. 절전처럼 측정 구간 시계가 어긋나면 수천 건이 drain으로 분류되어 여기서 걸린다.
    checks['V12_drain_within_vu'] = sum(k6_res['drain'].values()) <= 2 * summary['lab']['vu'] + 2
    # V10 외부 부하: 측정 구간 동안 실험과 무관한 프로세스가 쓴 CPU(코어 수, 5초 구간 평균의 평균)가 기준 이하
    if max_external is not None and external is not None:
        checks['V10_external_load'] = external <= max_external
    gap = srv['commitSuccess'] - k6_success
    v7 = 'pass' if gap == 0 else ('review' if 0 < gap <= client_timeouts else 'fail')
    failed = [name for name, ok in checks.items() if not ok] + (['V7_success_match'] if v7 == 'fail' else [])
    status = 'invalid' if failed else ('review' if v7 == 'review' else 'valid')
    return {
        'status': status,
        'invalid_reason': ';'.join(failed),
        'checks': checks,
        'V7_success_match': {'result': v7, 'k6_success': k6_success, 'server_commits': srv['commitSuccess']},
        'V8_counts': {'k6': {w: {r: k6_res[w][r] for r in ('LOCK_TIMEOUT', 'ERROR', 'CLIENT_TIMEOUT', 'CLIENT_ERROR')}
                             for w in WINDOWS},
                      'server_connection_timeouts': srv['connectionTimeouts']},
        'info': {'server_requests': srv['requests'], 'k6_requests_returned': count(summary, 'req_returned'),
                 'stock_decrement': sum(decrement.values())},
    }


def monitor_summary(rows):
    def col(name):
        return [float(r[name]) for r in rows if r.get(name) not in ('', None)]
    out = {}
    for name in ('host_cpu_busy_pct', 'java_cpu_pct', 'k6_cpu_pct', 'vm_cpu_pct', 'forward_cpu_pct', 'external_cores',
                 'mysql_cpu_pct',
                 'hikari_active', 'hikari_pending', 'heap_used_mib', 'load1'):
        v = col(name)
        out[name] = {'avg': round(sum(v) / len(v), 2), 'max': max(v), 'n': len(v)} if v else None
    return out


# ---------------------------------------------------------------- 실행

def read_csv(path):
    if not Path(path).exists():
        return []
    with open(path, newline='') as f:
        return list(csv.DictReader(f))


def append_csv(path, fields, row):
    new = not Path(path).exists()
    with open(path, 'a', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields)
        if new:
            w.writeheader()
        w.writerow({k: row.get(k, '') for k in fields})


class EnvChanged(Exception):
    """실행 중 전원·저전력 모드가 바뀌어 이후 실행을 멈춰야 할 때."""


def power_ok(snap):
    return snap['power_source'] == 'AC' and snap['low_power_mode'] == 0


def run_condition(p, args, server, root, raw_csv=False):
    method, vu, k, rep = p['method'], int(p['vu']), int(p['k']), int(p['repeat'])
    # 조건마다 전원을 확인한다 (예비실험 2회차에서 중간에 어댑터가 빠진 것을 시작 검사만으로는 놓쳤다)
    if args.strict_env and not power_ok(env_snapshot()):
        raise EnvChanged('전원 어댑터 연결과 저전력 모드 해제를 확인한 뒤 같은 명령으로 다시 실행한다 (이어서 실행됨)')
    seed = int(p['k6_seed'])
    run_id = f'{dt.datetime.now():%Y%m%d-%H%M%S}_{method}_vu{vu}_k{k}_r{rep}'
    run_dir = root / 'runs' / run_id
    run_dir.mkdir(parents=True)
    started = dt.datetime.now().isoformat(timespec='seconds')
    restarted = server.ensure(method)
    condition = dict(p, run_id=run_id, warmup_s=args.warmup, measure_s=args.measure, heap=args.heap,
                     initial_stock=INITIAL_STOCK, server_restarted=restarted, server_pid=server.proc.pid,
                     code_version=code_version(), env=env_snapshot(), started_at=started)
    # 서버를 재기동했으면 조건 워밍업 전에 JVM을 먼저 데운다 (예비실험: 재기동 직후 실행의 처리량이 낮게 나옴)
    condition['restart_warmup_s'] = args.restart_warmup if restarted else 0
    (run_dir / 'condition.json').write_text(json.dumps(condition, indent=2, ensure_ascii=False))
    if restarted and args.restart_warmup > 0:
        http_json('POST', '/admin/reset', {'productCount': 10, 'initialStock': INITIAL_STOCK})
        proc, log = start_k6(k6_env(16, 10, seed, args.restart_warmup, run_id + '_jvm_warmup',
                                    run_dir / 'restart_warmup_summary.json'), run_dir / 'k6_restart_warmup.log')
        proc.wait()
        log.close()
        if not wait_in_flight_zero():
            raise RuntimeError('재기동 워밍업 후 inFlight가 0이 되지 않음')

    # 워밍업 (같은 VU·K). 결과는 저장만 하고 지표에 쓰지 않는다.
    http_json('POST', '/admin/reset', {'productCount': k, 'initialStock': INITIAL_STOCK})
    proc, log = start_k6(k6_env(vu, k, seed, args.warmup, run_id + '_warmup', run_dir / 'warmup_summary.json'),
                         run_dir / 'k6_warmup.log')
    proc.wait()
    log.close()
    if not wait_in_flight_zero():
        raise RuntimeError('워밍업 후 inFlight가 0이 되지 않음')
    http_json('POST', '/admin/reset', {'productCount': k, 'initialStock': INITIAL_STOCK})

    # 본 측정 + 종료 대기
    before = metrics_snapshot()
    wall0, mono0 = time.time(), time.monotonic()
    proc, log = start_k6(k6_env(vu, k, seed, args.measure, run_id, run_dir / 'k6_summary.json'),
                         run_dir / 'k6_main.log', raw_csv=run_dir / 'raw.csv' if raw_csv else None)
    mon = Monitor(run_dir / 'monitor.csv', server.proc.pid, proc.pid).start() if args.monitor else None
    proc.wait()
    log.close()
    rows = mon.stop() if mon else []
    clock = {'wall_s': round(time.time() - wall0, 3), 'mono_s': round(time.monotonic() - mono0, 3)}
    k6_rc = proc.returncode
    if not wait_in_flight_zero():
        print(f'[{run_id}] 측정 후 inFlight가 0이 되지 않음', file=sys.stderr)
    result = http_json('GET', '/admin/result')
    after = metrics_snapshot()
    (run_dir / 'server_result.json').write_text(json.dumps(result, indent=2, ensure_ascii=False))
    (run_dir / 'server_metrics.json').write_text(json.dumps({'before': before, 'after': after}, indent=2))

    summary = json.loads((run_dir / 'k6_summary.json').read_text())
    mon_sum = monitor_summary(rows) if rows else {}
    external = (mon_sum.get('external_cores') or {}).get('avg')
    v = verify(method, summary, result, external, args.max_external_cores, clock)
    v['clock'] = clock
    v['k6_exit_code'] = k6_rc
    v['env_after'] = env_snapshot()
    # V13 전원: 실행 전후 모두 어댑터 연결, 저전력 모드 꺼짐 (strict 모드에서만 판정)
    if args.strict_env:
        v['checks']['V13_power'] = power_ok(condition['env']) and power_ok(v['env_after'])
        if not v['checks']['V13_power']:
            v['status'] = 'invalid'
            v['invalid_reason'] = ';'.join(x for x in [v['invalid_reason'], 'V13_power'] if x)
    if rows:
        v['monitor'] = mon_sum
        v['top_external_processes'] = mon.top_external()
    (run_dir / 'verify.json').write_text(json.dumps(v, indent=2, ensure_ascii=False))
    finished = dt.datetime.now().isoformat(timespec='seconds')
    append_csv(root / 'run_index.csv', INDEX_FIELDS,
               dict(p, run_id=run_id, status=v['status'], invalid_reason=v['invalid_reason'], warmup_s=args.warmup,
                    measure_s=args.measure, heap=args.heap, server_restarted=restarted,
                    started_at=started, finished_at=finished))
    print(f'[{run_id}] {v["status"]} {v["invalid_reason"]}  '
          f'main SUCCESS={count(summary, "res_main_SUCCESS")} CONFLICT={count(summary, "res_main_CONFLICT")}',
          flush=True)
    if args.strict_env and not v['checks'].get('V13_power', True):
        raise EnvChanged('측정 중 전원 상태가 바뀌었다. 어댑터를 연결한 뒤 같은 명령으로 다시 실행한다')
    return run_id, v


def cmd_plan(args):
    methods = args.methods.split(',')
    vus = [int(x) for x in args.vus.split(',')]
    ks = [int(x) for x in args.ks.split(',')]
    conditions = [(m, v, k) for m in methods for v in vus for k in ks]
    rows = []
    for rep in range(1, args.repeats + 1):
        order = conditions[:]
        random.Random(args.seed + rep).shuffle(order)
        for i, (m, v, k) in enumerate(order, 1):
            rows.append({'order_index': i, 'repeat': rep, 'method': m, 'vu': v, 'k': k,
                         'k6_seed': args.seed * 100 + rep * 1000 + i})
    out = Path(args.root) / 'plan' / f'plan_{args.name}_{args.seed}.csv'
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=PLAN_FIELDS)
        w.writeheader()
        w.writerows(rows)
    print(f'{len(rows)}개 실행 계획 → {out}')
    return out


def cmd_run(args):
    root = Path(args.root)
    root.mkdir(parents=True, exist_ok=True)
    env = env_check(args.strict_env, args.max_load1)
    env['code_version'] = code_version()
    (root / 'env_check').mkdir(exist_ok=True)
    (root / 'env_check' / f'env_check_{dt.datetime.now():%Y%m%d_%H%M%S}.json').write_text(
        json.dumps(env, indent=2, ensure_ascii=False))
    plan = sorted(read_csv(args.plan), key=lambda r: (int(r['repeat']), int(r['order_index'])))
    done = {}
    for r in read_csv(root / 'run_index.csv'):
        done.setdefault((r['repeat'], r['order_index']), []).append(r)
    todo = [p for p in plan
            if not any(x['status'] in ('valid', 'review') for x in done.get((p['repeat'], p['order_index']), []))]
    if args.limit:
        todo = todo[:args.limit]
    print(f'계획 {len(plan)}개 중 실행할 조건 {len(todo)}개', flush=True)
    # 시스템·디스플레이·디스크 절전을 모두 막는다 (-i만으로는 예비실험 뒤 절전에서 깨어난 상태를 유지하지 못했다)
    caffeinate = subprocess.Popen(['caffeinate', '-dims', '-w', str(os.getpid())])
    server = Server(args.heap, root / 'logs')
    try:
        for i, p in enumerate(todo):
            previous = [x for x in done.get((p['repeat'], p['order_index']), []) if x['status'] == 'invalid']
            run_id, v = run_condition(p, args, server, root, raw_csv=(args.raw_csv_first and i == 0))
            for x in previous:
                append_csv(root / 'rerun_log.csv', ['old_run_id', 'method', 'vu', 'k', 'repeat', 'reason', 'new_run_id'],
                           {'old_run_id': x['run_id'], 'method': p['method'], 'vu': p['vu'], 'k': p['k'],
                            'repeat': p['repeat'], 'reason': x['invalid_reason'], 'new_run_id': run_id})
    except EnvChanged as e:
        print(f'[중단] {e}', file=sys.stderr, flush=True)
    finally:
        server.stop()
        caffeinate.terminate()


def cmd_selftest(args):
    """C12: 조건 1개(M2, VU 4, K 10)를 짧게 실행 → 결과 저장 → 재고 검증 → CSV 생성까지 확인한다."""
    root = ROOT / 'results' / 'c12' / f'{dt.datetime.now():%Y%m%d_%H%M%S}'
    plan_args = argparse.Namespace(root=str(root), name='c12', seed=12, repeats=1, methods='m2', vus='4', ks='10')
    plan = cmd_plan(plan_args)
    run_args = argparse.Namespace(root=str(root), plan=str(plan), warmup=3, measure=5, heap=args.heap, monitor=True,
                                  strict_env=False, max_load1=99, limit=0, raw_csv_first=False,
                                  max_external_cores=None, restart_warmup=0)
    cmd_run(run_args)
    index = read_csv(root / 'run_index.csv')
    run_dir = root / 'runs' / index[0]['run_id']
    files = ['condition.json', 'warmup_summary.json', 'k6_summary.json', 'server_result.json', 'verify.json',
             'monitor.csv']
    missing = [f for f in files if not (run_dir / f).exists()]
    subprocess.run([sys.executable, str(ROOT / 'analysis' / 'aggregate.py'), '--root', str(root)], check=True)
    summary_ok = (root / 'summary' / 'summary.csv').exists()
    ok = len(index) == 1 and index[0]['status'] == 'valid' and not missing and summary_ok
    print(f'C12 {"통과" if ok else "실패"}: status={index[0]["status"]}, 누락 파일={missing}, summary.csv={summary_ok}')
    sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('plan')
    p.add_argument('--root', default='results')
    p.add_argument('--name', default='main')
    p.add_argument('--seed', type=int, required=True)
    p.add_argument('--repeats', type=int, default=5)
    p.add_argument('--methods', default=','.join(METHODS))
    p.add_argument('--vus', default=','.join(map(str, VUS)))
    p.add_argument('--ks', default=','.join(map(str, KS)))
    r = sub.add_parser('run')
    r.add_argument('--root', default='results')
    r.add_argument('--plan', required=True)
    r.add_argument('--warmup', type=int, default=20)
    r.add_argument('--measure', type=int, default=60)
    r.add_argument('--heap', default='1g')
    r.add_argument('--restart-warmup', type=int, default=0,
                   help='서버를 재기동한 조건에서만 조건 워밍업 전에 VU 16·K 10으로 이 시간(초)만큼 추가 워밍업')
    r.add_argument('--monitor', action='store_true')
    r.add_argument('--strict-env', action='store_true', help='환경 검사 위반 시 실행하지 않는다 (본 실험)')
    r.add_argument('--max-load1', type=float, default=3.0)
    r.add_argument('--limit', type=int, default=0)
    r.add_argument('--max-external-cores', type=float, default=None,
                   help='V10 기준: 측정 중 외부 프로세스 CPU 평균(코어 수)이 이 값을 넘으면 invalid (--monitor 필요)')
    r.add_argument('--raw-csv-first', action='store_true', help='첫 조건만 k6 원시 출력(--out csv)을 함께 저장')
    s = sub.add_parser('selftest')
    s.add_argument('--heap', default='1g')
    args = ap.parse_args()
    {'plan': cmd_plan, 'run': cmd_run, 'selftest': cmd_selftest}[args.cmd](args)


if __name__ == '__main__':
    main()
