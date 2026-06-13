/**
 * Pungdong Backend — API Type Contract (TypeScript)
 *
 * 모바일 / 웹 클라이언트가 호출하는 REST API 의 단일 출처.
 * BE 컨트롤러 시그니처가 바뀌면 같은 PR 안에서 이 파일도 같이 갱신된다.
 *
 * 디렉토리 구성은 docs/api-clients/README.md 참고.
 * 도메인 별 의미 / 흐름은 docs/architecture/<domain>.md 참고.
 */

// ============================================================
// 공통 응답 envelope
// ============================================================

/**
 * 실패 / 성공-empty 응답의 공통 형태.
 * - 인증/권한 실패 (401/403), 검증 실패 (4xx) 등 ExceptionAdvice 가 변환하는 응답.
 * - 단순 "성공만 알리는" 엔드포인트도 이 형태로 응답.
 */
export interface CommonResult {
  success: boolean;
  code: number;
  msg: string;
}

/** 단일 데이터 + 성공 메타. */
export interface SingleResult<T> extends CommonResult {
  data: T;
}

/** 리스트 데이터 + 성공 메타. */
export interface ListResult<T> extends CommonResult {
  list: T[];
}

/**
 * HATEOAS 응답 wrapper. Spring HAL_JSON 으로 응답하는 일부 엔드포인트가
 * 본 페이로드 위에 _links 를 추가해서 내려준다. 클라이언트는 _links 무시 가능.
 */
export interface HalLinks {
  _links?: {
    self?: { href: string };
    profile?: { href: string };
    [rel: string]: { href: string } | undefined;
  };
}

// ============================================================
// 도메인 값 (enum)
// ============================================================

export type Role = 'STUDENT' | 'INSTRUCTOR' | 'ADMIN';

export type AuthProvider = 'EMAIL' | 'KAKAO' | 'NAVER' | 'APPLE';

export type Gender = 'MALE' | 'FEMALE';

export type DeviceType = 'ANDROID' | 'IOS' | 'WEB';

/** 강사 신청 상태. 내 신청 조회는 미신청 시 'NONE' 도 반환. */
export type InstructorApplicationStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED';

/** 간편인증 공급자 (본인확인). 실제 본인확인기관 연동은 deferred — 현재 stub. */
export type IdentityProvider = 'KAKAO' | 'NAVER' | 'TOSS' | 'PASS' | 'KB' | 'PAYCO';

/** 위치 유형 — 얕은풀장(10m 이하) / 딥풀 / 해양(다이빙 포인트). 정확한 깊이는 maxDepth 로 별도. */
export type VenueType = 'SHALLOW_POOL' | 'DEEP_POOL' | 'OCEAN';

/** 위치 소유/공개 범위 — 어드민 정식(공식 카탈로그) / 강사 커스텀(비공개·종목 잠금). */
export type VenueScope = 'OFFICIAL' | 'CUSTOM';

/** 하루 파트 — 평일 / 주말·공휴일. */
export type DaypartKind = 'WEEKDAY' | 'WEEKEND';

/** 시간 제공 방식 — 고정 시간대 / 상시 입장 / (주말 전용) 평일과 동일. */
export type VenueTimeMode = 'FIXED' | 'OPEN' | 'SAME';

/** 정기 휴무 종류 — 매주 / 매월 N째 주. */
export type VenueClosureType = 'WEEKLY' | 'MONTHLY';

/** java.time.DayOfWeek 직렬화 — 풀 대문자 영문. */
export type Weekday =
  | 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

// ============================================================
// 인증 / 토큰
// ============================================================

/**
 * JWT 토큰 묶음. /sign/login, /sign/refresh, /sign/sign-up 응답 안에 들어간다.
 * - access_token: Authorization 헤더에 그대로 넣어 사용 (Bearer prefix 없이)
 * - refresh_token: /sign/refresh 본문에 담아 갱신
 */
export interface AuthToken {
  access_token: string;
  refresh_token: string;
  token_type: 'bearer';
  scope: string;
  expires_in: number; // access_token 유효 시간 (초)
  jti: string; // 토큰 식별자 (UUID)
}

