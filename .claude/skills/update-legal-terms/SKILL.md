---
name: update-legal-terms
description: >-
  Sanity 약관 콘텐츠(개인정보처리방침·이용약관·취소환불 = legalDocument, 또는 동의 체크박스 = term)를 안전하게
  조회·수정·게시한다. "약관 고쳐줘 / 처리방침 위탁사 바꿔줘 / 취소환불 문구 추가 / term 본문 수정 / 약관에 ~조항 넣어줘"
  류 요청에 사용. 좌표·토큰·조(條) 내부참조 함정·라이브 게시 확인·version 규칙·게시 후 검증을 한 절차로 묶는다.
  약관 본문은 레포에 없고(Sanity 호스티드) grep 안 됨 — 이 스킬로 접근.
---

# 약관 업데이트 (Sanity legalDocument / term)

약관 본문은 **git 이 아니라 Sanity 호스티드 데이터셋**에 있다(grep 안 됨). 이 스킬은 조회→편집설계→확인→게시→검증 절차.
**메커니즘 단일 출처 = [`sanity/data-access.md`](../../../sanity/data-access.md)** (좌표·토큰·Portable Text 패치·version 규칙). 여기선 그 위의 **워크플로우 + 약관 특유 주의**를 정의한다. 중복 금지 — 세부는 그 문서 참조.

## 좌표 (요약, 원본은 data-access.md)
- projectId `rc448mwo` · dataset `production` · apiVersion `v2024-01-01`
- **읽기** = env `SANITY_TOKEN`(Viewer, read-only). **쓰기** = `sanity exec --with-user-token`(CLI 로그인 필요, **Node 22+**) 또는 Editor `SANITY_WRITE_TOKEN`.
- 문서: `legalDocument`(slug `terms`/`privacy`/`refund`, published id `legalDocument.<slug>`) = 게시 전문(FE CDN 직접) · `term`(key/version/contexts) = 화면별 동의(BE 가 (key,version) 박제).

## 절차

### 1. 어느 문서인지 확정
개인정보처리방침=`privacy`, 이용약관=`terms`, 취소·환불=`refund`. 동의 체크박스면 `term`(key로).

### 2. 현재 본문 읽기 (read-only, 구조 파악)
`_key`·`style`·`text` 로 블록 구조를 뽑아 **바꿀 블록의 _key, 내부 조(條) 참조, 번호매김**을 파악. (curl + GROQ, python 파싱 — data-access.md 읽기 예시.)
```bash
PROJECT=rc448mwo; DATASET=production; API=v2024-01-01
curl -s -G "https://${PROJECT}.api.sanity.io/${API}/data/query/${DATASET}" -H "Authorization: Bearer ${SANITY_TOKEN}" \
  --data-urlencode 'query=*[_type=="legalDocument" && slug.current=="refund"][0]{_id,_rev,version,body}'
# 각 block: _key / style(normal·h2·h3·blockquote) / listItem / children[].text / children[].marks
```

### 3. 편집안 설계 (surgical, 보존 우선)
- **_key 경로로 최소 패치.** 단일 span·마크 없으면 `body[_key=="<k>"].children[0].text` 만 교체(블록 _key/style/마크 보존).
- ⚠️ **마크·주석(link 등) 있는 블록은 children 통째 교체 금지** — 텍스트 span 만.
- ⚠️⚠️ **조(條) 내부참조 함정**: 본문이 "**제N조**"를 내부 참조하면(예: refund 의 "제2조의 환불율"), **앞/중간에 섹션을 끼워 번호를 밀면 그 참조가 깨진다.** → 새 섹션은 **참조를 안 받는 위치**(문서 끝, 또는 유의사항 앞)에 삽입하고, 밀리는 섹션이 어디서도 번호 참조 안 되는지 확인. 재번호는 최소화.
- 새 블록: `{_type:'block', _key:'<uniq>', style, markDefs:[], children:[{_type:'span', _key:'<uniq>sp', text, marks:[]}]}`. _key 는 배열 내 유일하면 됨.

