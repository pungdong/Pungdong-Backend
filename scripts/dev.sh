#!/usr/bin/env bash
#
# 로컬 개발 서버 기동 / 재기동.
#
#   1. 8080 점유 프로세스 정리 (재기동 지원)
#   2. Docker 의존성(MySQL/Redis/Elasticsearch) 기동 — 이미 떠 있으면 no-op
#   3. .env.local 로드 (JWT_SECRET 등 시크릿)
#   4. JDK 17 로 bootRun (포그라운드, Ctrl+C 로 종료)
#
# 사용: ./scripts/dev.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

# 1. 기존 8080 프로세스 정리
if lsof -ti tcp:8080 >/dev/null 2>&1; then
  echo "▶ 기존 8080 프로세스 종료"
  lsof -ti tcp:8080 | xargs kill -9 2>/dev/null || true
fi

# 2. Docker 의존성 기동 (idempotent)
echo "▶ Docker 의존성 확인 (docker compose up -d)"
docker compose up -d

# 3. 시크릿 로드 (.env.local — direnv allow 여부와 무관하게 확실히)
set -a
[ -f .env.local ] && source .env.local
set +a

# 4. bootRun (JDK 17)
echo "▶ bootRun (JDK 17) — 종료: Ctrl+C"
exec env JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew bootRun
