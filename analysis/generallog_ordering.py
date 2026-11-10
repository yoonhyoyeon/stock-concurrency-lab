"""실험 1 진단: MySQL general log로 "각 요청이 무엇을 언제 읽었는지"를 실행별로 확인한다.

사용법:
    python3 analysis/generallog_ordering.py <general.log> <같은 실행의 correctness CSV>

general log는 서버가 받은 SQL을 시각(마이크로초)과 커넥션 번호와 함께 남긴다. Connector/J는 기본적으로
클라이언트 쪽에서 값을 채워 보내므로, UPDATE 문에 실제로 저장한 재고 값과 버전 조건이 그대로 남는다.

- 실행 구분: AdminService.reset()의 "DELETE FROM purchase_log" 한 번이 실행 하나의 시작이다.
- 트랜잭션: 커넥션별로 "SET autocommit=0"부터 COMMIT/ROLLBACK까지.
- 읽은 값: 트랜잭션의 첫 product 조회가 읽은 재고 = UPDATE에 저장한 stock + 1 (M0, M1 모두 1개 차감).
  M1은 UPDATE의 "version=?" 조건이 읽은 버전이다.
- 첫 COMMIT: 그 실행에서 서버가 가장 먼저 받은 COMMIT의 시각.
- 첫 커밋 완료: 그 커넥션이 COMMIT 다음에 보낸 문장(SET autocommit=1)의 시각. 클라이언트는 COMMIT 응답을 받은 뒤에
  다음 문장을 보내므로, 이 시각에는 첫 커밋이 이미 끝나 다른 트랜잭션에 보이는 상태이다.
  이 시각 이후에 서버가 받은 조회는 반드시 49 이하를 읽는다.
"""
import csv
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime

LINE = re.compile(r'^(\d{4}-\d\d-\d\dT[\d:.]+)Z\t\s*(\d+) (\w+)\t?(.*)$')
UPD = re.compile(r"^update product set .*stock=(\d+)(?:,version=(\d+))? where id=1(?: and version=(\d+))?")


def parse(path):
    events = []
    for line in open(path, encoding='utf-8', errors='replace'):
        m = LINE.match(line.rstrip('\n'))
        if m:
            ts = datetime.fromisoformat(m.group(1)).timestamp()
            events.append((ts, int(m.group(2)), m.group(3), m.group(4).strip()))
    return events


def split_runs(events):
    runs, cur = [], None
    for e in events:
        if e[2] == 'Query' and e[3] == 'DELETE FROM purchase_log':
            cur = []
            runs.append(cur)
        elif cur is not None:
            cur.append(e)
    return runs


def is_product_select(sql):
    s = sql.lower()
    return s.startswith('select ') and (' from product pp1_0 ' in s or ' from product vp1_0 ' in s)


def analyze(run):
    open_tx, txs, after_end = {}, [], {}
    for ts, conn, cmd, sql in run:
        if cmd != 'Query':
            continue
        low = sql.lower()
        if low == 'set autocommit=0':
            open_tx[conn] = {'conn': conn, 'read_ts': None, 'stock_written': None, 'version_cond': None, 'end': None}
        elif conn in open_tx:
            tx = open_tx[conn]
            if is_product_select(sql) and tx['read_ts'] is None:
                tx['read_ts'] = ts
            elif low.startswith('update product '):
                m = UPD.match(low)
                if m:
                    tx['stock_written'] = int(m.group(1))
                    tx['version_cond'] = int(m.group(3)) if m.group(3) is not None else None
            elif low in ('commit', 'rollback'):
                tx['end'], tx['end_ts'] = low, ts
                txs.append(open_tx.pop(conn))
                after_end[conn] = tx
        elif conn in after_end:
            after_end.pop(conn)['next_ts'] = ts
    txs = [t for t in txs if t['read_ts'] is not None]          # 서비스 트랜잭션만 (관리 API 제외)
    if not txs:
        return None
    t0 = min(t['read_ts'] for t in txs)
    first = min((t for t in txs if t['end'] == 'commit'), key=lambda t: t['end_ts'])
    first_commit, first_done = first['end_ts'], first['next_ts']
    read_values = Counter(t['stock_written'] + 1 for t in txs if t['stock_written'] is not None)
    versions = Counter(t['version_cond'] for t in txs if t['version_cond'] is not None)
    reads_ms = sorted((t['read_ts'] - t0) * 1000 for t in txs)
    return {
        'tx': len(txs),
        'commits': sum(t['end'] == 'commit' for t in txs),
        'read_values': dict(sorted(read_values.items(), reverse=True)),
        'version_cond': dict(sorted(versions.items())),
        'read_span_ms': reads_ms[-1],
        'first_commit_ms': (first_commit - t0) * 1000,
        'reads_after_first_commit': sum(t['read_ts'] > first_commit for t in txs),
        'commit_ms': (first_done - first_commit) * 1000,
        'reads_after_commit_done': sum(t['read_ts'] > first_done for t in txs),
    }


def main(log_path, csv_path):
    rows = [r for r in csv.DictReader(l for l in open(csv_path) if not l.startswith('#'))]
    runs = split_runs(parse(log_path))
    if len(runs) != len(rows):
        sys.exit(f'실행 수 불일치: log {len(runs)}, csv {len(rows)}')
    print('| 실험 | 방식 | N | 반복 | A | D | 트랜잭션 | 읽은 재고 값별 건수 | 버전 조건별 건수 | 첫 조회~마지막 조회 (ms) | 첫 조회~첫 COMMIT (ms) | 첫 COMMIT 이후 조회 | 첫 커밋 처리 (ms) | 첫 커밋 완료 이후 조회 |')
    print('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    for row, run in zip(rows, runs):
        a = analyze(run)
        vals = ', '.join(f'{k}: {v}' for k, v in a['read_values'].items())
        vers = ', '.join(f'v{k}: {v}' for k, v in a['version_cond'].items()) or '-'
        print(f"| {row['experiment']} | {row['method']} | {row['n']} | {row['repeat']} | {row['A']} | {row['D']} | "
              f"{a['tx']} | {vals} | {vers} | {a['read_span_ms']:.2f} | {a['first_commit_ms']:.2f} | {a['reads_after_first_commit']} | {a['commit_ms']:.2f} | {a['reads_after_commit_done']} |")


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
