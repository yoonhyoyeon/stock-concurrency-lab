# 실험 도구 환경 설정. 사용법: source scripts/env.sh
# - JDK 21: ~/Library/Java/JavaVirtualMachines/temurin-21.jdk (Temurin 21.0.13+9)
# - colima / limactl / docker / k6 / gradle: ~/.local/bin (공식 배포 바이너리)
export PATH="$HOME/.local/bin:$PATH"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
