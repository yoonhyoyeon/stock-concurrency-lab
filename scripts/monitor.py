"""실험 2 자원 모니터 (PROJECT_PLAN 5.5).

1초 간격으로 다음을 수집해 CSV로 저장한다 (CPU 100% = 코어 1개).
- 호스트 전체 CPU 사용률 (top, 전체 코어 대비 %)
- 실험 경로 프로세스의 CPU: 서버(java), k6, Colima VM(MySQL이 도는 가상 머신),
  포트 포워딩(limactl + SSH 터널. 호스트 3307 → VM의 MySQL로 가는 모든 쿼리가 지나간다)
  → ps의 누적 CPU 시간 차이로 계산
- MySQL 컨테이너 CPU·메모리 (docker stats, VM의 vCPU 기준)
- Hikari active·pending, JVM 힙 사용량 (/actuator/metrics), 1분 load average

5초 간격으로 외부 부하를 잰다.
- external_cores = 실험 경로와 모니터 자신을 뺀 모든 프로세스의 CPU 합(코어 수). 다른 앱·시스템 작업이 얼마나 섞였는지 보는 값이다.
- "호스트 전체 − 실험 프로세스"로 계산하지 않는 이유: 포트 포워딩과 커널 네트워킹 같은 실험 자신의 부하가 섞인다
  (예비실험 1차에서 확인). 커널 작업은 어느 프로세스에도 잡히지 않으므로 이 값에서 빠진다.

run_matrix.py가 측정 구간 동안 Monitor를 켜고 끈다. 단독 실행:
    python3 scripts/monitor.py <출력.csv> <서버 pid> [k6 pid]   (Ctrl+C로 종료)
"""
import csv
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

BASE = os.environ.get('BASE_URL', 'http://localhost:8080')
MYSQL_CONTAINER = 'stocklab-mysql'
ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
FIELDS = ['t_s', 'host_cpu_busy_pct', 'java_cpu_pct', 'k6_cpu_pct', 'vm_cpu_pct', 'forward_cpu_pct', 'external_cores',
          'mysql_cpu_pct', 'mysql_mem_mib', 'hikari_active', 'hikari_pending', 'heap_used_mib', 'load1']
EXTERNAL_EVERY = 5          # 초


def parse_cputime(text):
    total = 0.0
    for part in text.split(':'):
        total = total * 60 + float(part)
    return total


def cpu_seconds(pid):
    """ps의 누적 CPU 시간을 초로 바꾼다. 프로세스가 없으면 None."""
    if not pid:
        return None
    out = subprocess.run(['ps', '-o', 'time=', '-p', str(pid)], capture_output=True, text=True).stdout.strip()
    return parse_cputime(out) if out else None


def pgrep(pattern):
    out = subprocess.run(['pgrep', '-f', pattern], capture_output=True, text=True).stdout
    return [int(p) for p in out.split()]


def all_processes():
    """{pid: (누적 CPU 초, 이름)}"""
    out = subprocess.run(['ps', '-A', '-o', 'pid=,ppid=,time=,comm='], capture_output=True, text=True).stdout
    procs = {}
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) == 4:
            procs[int(parts[0])] = (int(parts[1]), parse_cputime(parts[2]), parts[3].strip())
    return procs


def metric(name, tag=None):
    url = f'{BASE}/actuator/metrics/{name}' + (f'?tag={tag}' if tag else '')
    try:
        with urllib.request.urlopen(url, timeout=1) as r:
            data = json.load(r)
        return {m['statistic']: m['value'] for m in data['measurements']}
    except Exception:
        return {}