// ============================================================
// 회원가입 + 로그인 (sign-up 도메인)
// docs/architecture/sign-up.md 참고
// ============================================================

/** POST /sign/sign-up 요청. */
export interface SignUpRequest {
  email: string;
  password: string;
  nickName: string;
}

/**
 * POST /sign/sign-up 응답 (201 Created).
 * 가입과 동시에 로그인 처리 — tokens 가 함께 내려와서 별도 /sign/login 불필요.
 */
export interface SignUpResponse extends HalLinks {
  email: string;
  nickName: string;
  tokens: AuthToken;
}

/** POST /sign/login 요청. */
export interface LoginRequest {
  email: string;
  password: string;
}

/** POST /sign/login 응답 (200 OK) — AuthToken 을 HAL 래퍼로 감싸서 응답. */
export type LoginResponse = AuthToken & HalLinks;

/** POST /sign/refresh 요청 — 만료된 access 갱신용. */
export interface RefreshRequest {
  refreshToken: string;
}

/** POST /sign/refresh 응답 (200 OK) — 새 access + refresh 쌍 (rotation). */
export type RefreshResponse = AuthToken & HalLinks;

/** POST /sign/logout 요청 — 두 토큰 모두 블랙리스트 등록. */
export interface LogoutRequest {
  accessToken: string;
  refreshToken: string;
}

/** POST /sign/logout 응답. */
export interface LogoutResponse extends HalLinks {
  message: string;
}

/** POST /sign/check/email 요청 — 가입 전 사전 체크. */
export interface CheckEmailRequest {
  email: string;
}

/** POST /sign/check/email 응답. */
export interface CheckEmailResponse extends HalLinks {
  exists: boolean;
}

/** GET /sign/check/nickName?nickName=... 응답. */
export interface CheckNickNameResponse extends HalLinks {
  exists: boolean;
}

// ============================================================
// 알림 / 디바이스 토큰 (notification 도메인)
// docs/architecture/notification.md 참고
// ============================================================

/**
 * POST /sign/firebase-token 요청 — 디바이스 FCM 토큰을 계정에 묶는다.
 * 같은 토큰을 다른 계정으로 등록하면 upsert (account_id 갱신, 행 추가 X).
 * 응답은 204 No Content.
 */
export interface RegisterFirebaseTokenRequest {
  token: string;
}

// ============================================================
// 본인확인 (identity-verification 도메인) — 계정 공유 자산
// docs/architecture/identity-verification.md 참고
//
// 수강/강사 어느 플로우에서든 같은 본인확인 레코드를 만들고(POST) 조회한다(GET /me).
// 강사 신청 진입 시 GET /me 로 기존 인증을 확인해 재인증을 건너뛰고(skip) verificationId 재사용.
// 현재 stub(즉시 verified) — 실 본인확인기관 연동은 deferred.
// ============================================================

/**
 * POST /identity-verifications 요청 — 본인확인(간편인증 stub).
 * PII(실명·생년월일·휴대폰)는 POST body 로만 전송 (URL/쿼리 금지).
 */
export interface IdentityVerificationRequest {
  realName: string;
  /** yyyyMMdd */
  birth: string;
  gender: Gender;
  phoneNumber: string;
  provider: IdentityProvider;
  /** 필수 약관 전체 동의. false 면 400. */
  agreedRequiredTerms: boolean;
}

/** POST /identity-verifications 응답(201). verificationId 를 강사 신청 제출에 재사용. */
export interface IdentityVerificationResponse extends HalLinks {
  verificationId: number;
  verified: boolean;
  realName: string;
}

/**
 * GET /identity-verifications/me — 내 최신 본인확인 상태 (계정 공유).
 * 미인증도 200 `{ verified: false }` (404 아님). verified 면 verificationId 를 강사 신청에 재사용.
 * verifiedAt 은 노출되지만 현재 만료 판단엔 안 씀(무만료) — 법적 재인증 주기 정해지면 TTL 추가.
 */
export interface MyIdentityVerificationResponse extends HalLinks {
  verified: boolean;
  verificationId?: number;
  realName?: string;
  provider?: IdentityProvider;
  verifiedAt?: string;
}

