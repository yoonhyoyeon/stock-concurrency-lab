"""실험 2 그래프 G1~G4와 보조 그래프 (PROJECT_PLAN 6.3).

사용법: python3 analysis/plots.py --root results            # → results/figures/
        python3 analysis/plots.py --root results/prelim     # → results/prelim/figures/

입력은 aggregate.py가 만든 <root>/summary/condition_stats.csv 하나뿐이다 (그래프 수치는 요약 CSV에서만 만든다).
모든 그래프: K=1과 K=10을 나란히 둔 작은 그림 두 개(같은 y축), x축은 VU(log2 눈금),
선은 valid 실행의 중앙값, 세로 막대는 최솟값~최댓값.
방식별 색·마커는 고정한다 (M1 파랑·원, M2 주황·사각, M3 청록·삼각). 색만으로 구분하지 않도록
마커 모양과 선 끝 이름표를 함께 쓴다.
"""
import argparse
import csv
from pathlib import Path

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402

STYLE = {  # 범주형 팔레트 1~3번 (밝은 배경 검증 통과), 방식에 고정
    'M1': {'color': '#2a78d6', 'marker': 'o', 'label': 'M1 낙관적'},
    'M2': {'color': '#eb6834', 'marker': 's', 'label': 'M2 낙관적+재시도'},
    'M3': {'color': '#1baf7a', 'marker': '^', 'label': 'M3 비관적'},
}
INK, INK2, MUTED, GRID, AXIS, SURFACE = '#0b0b0b', '#52514e', '#898781', '#e1e0d9', '#c3c2b7', '#fcfcfb'

plt.rcParams.update({
    'font.family': 'AppleGothic', 'axes.unicode_minus': False, 'font.size': 9,
    'figure.facecolor': SURFACE, 'axes.facecolor': SURFACE, 'savefig.facecolor': SURFACE,
    'axes.edgecolor': AXIS, 'axes.labelcolor': INK2, 'xtick.color': MUTED, 'ytick.color': MUTED,
    'text.color': INK, 'axes.titlecolor': INK, 'axes.spines.top': False, 'axes.spines.right': False,
    'axes.grid': True, 'grid.color': GRID, 'grid.linewidth': 0.6, 'axes.axisbelow': True,
})


def load(root):
    with open(Path(root) / 'summary' / 'condition_stats.csv', newline='') as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        r['vu'], r['k'] = int(r['vu']), int(r['k'])
    return rows


def series(rows, strategy, k, metric):
    pts = sorted((r for r in rows if r['strategy'] == strategy and r['k'] == k and r[f'{metric}_median'] != ''),
                 key=lambda r: r['vu'])
    x = [r['vu'] for r in pts]
    med = [float(r[f'{metric}_median']) for r in pts]
    lo = [m - float(r[f'{metric}_min']) for m, r in zip(med, pts)]
    hi = [float(r[f'{metric}_max']) - m for m, r in zip(med, pts)]
    return x, med, lo, hi


def panel(ax, rows, k, metric, strategies, ylabel, logy=False):
    vus = sorted({r['vu'] for r in rows})
    ends = []
    for s in strategies:
        x, med, lo, hi = series(rows, s, k, metric)
        if not x:
            continue
        st = STYLE[s]
        ax.errorbar(x, med, yerr=[lo, hi], color=st['color'], marker=st['marker'], markersize=6, linewidth=2,
                    elinewidth=1.2, capsize=3, label=st['label'], markeredgecolor=SURFACE, markeredgewidth=1)
        ends.append((med[-1], x[-1], s))
    ax._lab_ends = ends          # 레이아웃이 확정된 뒤 label_ends()가 이름표를 단다
    ax.set_xscale('log', base=2)
    ax.set_xticks(vus)
    ax.set_xticklabels([str(v) for v in vus])
    ax.set_xlim(min(vus) / 1.4, max(vus) * 2.2)
    if logy:
        ax.set_yscale('log')
    ax.set_title(f'K = {k}' + (' (한 상품 집중)' if k == 1 else f' ({k}개 상품 균등)'), fontsize=10, loc='left')
    ax.set_xlabel('동시 사용자 수 VU')
    ax.set_ylabel(ylabel)


