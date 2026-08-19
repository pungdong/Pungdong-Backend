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
| S3 업로드(비공개·자격증 등) | `plop-staging-uploads` | `plop-prod-uploads` |
| S3 공개 이미지(코스/프로필/리뷰) | `plop-staging-public` | `plop-prod-public` |
| 이미지 CDN(CloudFront+커스텀도메인) | `cdn-staging.plop.cool` | `cdn.plop.cool` |
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

### staging 새 코드 재배포 — 단골 함정 2개 (순서 중요)

이미 떠 있는 staging 에 **새 master 코드**를 올릴 때(= 이미지 갱신):

1. **`master-latest` 가 그 빌드인지 먼저 확인하고 배포한다.** `build.yml` 은 머지 후 **비동기(~10분)** 로 `master-latest` 를 굽는다. 빌드 끝나기 전에 배포하면 **옛 이미지를 재pull** 해서 헛돈다. → `aws ecr describe-images ... imageTag=master-latest` 의 태그에 **`master-<머지sha>`** 가 같이 붙었는지 폴링으로 확인 후 배포. (`gh run watch` 는 간헐 `401 Bad credentials` 로 빌드 완료 전에 빠져나갈 수 있어 신뢰 ✗ — **AWS creds 로 ECR 폴링이 확실**.)
2. **`force-new-deployment` 로 굴려야 새 task 가 뜬다.** 서비스는 `lifecycle.ignore_changes=[task_definition]` 라 `terraform apply`(staging-up) 만으론 재시작 안 함 → `aws ecs update-service --cluster plop-staging-cluster --service plop-staging-svc --force-new-deployment`. (env 가 바뀐 경우엔 staging-up 으로 새 task def revision 만든 뒤 그 revision 으로 update-service.)

검증: `rolloutState=COMPLETED` + CloudWatch `/ecs/plop-staging` 에 `Started PungdongApplication`. **롤링이라 새 task 가 health 못 넘기면 옛 task 가 계속 서빙 = 무중단**(새 코드만 안 뜸) — 그래서 "배포했는데 안 바뀜" 이면 새 task 크래시 로그부터 본다.

### PG 스왑 / `PAYMENT_MODE` 변경 — 이미지가 enum 을 가진 뒤에만 flip (2026-08-06)

결제 PG 를 갈아끼울 때(`PAYMENT_MODE`=stub|toss|inicis 변경, 또는 새 PG 추가)의 함정: **그 값에 해당하는 `PaymentProvider` enum 이 배포된 이미지에 있어야 한다.** 없으면 `PaymentGatewayRegistry` 가 부팅 시 `PaymentProvider.valueOf(mode)` 로 터져 — **그 어댑터 하나가 아니라 앱 전체가 안 뜬다**(`IllegalStateException`). 즉 `PAYMENT_MODE` 는 **런타임 토글이 아니라 배포와 묶인 값**이다.