// ============================================================
// 동의 / 약관 (consent 도메인) — 계정 공유 자산
// docs/architecture/consent.md · docs/features/consent-and-terms.md 참고
//
// 약관 콘텐츠(전문/요약/버전)는 Sanity 가 소유 — FE 가 화면(context) 기준으로 직접 읽어 보여준다.
// BE 는 "누가 어떤 약관 버전에 동의했나"만 기록한다. ★ FE 는 약관 key 만 보낸다(version 아님) —
// 어떤 버전으로 기록할지는 BE 가 key 로 Sanity 현재 버전을 조회해 전적으로 정한다. 그 버전을 처음
// 보면 전문을 받아 불변 박제(증빙), 이후는 참조만 → 유저별 전문 복사 X. 기록된 version 은 응답으로 받는다.
// ============================================================

/** 동의를 수집한 화면. Sanity term.contexts 와 같은 어휘 (lowercase snake). */
export type ConsentContext =
  | 'signup'
  | 'identity_verification'
  | 'instructor_application'
  | 'payment';

/** POST /consents 요청 — 한 화면에서 체크한 약관 key 들. version 은 보내지 않는다(BE 가 정함). */
export interface RecordConsentRequest {
  context: ConsentContext;
  /** 동의한 약관 key 들 (예: ["privacy_collect", "unique_id_ci_di"]). 최소 1건, 빈 배열이면 400. */
  keys: string[];
}

/** 기록된 동의 1건 (응답 전용) — BE 가 key 로 정한 현재 version 을 함께 돌려준다. */
export interface AgreementRef {
  key: string; // Sanity term.key (예: privacy_collect)
  version: string; // BE 가 기록한 버전 (Sanity 현재값)
}

/** POST /consents 응답(201). agreements 로 "각 key 가 어떤 version 으로 기록됐는지" 확인. */
export interface RecordConsentResponse extends HalLinks {
  recorded: number;
  agreements: AgreementRef[];
}

/** GET /consents/me 항목 — 내 동의 이력 1건. 배열은 `_embedded.consents` (CollectionModel). */
export interface MyConsentResponse {
  key: string;
  version: string;
  title: string;
  context: ConsentContext;
  /** ISO-8601 */
  agreedAt: string;
}

// ============================================================
// 강사 신청 (instructor-application 도메인)
// docs/architecture/instructor-application.md 참고
//
// 흐름(2-phase): (본인확인은 위 identity-verification 도메인) → 자격증 이미지 업로드 → 제출.
// 진입 시 GET /identity-verifications/me 로 skip 판단. 단체 목록/안내문구는 Sanity.
// ============================================================

/**
 * POST /instructor-applications/certificate-images 응답 (2-phase 1단계).
 * 요청은 multipart/form-data, 파트 이름 `image` (단일 파일). 여러 장이면 반복 호출.
 */
export interface CertificateImageResponse extends HalLinks {
  fileURL: string;
}

// ── 종목 (discipline) — docs/architecture/discipline.md ──
// 홈 셀렉터 · 강사 신청 종목 선택 공용. requiresCertification 으로 강사 신청 시 자격증 필수
// 여부가 갈림 (스쿠버/프리다이빙=true, 수영/서핑=false). 종목별 단체 목록은 Sanity 카탈로그.

/** GET /disciplines (공개) 항목 — 배열은 `_embedded.disciplines` 에 들어옴 (CollectionModel). */
export interface DisciplineResponse {
  code: string; // "FREEDIVING" | "SCUBA" | "MERMAID" | "SWIMMING" | "SURFING" ...
  name: string; // "프리다이빙"
  requiresCertification: boolean;
  sortOrder: number;
}

/**
 * 자격증 1건 = 발급 단체 + 이미지. 한 종목 신청에 여러 단체(AIDA+PADI+...) 가능.
 * 제출 요청과 조회 응답에 공용. (향후 레벨/등급 필드 추가 자리)
 */
export interface ApplicationCertificate {
  organizationCode: string; // 'AIDA' | 'PADI' | 'OTHER' ... (Sanity 카탈로그, 종목별)
  organizationOther?: string; // 'OTHER' 일 때
  fileURL: string; // 2-phase 업로드로 받은 URL
}

