# 배포 전략 (Deployment Strategy)

> **기술스택/프로세스 결정 기록** — 브랜치·CI/CD·환경 격리·피처 플래그를 *무엇을·왜* 그렇게 정했나.
> 인프라 *구조와 사용법*(Terraform 모듈/디렉토리)은 [`infra/README.md`](../../infra/README.md). 결정 히스토리(메모리)는 `phase_4_deployment_decisions`.

---

## 한 줄 요약

**브랜치는 `master` 하나(+피처브랜치), 이미지는 한 번 빌드해 환경설정만 바꿔 배포(build-once / 12-factor), 환경(dev·staging·prod)은 자원이 완전 분리된 별개 인프라.** staging 은 온디맨드(필요할 때 띄우고 안 쓰면 destroy), production 은 상시. 결정일 2026-06-26.

---

## 1. 환경 모델 — 환경은 "브랜치"가 아니라 "인프라"

| 환경 | 어디 | 가동 | 코드 출처 |
|---|---|---|---|
| **dev** | 로컬 (`docker compose` + `bootRun`) | 개발 중 | 피처브랜치(작업 중) |
| **staging** | AWS, **온디맨드** | 필요할 때(QA·심사 전 검증) → 끝나면 destroy | `master` 의 최신 이미지 |
| **production** | AWS, **상시** | 항상 | `master` 의 *검증·승격된* 이미지 |

**핵심: "staging" 은 브랜치가 아니라 환경.** 별도 `staging`/`develop` 롱리브 브랜치를 두지 않는다(아래 §3).

## 2. 환경 격리 — staging 테스트가 prod 에 영향 0

자원이 환경별 **별개 인스턴스**라, staging 에서 무엇을 하든 prod 데이터/자원에 닿지 않는다:

| 자원 | staging | production |
|---|---|---|
| RDS(데이터) | `plop-staging-mysql` | `plop-prod-mysql` |
| Redis | `plop-staging-redis` | `plop-prod-redis` |
| S3 업로드 | `plop-staging-uploads` | `plop-prod-uploads` |
| ECS/네트워크/ALB | `plop-staging-*` | `plop-prod-*` |
| 시크릿(SSM) | `/plop/staging/*` | `/plop/production/*` |
| 로그(CloudWatch) | `/ecs/plop-staging` | `/ecs/plop-prod` |

**왜 데이터가 안 섞이나** — 데이터는 *이미지에 들어있지 않다*. 이미지는 코드(jar)뿐이고, **데이터는 RDS(컨테이너 밖 별도 서비스)** 에 산다. 앱은 `SPRING_DATASOURCE_URL` 로 "어느 RDS 에 붙을지"만 받는다. 그래서 같은 이미지가 staging RDS / prod RDS 에 각각 붙고 데이터는 분리.

**공유되어 주의할 것:**
- **Sanity** — 단일 데이터셋(production)을 공유. **BE 는 읽기 전용**이라 staging 이 오염시키지는 못하나, 어드민이 Studio 에서 콘텐츠 publish 하면 **staging·prod 둘 다** 반영(전역). 라이브 전 테스트는 draft-preview 전략 참고([sanity-read-freshness.md](sanity-read-freshness.md), 메모리 `sanity_staging_preview_plan`).
- **AWS 계정/크레딧** — 같은 계정 → 둘 다 켜면 $100 무료크레딧에서 같이 차감(데이터 위험 아닌 비용). 안 쓰는 env 는 destroy.
- **외부 서비스 키** — 환경별 키 분리(juso, FCM, **Toss**). ⚠️ **staging 은 반드시 Toss sandbox 키**, prod 만 live. staging 에 live 키 = 테스트가 실결제(실돈).

## 3. 브랜치 전략 — 트렁크 기반 (GitFlow 아님)

```
feat/xxx (로컬 개발 + 테스트)  →  PR(CI 자동 테스트)  →  master 머지(squash)
```

- **롱리브 브랜치는 `master` 하나.** 작업은 짧은 피처브랜치를 떼서 PR → master.
- **`develop`/`staging` 브랜치 없음.** "성숙도"를 *브랜치*(GitFlow: develop→staging→master)가 아니라 ***환경***(staging→prod 승격)으로 표현한다.

**왜 GitFlow 아닌가 (솔로 dev):**
- 롱리브 3개 브랜치 머지 오버헤드·드리프트("staging 브랜치가 master 보다 앞섰나") 제거.
- staging 환경이 항상 정확히 `master` 를 반영 → 혼란 없음.
- prod 엔 **staging 에서 테스트한 정확히 그 이미지(바이트)** 가 올라감(브랜치 머지가 아니라 이미지 promote) → "내 PC/staging 에선 됐는데 prod 에선" 차단.

## 4. build-once / promote — 이미지 1개, 환경은 설정으로

**도커 이미지엔 환경값을 굽지 않는다(12-factor).** 빌드 = 코드 패키징뿐. 환경별 설정(DB주소·버킷·시크릿·`PAYMENT_MODE`)은 **런타임에 ECS task definition 이 env 로 주입**.

