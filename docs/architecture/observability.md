# 관측(Observability)·모니터링 기술스택 결정

> **기술스택 결정 기록** — 로그/에러/분석을 무엇으로 할지, 왜 Elasticsearch 가 아닌지. 구현 세부가 아니라 *결정과 근거*.

## 한 줄

검색용 ES 는 제거(규모 미달 + 이미 MySQL 이 검색 담당). 관측은 **BE 로그 = CloudWatch Logs / BE 에러 = Sentry / 웹 행동분석 = Amplitude** 로. 모두 **관리형(zero-ops)** — 솔로 dev 의 유지보수 부담을 최소화.

## 결정

| 관심사 | 선택 | 이유 |
|---|---|---|
| **BE 일반 로그** | **AWS CloudWatch Logs** (stdout 캡처) | ECS Fargate 와 함께 zero-ops. Logs Insights 로 쿼리. Phase 4 인프라에 포함 |
| **BE 에러 추적** | **Sentry** | 동일에러 자동 그룹핑·스택트레이스·릴리스 회귀추적·**알림(push)**. *에러*엔 로그스토어보다 우수. SaaS 무료 티어로 시작 |
| **웹 유저 행동분석** | **Amplitude** | 사용자 경험 보유. 퍼널/리텐션. FE 관심사 — BE/ES 무관 |
| **(나중에) 로그 탐색 UX** | **Grafana Cloud Loki** (필요 시) | Kibana 식 탐색이 그리울 때. **관리형**으로(self-host 는 솔로 dev 운영부담이라 비추). CloudWatch 가 baseline 이라 출시 필수 아님 |
| **검색(강의/코스)** | **MySQL `JpaSpecification`** | ES 제거. course 도메인은 이미 Specification(keyword LIKE + 필터)로 검색 |

## 왜 Elasticsearch 가 아닌가

- **검색용 ES**: 강의 full-text 인덱싱 위해 넣었으나 우리 규모엔 과함. **새 course 도메인은 이미 MySQL Specification 으로 검색** — ES 는 legacy lecture 에만 붙은 vestigial 이었다. **Phase 3(2026-06-24)에서 제거 완료** — 키워드 검색을 MySQL `LIKE`(`LectureSpecifications.keywordMatch`)로 치환, 검색 손실 0.
- **로그용 ES(ELK)**: *가능은* 하나 **자가호스팅 ES 가 가장 무거운 선택**(메모리 폭식·튜닝·스케일·보안 = 유지보수 부담). 솔로 dev + 이미 AWS 환경엔 CloudWatch + Sentry 가 더 가볍고 각 용도에 적합.
- ELK 는 "로그 볼륨 폭발 + 복잡한 로그검색 일상"일 때나 의미. 거기 도달하면 **관리형**(OpenSearch Serverless / Grafana Loki)을 붙인다 — 자가 ES 부활 X.

## 두 관심사 분리 (Loki vs Sentry 는 경쟁 아님)

- **Loki/CloudWatch = 모든 로그**(pull, "그 시간대 무슨 일?"). **Sentry = 예외만**(push, "터지면 알려줌" + 스택트레이스·그룹핑).
- 출시 우선순위 = **Sentry 먼저** — CloudWatch 가 로그 baseline 을 공짜로 주므로, 에러 인텔리전스(그룹핑·알림)가 더 큰 빈칸. 솔로 dev 에겐 push 형이 레버리지 큼. 기본 셋업은 SDK+DSN 으로 얕음.

## 비용 모델 (출시 규모 ≈ $0~15/mo, 무료 티어 위주)

| 도구 | 출시 예상 | 비용 레버 (주의) |
|---|---|---|
| CloudWatch Logs | ~$2~10 (서울 ingest ≈ $0.5~0.76/GB) | prod DEBUG 로깅 / 커스텀 메트릭($0.30·개) / 긴 보존 / Insights 대량 스캔 |
| Sentry | 무료 티어(~5k 에러/월)로 커버 → $0 | 에러 폭주 / tracing·replay 샘플 과다 → Team ~$26/mo |
| Amplitude | 무료 티어 커버 → $0 | 이벤트/MTU 볼륨 |

**비용 안전 셋업**: prod 로그레벨 INFO(DEBUG off)·보존 14~30일·커스텀 메트릭 절제 / Sentry Spike Protection on·`tracesSampleRate` 낮게(0~0.1)·노이즈 `beforeSend` 필터. (가격 숫자는 2026 초 근사 — 실제는 각 제공자 현재 페이지로 확정. 비용 *모델*은 불변.)

## 결정 히스토리

- **2026-06-24** — ES(검색) 제거 확정(Phase 3) + 관측 스택 = CloudWatch + Sentry(BE) / Amplitude(웹) / Loki(나중·관리형). 근거: 규모 미달 + 검색은 이미 MySQL + 솔로 dev 유지보수 최소화(관리형 우선). Loki/Sentry 는 보완재이며 Sentry 우선.

## 관련

- [docs/features/launch-and-seeded-content.md](../features/launch-and-seeded-content.md) · Phase 4 배포(메모리 `phase_4_deployment_decisions`)에서 CloudWatch/SSM/ECS 와 함께 설정.