- **순서(스왑)**: ① 새 provider 를 추가한 이미지가 `master-latest` 로 빌드됐는지 확인(위 함정 1) → ② SSM 시크릿 선행 — `user_secret_names` 에 추가한 이름(`INICIS_HASH_KEY`·`INICIS_API_KEY` 등)이 `/plop/<env>/` 에 **실재**해야 task 가 뜬다(없으면 secret fetch 실패로 기동 실패) → ③ **이미지 + 새 `PAYMENT_MODE` 를 같은 task def revision 에** 실어 배포(staging-up 이 새 revision 생성 → 그 revision 으로 `update-service --force-new-deployment`, 위 함정 2). 이미지와 mode 가 한 revision 이라 **원자적** = 부팅 실패 창(옛 이미지+새 mode) 자체가 안 생긴다.
- **절대 금지**: `PAYMENT_MODE` 만 먼저 바꾸고 옛 이미지에 `force-new-deployment` — 옛 이미지엔 그 enum 이 없어 부팅 실패.
- **롤백도 역순**: 새 provider 를 뺀 옛 이미지로 되돌리기 전에 `PAYMENT_MODE` 를 옛 값으로 **먼저** 내린다(안 그러면 롤백 이미지가 enum 없이 새 mode 를 만나 또 부팅 실패).
- **과거 주문은 무영향**: 스왑해도 각 주문은 `PaymentOrder.provider`(박제)로 환불·승인 라우팅(`PaymentGatewayRegistry.forOrder`) — mode 는 *신규* 주문만 정한다. 그래서 옛 PG 어댑터는 코드에 남겨둬야 그 PG 로 결제된 과거 주문을 취소할 수 있다(폐기 PG 라도 미결/환불 대기 주문이 없을 때만 어댑터 삭제).
- **MID(가맹점 식별자)와 서명 키는 한 짝 — 환경별로 섞이면 결제창이 인증 전에 거절한다.** 이니시스는 `INICIS_MID`(평문 env, 공개값)와 `INICIS_HASH_KEY`/`INICIS_API_KEY`(SSM 시크릿)가 **MID 별로 발급**된다. 테스트 MID(`INIpayTest`) + 운영 MID 키를 섞으면 서명이 안 맞아 **결제창이 뜨기도 전에** `P_NEXT_URL` 로 `P_OID=null P_STATUS=01` 이 되돌아온다 — FE 는 바로 "결제에 실패했어요". **BE 로그상 "결제 준비" 는 정상**이라 BE 만 보면 원인이 안 보인다.
  - **감별법**: 콜백에 `P_OID` 가 **null** = 이니시스가 우리 주문을 인식하기 전에 거절 = **호출 파라미터/서명 문제**(짝 불일치 1순위). `P_OID` 가 있는데 `P_STATUS != "00"` = 사용자 취소/카드사 거절 등 **정상적인 인증 실패**.
  - 그래서 env 를 갈 때 **MID 와 SSM 키를 항상 같이** 본다. 두 환경의 키가 다른지는 값을 안 찍고 지문으로 비교: `aws ssm get-parameter --name /plop/<env>/INICIS_HASH_KEY --with-decryption --query Parameter.Value --output text | shasum -a 256`.
- **prod 는 스테이징 검증 뒤에만**: 운영 PG flip 의 선행조건은 **(a) 스테이징에서 실 왕복 확인**. (b) 카드사 라이브 심사는 *실 정산*의 조건이지 *flip* 의 조건이 아니다 — **MID 가 테스트(INIpayTest)면 결제창은 뜨되 자정 자동취소라 실 정산이 없다**. 즉 라이브 심사 전에도 테스트 MID 로 prod flip 은 안전하고, 심사 통과 후엔 `INICIS_MID` 한 값만 운영 MID 로 교체한다.
  - **2026-08-07 실제 사례**: 운영을 "안전한 값(toss)" 으로 두는 게 오히려 위험했다. prod 의 토스 **테스트 키에 결제위젯 variantKey 가 없어 결제창이 아예 안 떴고**(심사자 화면: "결제 위젯을 불러오지 못했어요"), 하필 그 화면이 이니시스 신규계약 심사 대상이었다 → 이니시스(테스트 MID)로 flip. **교훈: "운영에 안 건드린 옛 PG 를 남겨둔다" 는 그 옛 PG 가 운영에서 실제로 동작할 때만 안전하다** — 휴면 PG 는 검증된 적 없는 경로다.

### ECR 리텐션이 prod 가 핀한 이미지를 지운다 (2026-07-30 발견)

**증상**: prod 를 재배포하려 하자 `CannotPullContainerError: ...plop:master-4c23ed4: not found`. 태스크가 뜨고 죽기를 반복.

**원인은 두 개가 겹친 것이다.**

**(1) `countNumber` 는 빌드 수가 아니라 매니페스트 수다.** `tagStatus = any` 라 태그 없는 것도 센다. buildx 는 **단일 아키텍처**(`--platform linux/arm64`) 빌드에도 매니페스트를 3개 만든다:

| # | 종류 | 태그 | 크기 |
|---|---|---|---|
| ① | OCI image **index** | `master-<sha>`, `master-latest` | 203MB |
| ② | arm64 **이미지** 매니페스트 | (untagged) | 203MB |
| ③ | **provenance attestation** 매니페스트 | (untagged) | ~42KB |

