#!/usr/bin/env bash
# 실험 환경 정보 저장 (PROJECT_PLAN 2장 "기록할 환경 정보"). 설정 동결 때와 본 실험 번들 시작 전에 실행한다.
# 사용법: source scripts/env.sh && scripts/collect_env.sh [출력 폴더, 기본 results/env]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/results/env}"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/env_$(date +%Y%m%d_%H%M%S).txt"
HEAP="${LAB_HEAP:-1g}"

mysql_q() { docker exec stocklab-mysql mysql -uroot -proot -N -e "$1" 2>/dev/null; }

{
echo "# 실험 환경 정보 ($(date '+%Y-%m-%d %H:%M:%S %z'))"
echo
echo "## 코드"
echo "code_version: $(git -C "$ROOT" describe --always --dirty 2>/dev/null || echo unknown)"
echo
echo "## 하드웨어·OS"
echo "model: $(sysctl -n hw.model)"
echo "cpu: $(sysctl -n machdep.cpu.brand_string)"
echo "cores: 성능 $(sysctl -n hw.perflevel0.physicalcpu) + 효율 $(sysctl -n hw.perflevel1.physicalcpu) = $(sysctl -n hw.ncpu)"
echo "memory_gib: $(( $(sysctl -n hw.memsize) / 1073741824 ))"
echo "os: $(sw_vers -productName) $(sw_vers -productVersion) ($(sw_vers -buildVersion))"
echo "power: $(pmset -g batt | head -1 | sed "s/Now drawing from //")"
echo "low_power_mode: $(pmset -g | awk '/lowpowermode/{print $2}')"
echo "thermal: $(pmset -g therm | tr '\n' ' ')"
echo
echo "## 런타임 버전"
echo "java: $(java -version 2>&1 | head -1)"
echo "jvm_options: -Xms$HEAP -Xmx$HEAP (G1 GC 기본값)"
echo "spring_boot: $(unzip -l "$ROOT/build/libs/stock-concurrency-lab.jar" 2>/dev/null | grep -o 'spring-boot-[0-9][0-9.]*\.jar' | head -1)"
echo "hibernate: $(unzip -l "$ROOT/build/libs/stock-concurrency-lab.jar" 2>/dev/null | grep -o 'hibernate-core-[0-9][^ ]*\.jar' | head -1)"
echo "hikaricp: $(unzip -l "$ROOT/build/libs/stock-concurrency-lab.jar" 2>/dev/null | grep -o 'HikariCP-[0-9][^ ]*\.jar' | head -1)"
echo "tomcat: $(unzip -l "$ROOT/build/libs/stock-concurrency-lab.jar" 2>/dev/null | grep -o 'tomcat-embed-core-[0-9][^ ]*\.jar' | head -1)"
echo "k6: $(k6 version 2>&1 | head -1)"
echo "k6_gomaxprocs: 2 (run_matrix.py)"
echo "python: $(python3 --version 2>&1)"
echo
echo "## Docker / Colima"
echo "colima: $(colima version 2>/dev/null | head -1)"
colima list 2>/dev/null | sed 's/^/  /'
echo "docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
echo "mysql_image: $(docker inspect stocklab-mysql --format '{{.Config.Image}}' 2>/dev/null)"
echo "mysql_image_digest: $(docker image inspect "$(docker inspect stocklab-mysql --format '{{.Image}}')" --format '{{index .RepoDigests 0}}' 2>/dev/null)"
echo
echo "## MySQL 설정"
mysql_q "SELECT VERSION();" | sed 's/^/version: /'
mysql_q "SELECT CONCAT(VARIABLE_NAME, ': ', VARIABLE_VALUE) FROM performance_schema.global_variables WHERE VARIABLE_NAME IN ('transaction_isolation','innodb_lock_wait_timeout','max_connections','innodb_flush_log_at_trx_commit','sync_binlog','log_bin','innodb_buffer_pool_size','general_log') ORDER BY VARIABLE_NAME;"
} > "$OUT"

echo "$OUT"