class _Stream(threading.Thread):
    """1초마다 값을 내보내는 외부 명령(top, docker stats)을 계속 읽어 마지막 값을 보관한다."""

    def __init__(self, cmd, parse):
        super().__init__(daemon=True)
        self.cmd, self.parse, self.last, self.proc = cmd, parse, None, None

    def run(self):
        try:
            self.proc = subprocess.Popen(self.cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        except FileNotFoundError:
            return
        for line in self.proc.stdout:
            value = self.parse(ANSI.sub('', line).strip())
            if value is not None:
                self.last = value

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()


def _parse_top(line):
    m = re.search(r'CPU usage: .*?([\d.]+)% idle', line)
    return round(100 - float(m.group(1)), 1) if m else None


def _parse_docker(line):
    m = re.match(r'([\d.]+)%,([\d.]+)(KiB|MiB|GiB)', line)
    if not m:
        return None
    mem = float(m.group(2)) * {'KiB': 1 / 1024, 'MiB': 1, 'GiB': 1024}[m.group(3)]
    return float(m.group(1)), round(mem, 1)


class Monitor:
    def __init__(self, out_path, java_pid, k6_pid=None, interval=1.0):
        self.out_path, self.interval = out_path, interval
        self.pids = {'java': java_pid, 'k6': k6_pid,
                     'vm': (pgrep('com.apple.Virtualization.VirtualMachine') or [None])[0]}
        self.forward_pids = pgrep('limactl') + pgrep('_lima/colima/ssh.sock')
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._top = _Stream(['top', '-l', '0', '-s', '1', '-n', '0'], _parse_top)
        self._docker = _Stream(['docker', 'stats', '--format', '{{.CPUPerc}},{{.MemUsage}}', MYSQL_CONTAINER],
                               _parse_docker)
        self.rows = []
        self.external_by_process = {}      # 이름 → 측정 구간 전체의 CPU 초

    def start(self):
        self._top.start()
        self._docker.start()
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        self._thread.join(10)
        self._top.stop()
        self._docker.stop()
        with open(self.out_path, 'w', newline='') as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            w.writerows(self.rows)
        return self.rows

    def top_external(self, n=5):
        """측정 구간 동안 CPU를 많이 쓴 외부 프로세스 (이름, 평균 코어 수)."""
        dur = self.rows[-1]['t_s'] if self.rows else 0
        ranked = sorted(self.external_by_process.items(), key=lambda x: -x[1])[:n]
        return [(name[-60:], round(sec / dur, 3)) for name, sec in ranked] if dur else []

    def _excluded(self, procs):
        """실험 경로 + 모니터 자신(이 파이썬 프로세스와 그 자식: top, docker, ps, caffeinate)."""
        me = os.getpid()
        excluded = {p for p in self.pids.values() if p} | set(self.forward_pids) | {me}
        excluded |= {pid for pid, (ppid, _, _) in procs.items() if ppid == me}
        return excluded

    def _loop(self):
        tracked = dict(self.pids, forward=None)
        prev = {k: cpu_seconds(p) for k, p in self.pids.items()}
        prev['forward'] = sum(cpu_seconds(p) or 0 for p in self.forward_pids)
        prev_all, prev_all_t = all_processes(), time.monotonic()
        t0 = prev_t = time.monotonic()
        tick = 0
        while not self._stop.wait(self.interval):
            tick += 1
            now = time.monotonic()
            dt = now - prev_t
            cur = {k: cpu_seconds(p) for k, p in self.pids.items()}
            cur['forward'] = sum(cpu_seconds(p) or 0 for p in self.forward_pids)
            cpu = {}
            for k in tracked:
                ok = cur[k] is not None and prev[k] is not None and dt > 0
                cpu[k] = round((cur[k] - prev[k]) / dt * 100, 1) if ok else ''
            prev, prev_t = cur, now
            external = ''
            if tick % EXTERNAL_EVERY == 0:
                now_all, now_all_t = all_processes(), time.monotonic()
                excluded = self._excluded(now_all)
                used = 0.0
                for pid, (_, sec, name) in now_all.items():
                    if pid in excluded or pid not in prev_all:
                        continue
                    d = max(0.0, sec - prev_all[pid][1])
                    used += d
                    if d > 0:
                        self.external_by_process[name] = self.external_by_process.get(name, 0) + d
                external = round(used / (now_all_t - prev_all_t), 3)
                prev_all, prev_all_t = now_all, now_all_t
            docker = self._docker.last or ('', '')
            self.rows.append({
                't_s': round(now - t0, 2),
                'host_cpu_busy_pct': self._top.last if self._top.last is not None else '',
                'java_cpu_pct': cpu['java'], 'k6_cpu_pct': cpu['k6'], 'vm_cpu_pct': cpu['vm'],
                'forward_cpu_pct': cpu['forward'], 'external_cores': external,
                'mysql_cpu_pct': docker[0], 'mysql_mem_mib': docker[1],
                'hikari_active': metric('hikaricp.connections.active').get('VALUE', ''),
                'hikari_pending': metric('hikaricp.connections.pending').get('VALUE', ''),
                'heap_used_mib': round(metric('jvm.memory.used', 'area:heap').get('VALUE', 0) / 2 ** 20, 1),
                'load1': round(os.getloadavg()[0], 2),
            })


if __name__ == '__main__':
    mon = Monitor(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else None).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print(f'{len(mon.stop())} rows → {sys.argv[1]}')
        print(mon.top_external())