→ `10` 은 **3.3빌드분**이다. (amd64 도 굽는 게 아니다 — 아키텍처가 늘어서 3이 된 게 아니라 index + attestation 때문이다.)

**(2) 이미지는 배포가 아니라 머지마다 쌓인다.** `build.yml` 은 `on: push: branches: [master]` — 배포(수동 `deploy.yml`)와 무관하게 **머지될 때마다** 굽는다. 그래서 소진 속도는 **머지 빈도**를 따라간다.

이 둘이 곱해지면 반직관적인 결론이 나온다 — **prod 를 오래 재배포하지 않을수록 위험하다.** prod 가 옛 태그에 고정된 채 master 만 굴러가면 그 태그가 조용히 창 밖으로 밀린다. 실제로 prod 가 핀한 `master-4c23ed4` 위로 머지가 4번(#179·#180·#181·#183 = 매니페스트 12개) 쌓이며 10 창을 넘겼다. → `countNumber = 60`(≈20빌드)으로 상향 (`infra/bootstrap/main.tf`).

**왜 조용했나 — 그리고 왜 위험했나**: 이미 떠 있는 태스크는 이미지를 다시 당기지 않으므로 **prod 는 멀쩡히 서빙 중**이었다. 하지만 그 태스크가 죽는 순간 **띄울 이미지가 없어 복구 불가**였다(circuit breaker 도 OFF라 무한 재시도). 즉 "지금 잘 돌아감"이 "복구 가능함"을 뜻하지 않는다.

**점검 습관**: prod 태스크 정의가 핀한 이미지가 ECR 에 실재하는지는 한 줄로 확인된다 —
```bash
aws ecs describe-task-definition --task-definition plop-prod --query 'taskDefinition.containerDefinitions[0].image' --output text
aws ecr describe-images --repository-name plop --image-ids imageTag=master-<sha>   # ImageNotFound 면 지뢰
```

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

### 🔴 terraform 에 적은 env 는 `apply` 없이는 **영원히** 반영되지 않는다 (2026-08-19)

분리의 대가다. **앱 배포는 env 를 옛 revision 에서 그대로 승계한다.** `production-deploy` 는 task-def
family 의 **최신 ACTIVE revision 을 베이스로 이미지만 갈아끼워** 새 revision 을 렌더하므로, terraform 이
만들지 않은 revision 이 계속 파생되며 **낡은 env 가 무한히 복사된다.**

**실제로 밟았다**: `infra/envs/production/main.tf` 에 2026-08-12 부터 `IDENTITY_VERIFICATION_MODE = "real"`
이 적혀 있었는데, **running task def 는 일주일 넘게 `stub`** 이었다. terraform state 는 rev23 에 멈춰 있고
live 는 워크플로가 찍어낸 rev24~27 이었다. 코드·시크릿(SSM)·문서 다 준비돼 있었고 **apply 한 번이 빠졌을 뿐**인데,
증상은 "prod 본인확인이 조용히 가짜로 동작" 이다 — 에러도, 배포 실패도 없다.

**그래서 env 를 바꿨으면 이 순서로 확인한다:**

1. `terraform apply -var="image_tag=master-<sha>"` — ⚠️ **`-var` 를 빼면 안 된다.** `terraform.tfvars` 의
   `image_tag` 핀은 낡아 있기 쉽고(2026-08-19 기준 `master-0745573`, 2026-06-30 값), 그대로 apply 하면
   **떠 있는 이미지가 옛 sha 로 되돌아간다**(ECR 에서 만료됐으면 `CannotPull`).
2. `aws ecs update-service --task-definition <family>:<새 revision>` — 서비스가 `ignore_changes=[task_definition]`
   라 **apply 만으로는 안 옮겨간다.** ⚠️ 여기서 `--force-new-deployment` 는 **아무 소용이 없다** —
   그건 *현재* revision 을 다시 띄울 뿐 revision 을 바꾸지 않는다.
3. 검증은 **running task 의 task def revision + 그 revision 의 env/secrets** 로. `rolloutState: COMPLETED`
   와 health UP 은 env 가 반영됐다는 증거가 **아니다**(옛 env 로도 똑같이 초록이다).

**순서 팁**: env 변경과 코드 배포가 같이 나갈 땐 **terraform apply 를 먼저** 하고 그다음 `production-deploy`
를 돌리면, 워크플로가 그 새 revision 을 베이스로 물어 **한 롤링에 끝난다**(update-service 를 손으로 할 필요가 없다).

**시크릿을 추가할 땐 SSM 이 선행**이다 — `user_secret_names` 에 이름만 넣고 `/plop/<env>/<NAME>` 이 없으면
secret fetch 실패로 **task 가 아예 안 뜬다**(§5 PG 스왑 항목과 같은 규칙).

### ⚠️ `infra/bootstrap` 은 **로컬 state** — 워크트리에서 apply 하지 말 것

`envs/staging`·`envs/production` 은 S3 backend 지만 **`bootstrap` 은 backend 가 없다**(`main.tf` 주석: "bootstrap 은 거의 안 바뀜"). 그래서 `terraform.tfstate` 가 **메인 체크아웃 디렉토리에만** 파일로 존재하고 gitignore 라 워크트리엔 따라오지 않는다.

워크트리에서 `bootstrap` 을 apply 하면 terraform 이 **state 가 비었다고 판단해 ECR 리포지토리부터 새로 만들려 든다**(2026-07-30 실제로 `Plan: 2 to add` 를 봤다 — plan 을 먼저 봐서 걸렀다). → **bootstrap 변경은 반드시 메인 체크아웃에서**, 브랜치 머지 후 `git pull` 하고 apply 한다.

부수 리스크: 이 state 는 노트북에만 있다. bootstrap 을 다시 손댈 일이 생기면 S3 backend 로 옮기는 걸 먼저 검토한다.

## 8. 비용

- **무료플랜(크레딧 $100, ~Dec 2026 또는 소진)** 기준. RDS `backup_retention_period` 는 무료플랜 제한으로 **1일**(유료 전환 후 7 상향).
- **prod 상시 ~$35-40/월**(Fargate+ALB, RDS/Redis 크레딧 내). **staging 온디맨드**라 안 쓰면 $0(destroy, 최종 스냅샷만 센트).

### RDS 엔진 라이프사이클 — 표준지원 종료는 **비용 이벤트**다 (2026-07-30, MySQL 8.0 → 8.4)

RDS 엔진의 표준지원(end of standard support)이 끝나면 인스턴스가 멈추는 게 아니라 **Extended Support 로 자동 넘어가면서 요금이 붙는다**. 그래서 EoSS 는 "언젠가 올려야 하는 숙제"가 아니라 **날짜가 박힌 청구서**로 취급한다.

| 항목 | 수치 |
|---|---|
| MySQL 8.0 RDS 표준지원 종료 | **2026-07-31** (과금 시작 8/1) |
| Extended Support 단가 (서울, Yr1-Yr2) | **$0.12 / vCPU-hour** |
| 우리 노출액 | `db.t4g.micro`(2 vCPU) × 2대 → **≈ $350/월 (≈ $11.5/일)** = 인스턴스 원가($18/월/대)의 **약 10배**, 크레딧 $100 을 **9일**에 소진 |
| 조치 | 2026-07-30 두 인스턴스 모두 **8.0.45 → 8.4** in-place 메이저 업그레이드 (`infra/modules/data/main.tf`) |

**다음에 또 밟기 쉬운 함정 4개** (이번에 실제로 확인한 것들):

1. **Extended Support 등록은 사후 해제가 불가능하다.** `EngineLifecycleSupport` 는 **생성/복원 시에만** 지정 가능한 인수로, `ModifyDBInstance` API 에 아예 필드가 없다. → 이미 뜬 인스턴스는 **메이저 업그레이드가 유일한 회피책**. Terraform 에 `engine_lifecycle_support` 를 추가하는 것도 답이 아니다 — **인스턴스 교체(=prod DB 파괴)** 를 유발한다.
2. **메이저 업그레이드 전에 대기 중인 OS 업데이트를 먼저 적용해야 한다** (AWS 문서 명시). `describe-pending-maintenance-actions` → `apply-pending-maintenance-action --opt-in-type immediate` (재부팅 수 분).
3. **구버전 스냅샷 복원 = 과금 재개.** EoSS 이후 8.0 스냅샷을 복원하면 Extended Support 마이너로 자동 승격되고 그 순간부터 청구된다. staging 의 `restore_snapshot_identifier` 사이클이 정확히 이 지뢰 — 복원 전 `describe-db-snapshots` 로 `EngineVersion` 을 확인한다.
4. **업그레이드는 Terraform 으로 한다.** 콘솔/CLI 로 직접 올리면 TF state 는 옛 버전에 남아 다음 apply 가 **다운그레이드를 시도**한다(AWS 가 거부 → 드리프트). `engine_version` + `allow_major_version_upgrade = true` 를 **같은 apply** 에 넣고, 블라스트 반경을 줄이려 `-target=module.data.aws_db_instance.this` 로 좁힌다.

**안전장치는 AWS 쪽이 이미 꽤 해준다** — 필수 precheck 이 비호환을 **다운타임 0으로** 먼저 걸러 업그레이드를 자동 취소하고(`PrePatchCompatibility.log`), `backup_retention_period > 0` 이면 업그레이드 전 스냅샷을 자동 생성하고, 시작 실패 시 옛 버전으로 자동 롤백한다(`RDS-EVENT-0188` + `upgradeFailure.log`). 우리 쪽 카나리아는 **Flyway + `hbm2ddl=validate`** — 앱이 부팅되면 스키마 정합은 통과한 것이다.

**연 1회 확인**: `aws rds describe-db-engine-versions --engine mysql --engine-version <현재> --query 'DBEngineVersions[].ValidUpgradeTarget'` 로 상위 메이저가 열렸는지, AWS Health 에 EoSS 이벤트가 떴는지 본다. 8.4 도 LTS 지만 결국 같은 날짜가 온다.

---

## 9. DB 스키마 = Flyway 마이그레이션 (2026-06-28 도입, #111)

`hbm2ddl.auto: validate` — Hibernate 는 부팅 시 **검증만**, 스키마는 **Flyway** 가 소유(`src/main/resources/db/migration/V<N>__*.sql`, 순서대로 1회씩 실행, `flyway_schema_history` 기록). 앱 부팅에 통합 → **배포 = 마이그레이션 자동 실행**. 기존 DB 는 `baseline-on-migrate` 로 V1 "이미 적용" 표시만, 빈 DB 는 V1 부터 통째 생성. 작성 규약은 [CLAUDE.md](../../CLAUDE.md) "Schema = Flyway migrations".

### 2026-06-28 prod 첫 배포 인시던트 (교훈)

prod 에 Flyway 이미지를 처음 배포하며 3가지가 연쇄로 터졌다 — **유저·FE 없는 데모 상태라 실손해 0**, 복구 후 정상.

| # | 무슨 일 | 왜 |
|---|---|---|
| ① | 새 이미지 `validate` 가 **`missing table enrollment_round`** 로 부팅 거부 | prod DB 가 다회차 재설계 *이전* 상태(여러 재설계 뒤처짐). **validate 가 깨진 채 서빙을 막은 것 = 안전장치 작동** |
| ② | forward 마이그레이션 불가 → **wipe + V1 baseline** 으로 결정 | 옛 변경들이 hbm2ddl=update 로 만들어져 **마이그레이션 히스토리가 없음**. baseline(V1)에 맞춰 새로 = 표준 Flyway 도입 절차(데이터는 버려도 되는 데모) |
| ③ | wipe 후 V1 이 **`table already exists`(1050)** 로 실패 → 실패 기록이 이후 부팅 전부 차단 | **circuit breaker OFF** 라 실패 태스크가 무한 재시도(churn) → V1 **동시 실행** → 충돌. V1 이 idempotent 아니었음(로컬은 `mysql` 직접/baseline 이라 Flyway 실행 버그를 못 봄) |

**해결**: V1 을 `CREATE TABLE IF NOT EXISTS` 로 **idempotent 화**(#121) → 빈 DB 에 새 이미지 배포 + wipe → 동시/재시도에도 안전하게 전 테이블 생성 → validate 통과. (당시 62개. V27 의 레거시 드롭 이후는 40개.)

**방지 (→ 이슈 트래킹)**:
- **마이그레이션은 항상 idempotent** (CLAUDE.md 규약 박음). 동시 실행/재시도가 흔한 분산 환경의 기본기.
- **fresh-DB + Flyway 검증** 추가 — CI 가 H2+Flyway-off 라 Flyway 실행 경로를 못 잡음([#123](https://github.com/pungdong/Pungdong-Backend/issues/123)).
- **ECS 배포 circuit breaker 켜기** — 실패 시 자동 롤백, churn 방지([#122](https://github.com/pungdong/Pungdong-Backend/issues/122)).
- **prod 를 오래 미루지 말 것** — 자주 배포하면 각 마이그레이션이 작은 forward step, 이런 큰 retrofit 안 생김.

> 운영 메모: private RDS 라 직접 접속 불가 — DB wipe/check 는 **prod VPC 안 one-off Fargate task**(mysql 이미지 + SSM 시크릿)로 했다. prod DB 는 현재 **빈 스키마**(데모 데이터 재시드 필요 시 별도).

### 2026-08-19 — "prod 를 오래 미루지 말 것" 을 미룬 결과 (마이그레이션 21개 점프)

위 방지책 마지막 줄을 지키지 못했다. prod 가 `master-a383968`(2026-08-10)에 9일간 멈춰 있는 사이 master 는
**80커밋 · 미적용 마이그레이션 21개(V14~V34)** 앞서 나갔고, 따라잡기가 "작은 forward step" 이 아니라
**한 번에 21개** 가 됐다. 결과는 무사고(`Successfully applied 21 migrations ... now at version v34`, ERROR 0건)였지만
그건 운이 아니라 **각 마이그레이션이 멱등 규약을 지켰기 때문**이고, 리스크 자체는 컸다.

**가장 위험했던 지점 — `V27`(v1 레거시 테이블 16개 DROP)의 배포 순서 게이트.** 그 파일은 스스로 요구한다:
*"코드 삭제 PR 을 먼저 완전히 롤아웃한 뒤에 이 마이그레이션을 배포한다."* ECS 롤링은 새 태스크가 Flyway 를
도는 동안 **옛 태스크가 아직 트래픽을 받기 때문**이다 — 옛 이미지에 남은 v1 코드(`DELETE /account` →
`lectureService.closeAllLecture` → `SELECT ... FROM lecture`)가 방금 사라진 테이블을 때려 **MySQL 1146 → 500**.
한 번에 배포하면 그 창이 그대로 열린다.

- **쪼개는 법**: 중간 이미지(코드 삭제는 포함, V27 은 미포함 = `master-95251f2`)를 먼저 배포해 안정화한 뒤
  최신을 배포한다. **ECR 리텐션 60 덕에 그 중간 이미지가 아직 살아 있었다**(§5 리텐션 항목의 실효).
- **이번 선택**: 실사용자가 없어 1단계 직행. 실제로 그 창에 `DELETE /account` 호출이 없어 무사고.
  **유저가 붙은 뒤엔 이 선택지가 사라진다** — 그때는 2단계가 유일한 안전한 길이다.
- ⚠️ **DROP 은 롤백 지점을 없앤다.** 이미지를 되돌려도 스키마는 안 돌아오므로 구버전 코드의 v1 경로는
  깨진 채다 = **전진 수정만 가능**. 배포 전에 "되돌릴 수 있는가" 를 이미지가 아니라 **스키마 기준**으로 물어야 한다.

---

## 10. DNS & 도메인 (plop.cool) — 2026-06-28

한 줄: 도메인은 **Squarespace 가 등록 + DNS 관리**, 그중 **API 서브도메인(`api`/`api-staging`)만 Route53 으로 위임**해 terraform 으로 자동화. GCP 는 이 도메인과 **무관**(FCM 전용).

### 어디서 관리하나 (단골 착각 주의)
- **레지스트라 + DNS 호스팅 = Squarespace**(옛 Google Domains). 네임서버가 `ns-cloud-eX.googledomains.com` 라 GCP Cloud DNS 처럼 보이지만 — 이건 **Squarespace 의 관리형 DNS** 지 우리 GCP 존이 아니다. (계정의 GCP 프로젝트 6개 전부 확인 — plop.cool 존 없음, DNS API 도 off. GCP 는 FCM 만.)
- Squarespace 는 **API/terraform provider 가 없어** 자동화 불가 → 거기 레코드는 전부 **수동**.

### 분담
| 레코드 | 어디 | 관리 |
|---|---|---|
| `api.plop.cool` → prod ALB | **Route53**(위임) | terraform `infra/envs/dns` (ALIAS) |
| `api-staging.plop.cool` → staging ALB | **Route53**(위임) | terraform `infra/envs/staging/dns.tf` (ALIAS, up/down 자동) |
| `@`·`www`·`staging` → Vercel(웹) | Squarespace | 수동 CNAME/A |
| `admin`·`admin-staging` → Vercel(어드민) | Squarespace | 수동 CNAME |
| MX(메일=Workspace)·TXT(인증) | Squarespace | 수동 |

### 왜 `api`/`api-staging` 만 Route53 인가
staging 은 on-demand(up/down)이라 **ALB DNS 가 띄울 때마다 바뀐다(churn)** → Squarespace 수동 CNAME 이면 매번 손편집. Route53 **ALIAS → ALB** 는 ALB 를 동적 추적하고, terraform(staging env)이 up/down 에 맞춰 레코드 생성/제거 → **수동질 0**. prod ALB 는 상시라 안 바뀌지만 일관성 위해 같이 위임.

### 위임 구조 (terraform, PR #126)
- `infra/envs/dns` (persistent, **수동 `terraform apply`**): Route53 존 2개 + prod ALIAS + ACM 검증 CNAME. 존은 staging churn 과 무관히 살아있어야 NS 위임이 안 깨짐.
- `infra/envs/staging/dns.tf` (ephemeral): staging ALIAS — staging up 때 생성, down 때 제거(존은 위 레이어가 소유).
- **일회성 사람 작업**: Squarespace Custom Records 에서 `api`/`api-staging` 를 `infra/envs/dns` output 의 Route53 NS(`*.awsdns-*`)로 위임(NS 레코드). 새 환경도 동일. ⚠️ "Domain Nameservers"(존 전체 NS)는 건드리지 말 것 — 서브도메인 NS 레코드만.

### 새 origin 붙일 때 = CORS 가 최대 3곳 (막히는 단골)
1. **BE** — `infra/envs/*/terraform.tfvars` 의 `cors_allowed_origins` (API 호출 허용)
2. **Sanity** — Manage → API → CORS Origins (FE 가 Sanity CDN **직접** 읽기 허용; write 면 Allow credentials 도)
3. **NCP** — 네이버 지도 쓰면 Web 서비스 URL 등록

> 공개 "물놀이 지도"의 OFFICIAL venue 는 **FE 가 Sanity CDN 직접** 읽는다(BE 안 거침, `VenueController` 주석 참고). staging 에서 핀 안 뜨면 십중팔구 위 2번(staging origin 이 Sanity CORS 에 없음).

### Sanity 데이터셋
`production` 하나를 **prod·staging 공유**(staging BE·FE 모두 prod 읽음, FE 협의 2026-06-28). 분리 안 함 — staging 테스트 글이 prod CMS 에 들어가는 건 감수. 분리하려면 staging BE 의 `SANITY_DATASET` 도 같이 바꿔야 함(BE 도 Sanity read).

---

## 후속 (미구현)

- ✅ ~~⑤ GitHub Actions~~ — **구현됨**(#90, 2026-06-25): build.yml(자동) + deploy.yml(버튼) + OIDC role. §5 참고. (남은 정리: least-privilege 권한 축소, infra-only 변경 시 build skip)
- **카나리**(CodeDeploy 블루/그린) — 트래픽/리스크 커지면.
- **Toss 결제 플로우** — `PAYMENT_MODE` 분기 + sandbox/live 키.
