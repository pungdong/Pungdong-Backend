# infra/ — Pungdong AWS 인프라 (Terraform)

Phase 4 배포 인프라. **dev 는 여기 없음**(로컬 `docker compose`). 이 디렉토리는 **staging / production** 만 다룬다.

> 결정 히스토리·근거는 memory `phase_4_deployment_decisions`. 이 문서는 *구조와 사용법*.

## 환경 모델

| 환경 | 어디 | 가동 | DB |
|---|---|---|---|
| **dev** | 로컬 (`docker compose`) | 개발 중 | 로컬 MySQL/Redis |
| **staging** | AWS, **온디맨드** | 필요할 때만 (QA·출시 전 테스트·Toss 심사) | 전용 RDS. `destroy` 시 최종 스냅샷 보존 → 재생성 시 복원 |
| **production** | AWS, **상시** | 항상 | 전용 RDS (상시) |

staging 을 안 쓸 땐 `terraform destroy` → 과금 ~$0 (최종 스냅샷만 센트 단위). 다시 쓸 땐 `apply` → 스냅샷에서 이전 데이터 복원(인스턴스 기동 ~10분).

## 결정 (lean, 출시용)

- **ECS Fargate** (0.5 vCPU / 1GB), **rolling 배포**(무중단 근접). blue/green 은 트래픽 늘면 후속.
- **RDS MySQL** t4g.micro single-AZ (프리티어 750h/월). **ElastiCache Redis** t3.micro 1노드 (프리티어).
- **NAT 없음** — 태스크를 public subnet 에 두고 public IP 부여 (NAT gateway $32/월 회피).
- **시크릿 = SSM Parameter Store**(SecureString), 런타임 주입. 이미지엔 안 굽음.
- **공개 이미지 CDN** — 코스/프로필/리뷰 등 공개 이미지는 비공개 버킷(`plop-{env}-public`) + **CloudFront(OAC)** + 커스텀 도메인(`cdn[-staging].plop.cool`)으로 서빙(엣지 캐싱=SEO/LCP, 버킷은 비공개 유지). persistent dns 레이어(`envs/dns/cdn.tf`)가 소유 — CloudFront 가 느리고 이미지 데이터가 쌓여 env churn 과 분리. 온디맨드 리사이즈/포맷(모바일 앱)은 `/r/*` behavior → 리전 Lambda+sharp(`envs/dns/cdn-transform.tf` + `image-lambda/`, 배포 전 `build.sh`). 원본 `/{key}` 는 S3 origin 그대로(변환 fail-safe). 결정: backend `docs/features/image-storage-and-serving.md`.
- 코어 외 의도적 생략(출시 후 트래픽 보고): Multi-AZ, auto-scaling, **앱 트래픽용** CloudFront/WAF. (이미지 CDN 은 위처럼 도입.)

## 구조

```
infra/
  bootstrap/    # state 백엔드(S3+DynamoDB lock) — 1회. (CI OIDC role 은 ⑤에서 추가)
  modules/
    network/    # VPC · public subnet · IGW · 보안그룹(alb/app/data)
    data/        # RDS MySQL · ElastiCache Redis (+ subnet group)        ← 다음
    app/         # ECR · ECS cluster/service(rolling) · ALB · IAM · 로그   ← 다음
  envs/
    staging/     # 모듈 조합 + staging 변수 (desired_count 등 작게)        ← 다음
    production/  # 모듈 조합 + production 변수                              ← 다음
```

**환경별 디렉토리 + 공유 모듈** 패턴 (workspace 아님) — staging 작업이 실수로 prod 에 적용되는 사고 방지, state 도 환경별 분리.

## 사용 (env 완성 후)

```bash
cd infra/envs/staging
terraform init
terraform plan      # 무엇이 생성/변경되는지 미리보기 (과금 전)
terraform apply     # 실제 생성 (과금 시작)
terraform destroy   # staging 내림 ($0, 최종 스냅샷만 남김)
```

리전 = `ap-northeast-2` (Seoul). 첫 `apply` 는 사용자 AWS 관리자 자격증명으로 로컬 실행, 이후 배포는 GitHub Actions(OIDC, ⑤).
