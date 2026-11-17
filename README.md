# stock-concurrency-lab

요청 집중도와 동시 사용자 수에 따른 재고 차감 동시성 제어 방식(M0 무제어 · M1 낙관적 · M2 낙관적+재시도 · M3 비관적)의 성능 비교 실험 프로젝트.

## 실행

```bash
source scripts/env.sh          # JDK 21, colima/docker/k6를 PATH에 추가
colima start                   # Docker 런타임 (최초 생성: colima start --vm-type vz --cpu 4 --memory 4 --disk 20)
docker compose up -d           # MySQL 8.0.46 → localhost:3307

./gradlew test                 # 구현 확인 테스트 (C1~C11, C13)
./gradlew correctnessExperiment   # 실험 1: 정합성 검증 → results/correctness/correctness_<시각>.csv
python3 analysis/correctness_t1.py results/correctness/correctness_<시각>.csv   # 결과표 T1
./gradlew bootJar
java -jar build/libs/stock-concurrency-lab.jar --spring.profiles.active=m2,perf
```

### 실험 2 (성능, k6)

```bash
./gradlew bootJar
python3 scripts/run_matrix.py selftest                     # C12: 조건 1개 실행 → 저장 → 검증 → CSV
python3 scripts/run_matrix.py plan --root results --name main --seed 20261118 --repeats 5
scripts/collect_env.sh                                     # 환경 정보 → results/env/
python3 scripts/run_matrix.py run --root results --plan results/plan/plan_main_20261118.csv \
    --warmup 20 --measure 60 --restart-warmup 30 --monitor --strict-env --max-external-cores 1.0
python3 analysis/aggregate.py --root results               # summary.csv, condition_stats.csv
python3 analysis/plots.py --root results                   # G1~G4 → results/figures/
```

- `load/purchase.js`: k6 폐쇄형 부하(constant-vus), 측정 구간/종료 대기 분리, k6 내부 집계
- `scripts/run_matrix.py`: 실행 계획, 서버 재기동, reset·워밍업·측정·수집, 유효성 판정 V1~V13, 재개
- `scripts/monitor.py`: 1초 간격 자원 기록, 5초 간격 외부 부하(실험 경로 밖 프로세스의 CPU)
- `--strict-env`: 전원 어댑터·저전력 모드 해제·load average·Colima 자원을 시작 전과 조건마다 검사

공통 설정: HikariCP 커넥션 풀 100 = Tomcat 최대 스레드 100 (풀이 더 작으면 기동 실패), `innodb_lock_wait_timeout=3`, REPEATABLE READ, JVM `-Xms1g -Xmx1g`, k6 `GOMAXPROCS=2`.

방식 프로파일(`m0`~`m3`)은 정확히 하나만 지정한다. 모드 프로파일: `verify`(SQL 로그), `correctness`(구매 기록 ON), `perf`(로그·구매 기록 OFF).

## API

| API                            | 용도                                                                      |
| ------------------------------ | ------------------------------------------------------------------------- |
| `POST /products/{id}/purchase` | 재고 1 차감. `{"result","productId","attempts","conflicts"}`              |
| `POST /admin/reset`            | `{"productCount":K,"initialStock":S}`로 초기화. 진행 중 요청이 있으면 409 |
| `GET /admin/result`            | 상품별 재고·버전, 서버 집계, DB 설정                                      |
