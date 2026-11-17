// 실험 2 부하 스크립트 (PROJECT_PLAN 5.2)
//
// 폐쇄형 부하(constant-vus): VU마다 응답을 받는 즉시 다음 구매 요청을 보낸다. 실패한 요청을 다시 보내지 않는다.
// 집계는 요청별 원시 출력 대신 k6 안의 커스텀 메트릭으로 하고, handleSummary로 JSON 하나만 저장한다
// (서버·DB·k6가 한 PC에서 돌므로 k6의 디스크·CPU 사용을 줄이기 위함).
//
// 환경 변수
//   BASE_URL     서버 주소 (기본 http://localhost:8080)
//   VU           동시 사용자 수
//   K            상품 수. 1이면 항상 1번, 2 이상이면 1..K 중 균등 무작위
//   DURATION_S   측정 구간 길이(초). 이 시간 안에 완료된 응답은 main, 그 뒤 종료 대기 중 완료된 응답은 drain
//   SEED         상품 선택 난수 시드. VU마다 SEED*1000 + VU 번호로 독립된 난수열을 쓴다
//   RUN_ID       실행 식별자 (요약 파일에 그대로 기록)
//   SUMMARY_OUT  요약 JSON 저장 경로
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const VU = Number(__ENV.VU || 1);
const K = Number(__ENV.K || 1);
const SEED = Number(__ENV.SEED || 1);
const DURATION_S = Number(__ENV.DURATION_S || 60);
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RUN_ID = __ENV.RUN_ID || 'manual';
const SUMMARY_OUT = __ENV.SUMMARY_OUT || 'k6_summary.json';

export const options = {
  scenarios: {
    measure: { executor: 'constant-vus', vus: VU, duration: `${DURATION_S}s`, gracefulStop: '30s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  noVUConnectionReuse: false,
};

const RESULTS = ['SUCCESS', 'SOLD_OUT', 'CONFLICT', 'LOCK_TIMEOUT', 'ERROR', 'CLIENT_TIMEOUT', 'CLIENT_ERROR'];
const WINDOWS = ['main', 'drain'];

// 커스텀 메트릭은 init 단계에서 선언해야 한다.
const latAllMain = new Trend('lat_all_main', true);
const latSuccessMain = new Trend('lat_success_main', true);
const resCounters = {};
for (const w of WINDOWS) {
  for (const r of RESULTS) {
    resCounters[`${w}_${r}`] = new Counter(`res_${w}_${r}`);
  }
}
const attemptsMain = new Counter('attempts_main');
const conflictsMain = new Counter('conflicts_main');
const reqStarted = new Counter('req_started');
const reqReturned = new Counter('req_returned');
const reqProduct = [];
for (let i = 1; i <= K; i++) {
  reqProduct[i] = new Counter(`req_product_${i}`);
}

// k6의 Math.random은 시드를 고정할 수 없으므로 시드 고정 PRNG를 VU마다 따로 둔다.
function mulberry32(a) {
  return function () {
    let t = (a += 0x6D2B79F5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

let rng = null;
const params = { timeout: '10s', tags: { name: 'purchase' } };

function classify(res) {
  if (res.status === 0) {
    // k6 오류 코드 1050 = 요청 timeout
    return res.error_code === 1050 ? 'CLIENT_TIMEOUT' : 'CLIENT_ERROR';
  }
  try {
    const result = res.json('result');
    if (RESULTS.indexOf(result) >= 0 && result !== 'CLIENT_TIMEOUT' && result !== 'CLIENT_ERROR') {
      return result;
    }
  } catch (e) {
    // 본문이 JSON이 아니면 클라이언트 쪽 오류로 센다.
  }
  return 'CLIENT_ERROR';
}

export default function () {
  if (rng === null) rng = mulberry32(SEED * 1000 + exec.vu.idInTest);
  const productId = K === 1 ? 1 : 1 + Math.floor(rng() * K);
  reqProduct[productId].add(1);

  reqStarted.add(1);
  const res = http.post(`${BASE}/products/${productId}/purchase`, null, params);
  reqReturned.add(1);

  const window = (Date.now() - exec.scenario.startTime <= DURATION_S * 1000) ? 'main' : 'drain';
  const result = classify(res);
  resCounters[`${window}_${result}`].add(1);

  if (window === 'main') {
    latAllMain.add(res.timings.duration);
    if (result === 'SUCCESS') latSuccessMain.add(res.timings.duration);
    if (res.status !== 0) {
      try {
        attemptsMain.add(res.json('attempts') || 0);
        conflictsMain.add(res.json('conflicts') || 0);
      } catch (e) {
        // 분류 단계에서 CLIENT_ERROR로 이미 집계됨
      }
    }
  }
}

export function handleSummary(data) {
  data.lab = { run_id: RUN_ID, vu: VU, k: K, seed: SEED, duration_s: DURATION_S, base_url: BASE };
  const m = data.metrics;
  const count = (name) => (m[name] ? m[name].values.count : 0);
  const line = `[${RUN_ID}] main SUCCESS=${count('res_main_SUCCESS')} CONFLICT=${count('res_main_CONFLICT')} `
    + `started=${count('req_started')} returned=${count('req_returned')}\n`;
  return { [SUMMARY_OUT]: JSON.stringify(data, null, 2), stdout: line };
}