def label_ends(fig):
    """선 끝 이름표. 글자는 본문 색으로 쓰고, 겹치지 않게 세로로 최소 12pt 간격을 둔다.
    축 범위·레이아웃이 모두 정해진 뒤에 호출해야 간격이 맞는다."""
    fig.canvas.draw()
    pt_per_px = 72 / fig.dpi
    for ax in fig.axes:
        ends = sorted(getattr(ax, '_lab_ends', []), key=lambda e: ax.transData.transform((e[1], e[0]))[1])
        last = None
        for y, x, s in ends:
            px = ax.transData.transform((x, y))[1]
            target = px if last is None or px - last >= 12 / pt_per_px else last + 12 / pt_per_px
            last = target
            ax.annotate(s, (x, y), xytext=(8, (target - px) * pt_per_px), textcoords='offset points',
                        va='center', fontsize=9, color=INK2)


def figure(rows, metric, strategies, title, ylabel, out, logy=False, note=''):
    ks = sorted({r['k'] for r in rows})
    fig, axes = plt.subplots(1, len(ks), figsize=(4.2 * len(ks), 3.4), sharey=True, squeeze=False)
    for ax, k in zip(axes[0], ks):
        panel(ax, rows, k, metric, strategies, ylabel, logy)
    for ax in axes[0][1:]:
        ax.set_ylabel('')
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='upper right', ncol=len(labels), frameon=False, fontsize=9,
               bbox_to_anchor=(1.0, 1.0))
    fig.suptitle(title, x=0.01, ha='left', fontsize=11, color=INK)
    n = max(int(r['n_valid']) for r in rows)
    fig.text(0.01, 0.005, f'선: valid 실행의 중앙값, 막대: 최솟값~최댓값 (조건당 최대 {n}회). {note}',
             fontsize=7.5, color=MUTED, ha='left', va='bottom')
    fig.tight_layout(rect=(0, 0.04, 1, 0.92))
    label_ends(fig)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    return out


def g4(rows, out):
    ks = sorted({r['k'] for r in rows})
    fig, axes = plt.subplots(2, len(ks), figsize=(4.2 * len(ks), 6.0), sharex=True, squeeze=False)
    for col, k in enumerate(ks):
        panel(axes[0][col], rows, k, 'conflict_rate', ['M1', 'M2'], '관측 충돌률 (%)')
        panel(axes[1][col], rows, k, 'avg_attempts', ['M1', 'M2'], '평균 시도 횟수')
        axes[0][col].set_xlabel('')
    for row in axes:
        lo = min(a.get_ylim()[0] for a in row)
        hi = max(a.get_ylim()[1] for a in row)
        for a in row:
            a.set_ylim(lo, hi)
    for a in axes[1]:
        a.set_title('')
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='upper right', ncol=2, frameon=False, fontsize=9, bbox_to_anchor=(1.0, 1.0))
    fig.suptitle('G4. 동시 사용자 수에 따른 관측 충돌률과 평균 시도 횟수 (M1·M2)', x=0.01, ha='left', fontsize=11)
    n = max(int(r['n_valid']) for r in rows)
    fig.text(0.01, 0.005, f'선: valid 실행의 중앙값, 막대: 최솟값~최댓값 (조건당 최대 {n}회). '
             f'M2의 시도 상한은 4회, M1은 1회.', fontsize=7.5, color=MUTED, ha='left', va='bottom')
    fig.tight_layout(rect=(0, 0.03, 1, 0.95))
    label_ends(fig)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', default='results')
    args = ap.parse_args()
    rows = load(args.root)
    out = Path(args.root) / 'figures'
    out.mkdir(exist_ok=True)
    made = [
        figure(rows, 'success_tps', ['M1', 'M2', 'M3'], 'G1. 동시 사용자 수에 따른 성공 TPS', '성공 TPS (건/초)',
               out / 'G1_success_tps.png'),
        figure(rows, 'lat_success_p95_ms', ['M1', 'M2', 'M3'], 'G2. 동시 사용자 수에 따른 성공 응답 p95',
               '성공 응답 p95 (ms, 로그 눈금)', out / 'G2_success_p95.png', logy=True),
        figure(rows, 'success_rate', ['M1', 'M2', 'M3'], 'G3. 동시 사용자 수에 따른 성공률', '성공률 (%)',
               out / 'G3_success_rate.png'),
        g4(rows, out / 'G4_conflict_attempts.png'),
        figure(rows, 'lat_all_p95_ms', ['M1', 'M2', 'M3'], '보조. 동시 사용자 수에 따른 전체 응답 p95 (실패 포함)',
               '전체 응답 p95 (ms, 로그 눈금)', out / 'S1_all_p95.png', logy=True,
               note='빠른 CONFLICT 응답이 p95를 낮추는지 G2와 비교한다.'),
    ]
    for m in made:
        print(m)


if __name__ == '__main__':
    main()
