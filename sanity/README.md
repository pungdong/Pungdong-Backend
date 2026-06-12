# 풍덩 Sanity — 자격증 단체 카탈로그 + 약관 (어드민 CMS)

강사 신청 화면의 **종목별 단체 목록**과 **약관**을 관리하는 Sanity Studio 스키마.
**이 BE 레포가 소유**한다 — 스키마 계약(term `key/version/contexts`, `certOrganization.code`)을 BE 도메인(consent·instructor-application)이 정하고, BE 가 `term` 을 서버사이드로 읽기(동의 박제) 때문. 성격상 back-office/어드민이라 FE 가 아니라 여기에 둔다. (Studio 는 Sanity 클라우드로 **독립 배포**되고, BE Gradle 빌드는 이 폴더를 건드리지 않는다.)

콘텐츠 데이터는 Sanity 클라우드에 산다. BE DB 는 `certificates[].organizationCode` **문자열만** 받음(soft ref, FK 아님 — 그 값 = 여기 `certOrganization.code`). FE 는 런타임에 projectId + GROQ 로 **직접 읽기**만 한다(아래 "FE 연동").

## 구성

```
sanity/
├─ schemas/
│  ├─ certOrganization.ts   # 자격증 발급 단체 (종목별, 다중 종목 가능)
│  ├─ term.ts               # 약관/동의 (앱 전체, contexts 로 화면 스코프)
│  └─ index.ts              # schemaTypes export
├─ queries.ts               # GROQ 단일 출처 (orgsByDiscipline, termsByContext) — FE 가 복사
├─ sanity.config.ts         # Studio 설정 (projectId 채우기)
├─ sanity.cli.ts            # deploy 용 (projectId 채우기)
└─ package.json             # @pungdong/sanity (sanity Studio deps)
```

## 셋업 (연동만 하면 딱 되게)

1. **Sanity 프로젝트 생성** — https://sanity.io 가입 → 새 프로젝트 → **projectId** 확보, dataset = `production`.
2. **projectId** — `sanity.config.ts` + `sanity.cli.ts` 에 `rc448mwo` 로 이미 박혀 있음.
3. **설치 & 실행** — BE 레포(Gradle/Java)와 독립된 Node 폴더. JS 산출물(`node_modules`)은 gitignore, 소스 + `pnpm-lock.yaml` 은 커밋:
   ```bash
   cd sanity
   pnpm install        # sanity/node_modules 에 설치 (Gradle 빌드와 무관)
   pnpm dev            # http://localhost:3333 Studio
   pnpm deploy         # <project>.sanity.studio 로 배포 (운영이 여기서 콘텐츠 입력)
   ```
   > **독립 배포**: Studio 배포(`pnpm deploy` → `*.sanity.studio`)는 BE/FE 앱 배포와 **완전 별개**. BE 의 `./gradlew build` 는 이 폴더를 안 건드리고, FE 도 런타임에 `@sanity/client` 로 읽기만 한다.
4. **데이터 입력** (Studio 에서):
   - 단체(certOrganization): `name`(굵은 표시명, 예 PADI) + `fullName`(부제 정식명칭, 예 Professional Association of Diving Instructors) + `code`(BE 전송값) + `disciplines`(예 AIDA→FREEDIVING,SCUBA / PADI→SCUBA,FREEDIVING). SSI/NAUI/CMAS/MOLCHANOVS/기타(`code: OTHER`)
   - 약관(term): 예) 개인정보 수집·이용(`key: privacy_collect`), 고유식별정보 CI/DI(`unique_id_ci_di`), 서비스/통신사 약관(`service_terms`).
     각 약관의 **`contexts`** 에 노출 화면 선택(본인확인/강사신청/회원가입/결제 — 여러 개 가능), `required`/`summary`/`body`/`version` 작성.

## FE 연동 (apps/web · mobile)

Studio 코드(이 폴더)는 FE 에 **필요 없음**. FE 는 ① `@sanity/client` 설치, ② **projectId + GROQ 문자열만 복사**(아래 [`queries.ts`](queries.ts) 의 `orgsByDiscipline`/`termsByContext` 를 그대로 — `types.ts` 복사하는 방식과 동일)하면 됨:
```bash
pnpm --filter web add @sanity/client @portabletext/react
```
```ts
import {createClient} from '@sanity/client'
// queries.ts 의 GROQ 문자열을 FE 로 복사 (이 레포가 단일 출처)
import {orgsByDiscipline, termsByContext} from './sanity-queries'

const sanity = createClient({
  projectId: 'rc448mwo',
  dataset: 'production',
  apiVersion: '2024-01-01',
  useCdn: true,        // 읽기 전용 + public dataset 이면 true
})

// 종목 고르면 그 종목 단체 드롭다운
const orgs = await sanity.fetch(orgsByDiscipline, {disciplineCode: 'FREEDIVING'})
// → [{code:'AIDA', name:'AIDA'}, {code:'SSI', name:'SSI'}, ...]  → 사용자가 고른 code 를
//    BE 제출 시 certificates[].organizationCode 로 전송

// 약관 — 화면 컨텍스트로 필터
const terms = await sanity.fetch(termsByContext, {context: 'instructor_application'})
// terms[].body 는 Portable Text → <PortableText value={t.body}/>
```
- dataset 을 **public** 으로 두면 토큰 불필요(읽기). private 면 read token 발급해 server-side 에서.

## 주의 — 코드값 고정

`certOrganization.code` 는 **BE 로 전송되는 값**이라, 한 번 정하면 바꾸지 말 것 (제출된 자격증이 이 코드를 가리킴). 종목 코드(`disciplines`)는 BE `discipline.code`(FREEDIVING/SCUBA…)와 1:1.

## 관련

- 동의/약관 피처: [`../docs/features/consent-and-terms.md`](../docs/features/consent-and-terms.md)
- 강사 온보딩 피처: [`../docs/features/instructor-onboarding.md`](../docs/features/instructor-onboarding.md)
- BE 계약(TS): [`../docs/api-clients/types.ts`](../docs/api-clients/types.ts)