/** POST /instructor-applications (제출) · PUT /instructor-applications/me (재제출) 요청. */
export interface InstructorApplicationSubmitRequest {
  /** GET /disciplines 의 code. */
  disciplineCode: string;
  /** GET /identity-verifications/me 에서 재사용 (없으면 POST /identity-verifications 로 생성). */
  verificationId: number;
  /** 자격증 목록(단체+이미지). 자격증 필요 종목은 1건 이상, 불필요 종목은 생략. */
  certificates?: ApplicationCertificate[];
}

/** 신청 제출/재제출 결과. POST 는 201, PUT 은 200. */
export interface InstructorApplicationResponse extends HalLinks {
  applicationId: number;
  status: InstructorApplicationStatus;
}

/**
 * GET /instructor-applications/me — 내 신청 목록 (종목별 여러 건). 배열은 `_embedded.applications`
 * (CollectionModel). 미신청 종목은 항목 없음 → FE 가 선택된 종목으로 필터, 없으면 "신청하기" 노출.
 */
export interface MyInstructorApplicationResponse {
  disciplineCode: string;
  status: InstructorApplicationStatus;
  certificates: ApplicationCertificate[];
  identityVerified: boolean;
  /** REJECTED 일 때 반려 사유. */
  rejectionReason?: string;
  submittedAt?: string;
  reviewedAt?: string;
}

/**
 * 어드민 목록 (GET /admin/instructor-applications) 의 한 행. PagedModel — 배열은
 * `_embedded.applications`, 페이지 메타는 `page`. `status` 쿼리 생략 시 전체, 지정 시 탭별.
 * 기본 정렬 submittedAt desc.
 */
export interface InstructorApplicationSummary extends HalLinks {
  applicationId: number;
  accountId: number;
  nickName: string;
  email: string;
  disciplineCode: string;
  /** 첨부 자격증의 단체들(중복 제거). */
  organizationCodes: string[];
  status: InstructorApplicationStatus;
  submittedAt: string;
}

/** GET /admin/instructor-applications/counts — 탭 뱃지용 상태별 건수. */
export interface InstructorApplicationCountsResponse extends HalLinks {
  submitted: number;
  approved: number;
  rejected: number;
  total: number;
}

/** GET /admin/instructor-applications/{id} — 어드민 상세 (본인확인 PII 포함, ADMIN 전용). */
export interface InstructorApplicationDetailResponse extends HalLinks {
  applicationId: number;
  accountId: number;
  email: string;
  nickName: string;
  status: InstructorApplicationStatus;
  disciplineCode: string;
  certificates: ApplicationCertificate[];
  realName: string;
  birth: string;
  phoneNumber: string;
  rejectionReason?: string;
  createdAt?: string;
  submittedAt: string;
  reviewedAt?: string;
  /** 처리한 어드민 닉네임 (승인/반려 후). */
  reviewerNickName?: string;
}

/**
 * POST /instructor-applications/certificates — 자격증 관리 탭. 이미 승인된(APPROVED) 강사가
 * 그 종목에 자격증 1건 추가. MVP 는 검수 없이 즉시 반영(상태 APPROVED 유지). 승인 전 신청은
 * 제출/재제출(POST·PUT)로. 같은 종목 재신청(POST /instructor-applications)은 400.
 */
export interface AddCertificateRequest {
  disciplineCode: string;
  organizationCode: string;
  organizationOther?: string;
  fileURL: string;
}

/** POST /admin/instructor-applications/{id}/reject 요청. */
export interface RejectInstructorApplicationRequest {
  reason: string;
}

