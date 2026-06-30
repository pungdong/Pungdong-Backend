# Sanity 데이터 액세스 (읽기 / 쓰기) — how-to

> **목적**: "Sanity 데이터 좀 봐줘 / 고쳐줘" 류 요청이 올 때마다 반복되는 Q&A 를 한 곳에 박제.
> 스키마/배포는 [CLAUDE.md](CLAUDE.md), 셋업은 [README.md](README.md). 이 문서는 **콘텐츠(데이터) 조회·수정**.

## 매번 헷갈리는 핵심 구분

| 무엇 | 어디 | grep 되나 |
|---|---|---|
| 스키마(Studio) `schemas/*.ts` | **이 레포 `sanity/`** (git) | ✅ |
| 카탈로그 시드 `seed/*.ndjson` (자격증·위치·투어) | **이 레포 `sanity/seed/`** (git) | ✅ |
| **콘텐츠 본문** (약관/처리방침 텍스트, `term`·`legalDocument`·`siteSettings` 문서값) | **Sanity 호스티드 데이터셋** (projectId `rc448mwo`, dataset `production`) — git 아님 | ❌ |

→ **시드에 없는 콘텐츠(약관/처리방침/term)는 레포에서 grep 해도 안 나온다. 데이터셋에 있는 데이터다.** Studio UI 또는 아래 API 로 접근.

## 좌표

- projectId `rc448mwo` · dataset `production` · apiVersion `v2024-01-01`
- Query(읽기): `GET https://rc448mwo.api.sanity.io/v2024-01-01/data/query/production?query=<GROQ>`
- Mutate(쓰기): `POST https://rc448mwo.api.sanity.io/v2024-01-01/data/mutate/production`

## 토큰 / 권한 (여기서 막히는 게 단골)

- env **`SANITY_TOKEN` = Viewer read-only** (BE `legal` 프록시용 — [.env.example](../.env.example) L83). **읽기만** 된다. 쓰기 시도하면 `transaction failed: Insufficient permissions; permission "update" required`.
- 쓰기(mutate)에는 **Editor 권한**이 필요. 두 경로:
  - **(A) 개인 CLI 로그인 사용 — ad-hoc, 추가 토큰 불필요 (이번에 쓴 방법).** `~/.config/sanity/config.json` 에 로그인돼 있으면(`sanity login`) 그 계정 권한으로:
    ```bash
    cd sanity
    PATH="$HOME/.nvm/versions/node/v22.22.2/bin:$PATH" \
      node_modules/.bin/sanity exec <script>.mjs --with-user-token
    ```
    - ⚠️ Sanity CLI 는 **Node 22+** (메모리 `feedback_sanity_node22_manifest`). 셸 node 가 20 이면 nvm 의 22 경로를 PATH 앞에.
    - 스크립트 안에서 클라이언트: `import cli from 'sanity/cli'; const {getCliClient} = cli` → `getCliClient({apiVersion:'2024-01-01'})`. **v3.99 에서 `getCliClient` 는 default export** (named import `import {getCliClient}` 는 ESM/CJS interop 로 실패).
    - `term-size: No such file or directory` 경고는 무해(무시).
  - **(B) 전용 Editor 토큰 — 반복/스크립트/CI 용 (권장).** manage.sanity.io → API → Tokens → **Editor** 토큰 생성 → `SANITY_WRITE_TOKEN` 으로 env 에 (read-only `SANITY_TOKEN` 과 **분리 유지**). 그 뒤 curl `-H "Authorization: Bearer $SANITY_WRITE_TOKEN"` 또는 `@sanity/client`. (개인 로그인(A)은 계정에 묶이고 헤드리스에서 안 되므로, 자주 할 거면 B 로 굳히는 게 맞음.)
- 🔒 **토큰 값을 로그/터미널에 절대 echo 하지 말 것** (transcript 유출). 스크립트가 파일/env 에서 읽어 쓰게만.

## 읽기 예시 (read-only `SANITY_TOKEN`)

```bash
PROJECT=rc448mwo; DATASET=production; API=v2024-01-01
curl -s -G "https://${PROJECT}.api.sanity.io/${API}/data/query/${DATASET}" \
  -H "Authorization: Bearer ${SANITY_TOKEN}" \
  --data-urlencode 'query=*[_type=="legalDocument" && slug.current=="privacy"][0]{_id,_rev,version}'
```
- 공개 콘텐츠는 토큰 없이 CDN(`https://rc448mwo.apicdn.sanity.io/...`)으로도 읽힌다(FE 방식). 단 익명 거부 타입(legalDocument 등)은 토큰 필요.
- `pt::text(@)` 로 Portable Text 블록을 평문으로 뽑으면 구조 파악이 쉽다.

## 쓰기 예시 — Portable Text 필드 안전 패치

순서:
1. **먼저 구조 조회** — 바꿀 블록의 `_key`, 그 블록 `children[]`(span) 구조와 `marks` 확인.
2. **published vs draft** — FE 는 published(`<id>`, 예 `legalDocument.privacy`)를 읽는다. Studio 미발행 편집은 `drafts.<id>`. **published 직접 패치 = 즉시 게시.** 패치 전 `*[_id=="drafts.<id>"]` 로 draft 존재 확인(있으면 나중에 publish 시 덮어써짐 → 충돌 주의).
3. **`_key` 경로로 set** — 단일 span·마크 없으면 `body[_key=="k2o"].children[0].text` 만 교체(블록 _key/style/마크 보존). **마크·주석(annotation) 있는 블록은 children 통째 교체 금지** — span 보존하며 텍스트만.
4. **`ifRevisionId(rev)` 가드** — 직전 조회 `_rev` 를 넘겨 동시편집 보호(누가 그새 고쳤으면 실패).

스크립트(`sanity exec ... --with-user-token` 로 실행):
```js
import cli from 'sanity/cli'
const {getCliClient} = cli
const client = getCliClient({apiVersion: '2024-01-01'})
let p = client.patch('legalDocument.privacy').ifRevisionId('<rev>')
p = p.set({'body[_key=="k2o"].children[0].text': '바꿀 텍스트'})
console.log((await p.commit({visibility: 'sync'}))._rev)
```

## 약관 version 규칙 (`term` vs `legalDocument`)

- **`legalDocument`** (전문 페이지, slug terms/privacy/refund): version bump **강제 없음** → 미출시면 `1.0` 유지 OK. 표시용.
- **`term`** (화면별 동의 체크박스): schema 가 **body 변경 시 version bump 를 강제**(안 올리면 publish 차단). BE 동의기록이 `(key, version)` 으로 박제하기 때문. 의미 개정이면 `v1→v2`. **미출시여도 Studio 가 막으면** 본문을 동의 범위로만 유지하거나 bump.

## 히스토리

- **2026-06-30** — 처리방침(`legalDocument.privacy`) 1조 본인인증 수집 항목에 **휴대전화번호·통신사** 추가, 5조 위탁사 **다날 / (주)코리아포트원 / 토스페이먼츠** 명시. 본인확인 BE 필드(`carrier`/`foreignerType`) 추가(PR #151)와 정합. version **1.0 유지**(미출시). 방법 = (A) `sanity exec --with-user-token`.