### 4. 사전 체크
- **draft 존재?** `*[_id in ["drafts.legalDocument.refund", ...]]{_id}` — 있으면 Studio 미발행 편집이 있는 것 → 나중에 publish 하면 내 패치를 덮어씀(충돌 주의, 유저에게 알림).
- 직전 조회의 **`_rev`** 확보(동시편집 가드용).

### 5. 확인 (라이브 게시 = 신중)
published 문서 직접 패치는 **웹 `/{slug}` 에 즉시 게시**된다(공개 법적 문서). → **편집안(before/after)을 유저에게 보여주고 확인받은 뒤** 게시. 아래는 게시 전 반드시 언급:
- 즉시 라이브 노출.
- ⚠️ **"게시는 하지만 법적 유효/충분성은 보증 못 한다 — 실 결제/출시 전 약관 법무 검토 권고."** (특히 환불·이용기간·제한 조항.)
- 약관 문구가 **코드 동작과 어긋나면**(예: "자동 환불" 이라 썼는데 코드는 요청 기반) → 유저에게 갭 고지 + **GitHub 이슈로 정합 TODO** 남길지 물어봄.

### 6. 쓰기 (`sanity exec --with-user-token`, Node 22)
임시 `.mjs` 스크립트로 패치 → 실행 → **삭제**(레포에 안 남김). `ifRevisionId(<rev>)` 로 동시편집 보호. 🔒 **토큰 값 echo 금지.**
```js
// sanity/_legal_update.mjs (실행 후 삭제)
import cli from 'sanity/cli'
const { getCliClient } = cli                 // v3.99: default export (named import 실패)
const client = getCliClient({ apiVersion: '2024-01-01' })
const blk = (k, style, text) => ({ _type:'block', _key:k, style, markDefs:[], children:[{_type:'span', _key:k+'sp', text, marks:[]}] })
await client.patch('legalDocument.refund').ifRevisionId('<rev>')
  .set({ 'body[_key=="<headingKey>"].children[0].text': '7. 유의사항' })       // 번호 조정 등
  .insert('before', 'body[_key=="<headingKey>"]', [ blk('newh','h2','6. ...'), blk('n1','normal','① ...') ])
  .commit({ visibility: 'sync' })
```
```bash
cd sanity && PATH="$HOME/.nvm/versions/node/v22.22.2/bin:$PATH" node_modules/.bin/sanity exec _legal_update.mjs --with-user-token
# `The sanity package is moving to v4` 배너 / term-size 경고는 무해. 성공 시 새 _rev 출력.
```
쓰기 토큰이 없고 CLI 로그인도 없으면 → `sanity login`(유저) 또는 Editor `SANITY_WRITE_TOKEN` 발급 요청.

### 7. version 규칙
- **`legalDocument`** — bump 강제 **없음**(표시 전용, BE 박제 안 함). 미출시면 `1.0` 유지 OK. 페이퍼트레일 원하면 bump.
- **`term`** — body 변경 시 **version bump 강제**(schema validation; BE 가 (key,version) 박제하므로). 의미 개정이면 `v1→v2`. 안 올리면 publish 차단.

### 8. 게시 후 검증
바꾼 블록을 **재조회**해 실제 반영 확인(read-only). CDN 은 publish 시 flush 되어 수 초 내 수렴.

### 9. 기록
의미 있는 약관 변경은 **메모리 `legal_policy_decisions` 갱신**. 코드↔약관 갭이면 **GitHub 이슈**로 정합 TODO(예: #186 6개월 자동환불).

## 관련
- 메커니즘 원본: [`sanity/data-access.md`](../../../sanity/data-access.md) · Studio/스키마: [`sanity/CLAUDE.md`](../../../sanity/CLAUDE.md)
- 정책·히스토리: [`docs/features/consent-and-terms.md`](../../../docs/features/consent-and-terms.md) · 메모리 `legal_policy_decisions`
- ⚠️ 배포/스키마 변경(새 타입)은 이 스킬 아님 — Node 22 매니페스트 함정은 `sanity/CLAUDE.md`.