// ── 위치 (venue) — docs/features/venue.md ──
// 수영장(딥풀)·해양 포인트 = 강의가 진행되는 장소. 입장료·운영 시간대·이용 옵션·정기휴무가 위치에 종속.
// ⚠️ 소유 분담:
//   - 공식(OFFICIAL) 수영장 = Sanity authoring. FE 가 Sanity 를 GROQ(`sanity/queries.ts`
//     officialVenuesByDiscipline / venueById)로 직접 읽음 — 이 파일의 BE 엔드포인트 아님.
//   - 커스텀(CUSTOM) = 강사가 만든 비공개·종목잠금 위치 → 아래 BE 엔드포인트.
// 코스 빌더 official+custom 통합은 후속 **BE 단일 머지 엔드포인트**(예: GET /venues/for-course)가
//   official(Sanity 서버사이드)+custom(DB)을 합쳐 반환 — FE 는 데이터 소스를 모른다. course 생성과 함께 구축.
// 현재 GET /venues = 내 custom 목록(관리용). 공식 위치 공개 표시는 FE 가 Sanity 직접 읽기.
// 시간은 "HH:mm:ss" 문자열. BE 엔드포인트(모두 인증 — 강사 트랙):
//   POST /venues · GET /venues?disciplineCode=&type= · GET/PUT/DELETE /venues/{id}
// 아래 인터페이스는 BE CUSTOM 응답 기준(Sanity OFFICIAL 도 모양은 유사).

/** 시간블록 1구간 (FIXED 모드의 "부"). 수강생이 이 중 하나를 고른다. */
export interface VenueTimeBlock {
  startTime: string; // "08:00:00"
  endTime: string; // "11:00:00"
  sortOrder: number;
}

/**
 * 평일/주말 하루 파트. 한 이용 옵션에 WEEKDAY 1개 + (선택) WEEKEND 1개.
 * - WEEKDAY: 항상 sold=true, timeMode ∈ FIXED|OPEN
 * - WEEKEND: sold=false(주말 불가) 가능, timeMode ∈ SAME(평일과 동일)|FIXED|OPEN
 * - FIXED → timeBlocks 사용 / OPEN → openStart~openEnd + holdHours(키반납 N시간, 수강생이 시작 시각 선택)
 */
export interface VenueDaypart {
  kind: DaypartKind;
  sold: boolean;
  fee?: number; // 입장료(원). 평일/주말 독립. sold=false 면 생략
  timeMode?: VenueTimeMode;
  openStart?: string; // OPEN "09:00:00"
  openEnd?: string; // OPEN "22:00:00"
  holdHours?: number; // OPEN 키반납 시간
  timeBlocks: VenueTimeBlock[];
  /** 응답 전용 — 파생 이용시간(시간). FIXED=첫 블록 길이, OPEN=키반납, SAME=null. 요청 시 무시. */
  durationHours?: number | null;
}

/** 이용 옵션 1종 = 한 카드(일반권/하프권/종일권 …). 권종은 카드를 추가하는 것 — 이용시간은 파생. */
export interface VenueTicket {
  id?: number; // 응답 전용
  name?: string;
  sortOrder?: number;
  /** 적용 종목 코드(disciplines.code). CUSTOM 은 lockedDisciplineCode 1개로 강제(OFFICIAL/Sanity 는 멀티 가능). */
  disciplineCodes: string[];
  dayparts: VenueDaypart[];
}

/**
 * 정기 휴무 1규칙. 월간은 atomic — "N째 주 X요일" 1건(`nth`+`monthlyWeekday`).
 * "2·4주 화" 나 "2주 화 + 4주 목"은 MONTHLY 항목을 여러 개로(grouping 은 UI 표현, 저장은 원자 단위).
 */
export interface VenueClosure {
  type: VenueClosureType;
  /** WEEKLY — 매주 휴무 요일들. */
  weekdays?: Weekday[];
  /** MONTHLY — 몇째 주(1~5, 1건). */
  nth?: number;
  /** MONTHLY — 요일 1개. */
  monthlyWeekday?: Weekday;
}

/**
 * 커스텀 위치 생성/수정 요청 — POST /venues · PUT /venues/{id}. owner 는 현재 계정(바디 아님).
 * lockedDisciplineCode 필수 — 그 종목 강사신청 보유 시에만 생성(PENDING 포함). 모든 티켓이 그 종목으로 강제.
 * (공식 위치는 BE 아님 — Sanity Studio authoring.)
 */