→ **같은 이미지 하나**가 staging(staging env)·prod(prod env) 둘 다로 나간다. `staging-latest`/`prod-latest` 태그는 *같은 이미지에 붙은 라벨*(승격 추적용)이지 다른 빌드가 아니다.

이미지 저장소 = **ECR**(`…/plop`, bootstrap 소속이라 staging destroy 해도 보존). 환경은 태그로 가리켜 pull.

## 5. CI/CD — 빌드는 자동, 배포는 수동/게이트

| 단계 | 자동? | 비고 |
|---|---|---|
| master 머지 → **이미지 빌드 + ECR push** | ✅ 자동 (CI) | 태그 `master-<sha>` + 이동태그. "검증된 최신 이미지 항상 대기" |
| **staging 배포** | ❌ 수동(온디맨드) | 필요할 때 `terraform apply` → 그 시점 최신 이미지로 뜸 → 테스트 → `destroy` |
| **production 승격** | ❌ **수동 게이트** | 검증된 이미지를 prod 로 promote (`workflow_dispatch`/release). 후속: 카나리(CodeDeploy 블루/그린) |

**왜 staging 자동배포 안 하나** — staging 은 온디맨드(안 쓰면 $0). "머지마다 자동배포" 는 staging 상시가동 전제라 비용 전략과 충돌. 대신 **띄울 때 최신 이미지를 받는** 구조라 자동배포 없이도 늘 최신.

**왜 prod 즉시배포(Vercel식) 안 하나** — FE(무상태·즉시롤백·프리뷰)는 머지=배포가 안전하나, BE 는 DB 마이그레이션·결제·실돈이라 한번 잘못 나가면 롤백이 어렵다. → 쿠팡식 **수동 게이트 + 카나리**.

**구현 (2026-06-25, #90):** GitHub Actions 로 실제 배선됨.
- `.github/workflows/build.yml` — master push → arm64 도커 빌드 → ECR push(`master-<sha>` + `master-latest`). docs/md/sanity 변경은 skip.
- `.github/workflows/deploy.yml` — 수동 버튼(`workflow_dispatch`), action 드롭다운: `staging-up`(terraform apply) / `staging-down`(terraform destroy) / `production-deploy`(ECS 이미지 교체 + 헬스확인).
- 인증 = OIDC (정적 키 0). bootstrap 의 provider + role `plop-github-actions`(repo 게이트, AdministratorAccess — 추후 least-privilege).

## 6. 피처 플래그 — 환경별은 env, 전역 런타임은 Sanity

| 플래그 종류 | 메커니즘 | 예 |
|---|---|---|
| **환경별로 다른 값** (staging≠prod) | **env 변수**(task def) | `PAYMENT_MODE`(prod=immediate / staging=deferred), `ADDRESS_GEOCODE_MODE`, `IDENTITY_VERIFICATION_MODE` |
| **환경 무관, 런타임 어드민 토글** | **Sanity siteSettings** | `launched`, `showSeededCourses` |

**왜 `PAYMENT_MODE` 는 env 인가** — Sanity 는 단일 데이터셋 공유라 staging/prod 가 같은 값이 됨 → 환경마다 달라야 하는 건 Sanity 로 불가. env 변수가 정답. (심사용 즉시결제=임시 → 나중에 prod 도 deferred 로 flip 시 env 만 바꿔 재배포, 코드/브랜치 안 건드림.)

## 7. Terraform ≠ 배포 파이프라인 (분리)

- **앱 배포(새 코드)** = 이미지 빌드 → ECR → `ecs update-service`(롤링). **terraform apply 안 함.** ECS 서비스에 `lifecycle { ignore_changes = [task_definition] }` 를 둬서 CI 의 task def 갱신과 terraform 이 안 싸우게 함.
- **인프라 변경(새 리소스·env 키 추가)** = **terraform apply**(가끔, 수동/게이트).

→ GitHub Actions = *앱 이미지 배포*, Terraform = *인프라*. 매 머지에 terraform 을 엮지 않는다.

## 8. 비용

- **무료플랜(크레딧 $100, ~Dec 2026 또는 소진)** 기준. RDS `backup_retention_period` 는 무료플랜 제한으로 **1일**(유료 전환 후 7 상향).
- **prod 상시 ~$35-40/월**(Fargate+ALB, RDS/Redis 크레딧 내). **staging 온디맨드**라 안 쓰면 $0(destroy, 최종 스냅샷만 센트).

---

## 후속 (미구현)

- ✅ ~~⑤ GitHub Actions~~ — **구현됨**(#90, 2026-06-25): build.yml(자동) + deploy.yml(버튼) + OIDC role. §5 참고. (남은 정리: least-privilege 권한 축소, infra-only 변경 시 build skip)
- **카나리**(CodeDeploy 블루/그린) — 트래픽/리스크 커지면.
- **Toss 결제 플로우** — `PAYMENT_MODE` 분기 + sandbox/live 키.
