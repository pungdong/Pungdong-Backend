# CLAUDE.md — address (주소·위치정보 도메인)

이 패키지를 열면 자동 로드되는 좁은 컨텍스트. 전체 컨벤션은 루트 [CLAUDE.md](../../../../../../../CLAUDE.md).

> **package-by-feature** 도메인. 외부 juso(주소기반산업지원서비스) API 와의 경계 + 좌표 변환. `global/` 이 아니라 도메인인 이유: 컨트롤러·서비스·외부 클라이언트·DTO 를 가진 하나의 기능 단위라서.

## 무엇이 들어있나 — 주소 검색 + 좌표 변환

**juso 통합은 BE 한 곳(여기)에만.** FE(웹·앱)는 juso 를 직접 호출하지 않는다 — 승인키 노출 + 모바일 BFF 부재 때문. 항상 이 도메인의 엔드포인트를 거친다(키는 BE 밖으로 안 나감).

- **컨트롤러**: `AddressController` — `GET /address-search?keyword=&page=&size=`(도로명주소 검색), `POST /geocode`(선택 주소 → WGS84 위경도).
- **서비스**: `AddressService` — keyword 검증·페이지 클램프 + client 위임(얇음).
- **외부 경계**: `AddressApiClient`(interface) + `JusoAddressApiClient`(실 juso 호출, prod/staging) / `StubAddressApiClient`(로컬 고정값) — `@ConditionalOnProperty(pungdong.address.geocode-mode = stub|juso)` 게이트. (`SanityTermClient`·`IdentityVerifier` 와 동일 패턴.)
- **dto/**: `GeocodeRequest`(POST 바디). 응답/검색결과는 `AddressApiClient` 의 record(`AddressItem`/`SearchResult`/`Coordinate`).

보안 매처(`/address-search`, `/geocode` → authenticated)는 **`global/security/SecurityConfiguration`**.

## 작업 전 반드시 읽기

- **[docs/architecture/address.md](../../../../../../../docs/architecture/address.md)** — 흐름/모델/권한/간극
- juso 명세: 검색 `addrLinkApi.do`, 좌표 `addrCoordApi.do` (요청/응답 필드는 architecture 문서)
- 컨트롤러/응답 바꾸면 **같은 PR 에서 [docs/api-clients/types.ts](../../../../../../../docs/api-clients/types.ts) 갱신**

## 결정 히스토리 (왜 이렇게 됐나)

- **juso 통합 = BE 소유, FE 는 BE 경유** — FE 직접 호출은 승인키 노출(앱은 BFF 도 없음). 좌표 변환은 서버에서. (사용자 결정 2026-06-13)
- **승인키 2개** — 검색({@code search-key})과 좌표({@code coord-key})가 별개. 좌표 API 는 검색 키를 거부(juso E0001). 둘 다 env.
- **좌표제공은 개발용 승인키 없음 → 로컬 stub 기본** — `geocode-mode=stub`(default)이라 로컬은 외부 호출 0(고정 좌표). 실호출 검증은 staging/prod(`geocode-mode=juso`). 검색은 개발키 있어 실호출 가능하지만 일관성/외부의존 회피로 stub 통일.
- **좌표계 변환** — 좌표제공 `entX/entY` 는 WGS84 가 아니라 한국 격자좌표. proj4j 로 `source-crs`(**EPSG:5179** GRS80 UTM-K) → WGS84. ✅ **검증 완료(2026-06-13)**: 서울시청 실호출 entX/entY → 37.566/126.978 일치(pyproj·proj4j). 명세엔 미기재였으나 실호출로 확정.
- **referer** — 운영 키 등록 URL 제한 대응, 서버 호출에 `Referer` 헤더(등록 URL) 세팅(설정 비면 생략).
- **소비자** — 강사 커스텀 위치(`venue` `/venues`)·공식 위치(admin BFF→Sanity write 전 좌표)·향후 주소 입력 화면. 그래서 venue 전용이 아니라 공용 도메인.

## 안전망 테스트

`src/test/.../usecase/AddressUseCaseTest` — 실 H2 + 시큐리티 체인, stub 모드(외부 juso 미호출, 결정적). S(검색·좌표)/V(검증)/T(인증). ⚠️ Authorization 헤더 raw JWT.

## 검증됨 / 아직 안 한 것

- ✅ **실 juso 검증(2026-06-13)** — staging 키 + `Referer` 로 **로컬에서** 검색·좌표 errorCode 0, 좌표계 EPSG:5179 확정(서울시청). 좌표제공도 referer 헤더면 로컬 호출 가능.
- 후속: 캐싱(동일 주소 좌표 재요청) · REST Docs `document(...)` · 소비자 연동(venue 주소→좌표).