export interface VenueCreateRequest {
  name: string;
  type: VenueType;
  /** 정식 도로명주소 (위/경도 기준 — address 도메인 검색→좌표 결과). */
  address?: string;
  /** 세부주소 (동·호수 등, 선택). geocoding 대상 아님. */
  addressDetail?: string;
  latitude?: number;
  longitude?: number;
  /** 최대수심(m, 선택). */
  maxDepth?: number;
  /** 위치가 잠길 종목 코드 (필수). */
  lockedDisciplineCode: string;
  closures?: VenueClosure[];
  /** 최소 1개. 각 티켓은 WEEKDAY daypart 필수. 티켓 disciplineCodes 는 lockedDisciplineCode 와 일치해야 함. */
  tickets: VenueTicket[];
}

/**
 * 커스텀 위치 응답(상세/목록). 목록은 `_embedded.venues`(CollectionModel) — GET /venues 는 내 커스텀만.
 * `scope` 는 항상 'CUSTOM'(FE 가 Sanity OFFICIAL 과 합쳐 통합 리스트 만들 때 구분용).
 * 공식(OFFICIAL) 위치 모양은 Sanity GROQ 결과(`sanity/queries.ts`)를 FE 가 유사 형태로 매핑.
 */
export interface VenueResponse extends HalLinks {
  id: number;
  name: string;
  type: VenueType;
  /** 정식 도로명주소 (위/경도 기준). */
  address?: string;
  /** 세부주소 (동·호수 등, 선택). */
  addressDetail?: string;
  latitude?: number;
  longitude?: number;
  /** 최대수심(m, 선택). */
  maxDepth?: number;
  scope: 'CUSTOM';
  /** 소유 강사 id. */
  ownerId: number;
  lockedDisciplineCode: string;
  closures: VenueClosure[];
  tickets: VenueTicket[];
  createdAt?: string;
  updatedAt?: string;
}

// ── 주소 검색 + 좌표 변환 (address) — docs/architecture/address.md ──
// juso(주소기반산업지원서비스) 통합은 BE 한 곳에만 — FE(웹·앱)는 juso 직접 호출 X(승인키 은닉 +
// 모바일 BFF 부재). 항상 BE 를 거친다. 모두 인증 필요.
//   GET  /address-search?keyword=&page=&size=   → 도로명주소 검색(후보 목록)
//   POST /geocode { admCd,rnMgtSn,udrtYn,buldMnnm,buldSlno } → WGS84 위경도

/** 검색 결과 1건. 표시용(roadAddr 등) + 좌표 변환에 넘길 키(admCd/rnMgtSn/udrtYn/buldMnnm/buldSlno). */
export interface AddressItem {
  roadAddr: string;
  jibunAddr: string;
  zipNo: string;
  bdNm?: string;
  siNm?: string;
  sggNm?: string;
  emdNm?: string;
  // 좌표 변환용 키 (선택 후 그대로 POST /geocode 로):
  admCd: string;
  rnMgtSn: string;
  udrtYn: string;
  buldMnnm: string;
  buldSlno: string;
}

/** GET /address-search 응답. */
export interface AddressSearchResult {
  totalCount: number;
  page: number;
  countPerPage: number;
  items: AddressItem[];
}

/** POST /geocode 요청 — 검색 결과에서 고른 항목의 키 5개. */
export interface GeocodeRequest {
  admCd: string;
  rnMgtSn: string;
  udrtYn: string;
  buldMnnm: string;
  buldSlno?: string;
}

/** POST /geocode 응답 — WGS84 위경도(구글맵 등 표준). */
export interface Coordinate {
  latitude: number;
  longitude: number;
}

// ============================================================
// 인증 실패 응답 코드 (참고용)
// docs/architecture/sign-up.md 의 "보안 / 권한 매트릭스" 참고
// ============================================================

/**
 * ExceptionAdvice + Security 핸들러가 내는 에러 code 매핑.
 * 클라이언트는 일반적으로 `success === false` 만 보고 분기하지만,
 * 토큰 갱신 / 재로그인 같은 자동 흐름은 code 로 분기 가능.
 */
export const ErrorCode = {
  EMAIL_SIGNIN_FAILED: -1001,
  AUTH_ENTRY_POINT: -1002,
  ACCESS_DENIED: -1003,
  SIGN_IN_INPUT: -1004,
  EXPIRED_ACCESS_TOKEN: -1005,
  EXPIRED_REFRESH_TOKEN: -1006,
  FORBIDDEN_TOKEN: -1007,
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];
