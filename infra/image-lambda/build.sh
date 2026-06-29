#!/usr/bin/env bash
# 변환 Lambda 배포 패키지 빌드 — sharp 네이티브 바이너리를 Lambda 런타임(arm64 glibc)용으로 받아
# package/ 에 모은다. Terraform(archive_file)이 package/ 를 zip 한다.
#
# ⚠️ sharp 는 네이티브 모듈이라 "Lambda 가 도는 OS/아키텍처"용 바이너리가 필요하다. 로컬이 mac 이어도
#   아래 --os=linux --cpu=arm64 로 Lambda(arm64) 용 prebuilt 를 받는다. (Lambda 함수도 arm64.)
#
# 사용:  cd infra/image-lambda && ./build.sh   →   그 다음 envs/dns 에서 terraform apply
set -euo pipefail
cd "$(dirname "$0")"

rm -rf package
mkdir -p package
cp index.mjs package.json package/

# Lambda(nodejs20.x, arm64, Amazon Linux 2023=glibc) 용 의존성. sharp prebuilt 를 그 타깃으로 강제.
npm install --prefix package --omit=dev --os=linux --cpu=arm64 --libc=glibc

echo "built package/ — node_modules + index.mjs. terraform(archive_file)이 zip 함."
