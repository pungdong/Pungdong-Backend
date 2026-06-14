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

/** 위치 유형 — 일반 수영장 / 잠수풀 / 딥풀 / 해양(다이빙 포인트). 정확한 깊이는 maxDepth 로 별도. */
export type VenueType = 'SWIMMING_POOL' | 'DIVING_POOL' | 'DEEP_POOL' | 'OCEAN';

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

// ── 계정 조회 (account) — docs/architecture/sign-up.md ──
// GET /account (인증) — 현재 계정 기본 정보. `roles` 로 강사/수강생 화면 분기(권위 소스).
// ★ JWT 의 roles 클레임이 아니라 이 값으로 분기 — role 은 additive 이고 서버가 매 요청 재계산하므로
//   강사 승인 직후에도 정확(토큰은 발급 시점 고정이라 stale). (프로필 탭용 통합 조회 API 는 후속.)

export interface AccountBasicInfo extends HalLinks {
  id: number;
  email: string;
  nickName: string;
  birth?: string;
  gender?: Gender;
  phoneNumber?: string;
  /** 강사면 'INSTRUCTOR' 포함(STUDENT 와 함께). FE 탭 분기는 roles.includes('INSTRUCTOR'). */
  roles: Role[];
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
// 코스 빌더 official+custom 통합 = GET /venues/builder — BE 가 official(Sanity 서버사이드+Redis 캐시)
//   + 내 custom(DB)을 합쳐 반환. FE 는 데이터 소스를 모른다 — 항목의 scope/venueRefId 로 구분.
// 현재 GET /venues = 내 custom 목록(관리용). 공식 위치 공개 표시는 FE 가 Sanity 직접 읽기.
// 시간은 "HH:mm:ss" 문자열. BE 엔드포인트(모두 인증 — 강사 트랙):
//   POST /venues · GET /venues?disciplineCode=&type= · GET /venues/builder?disciplineCode=&type=
//   · GET/PUT/DELETE /venues/{id}
// VenueResponse 는 custom(scope=CUSTOM)·official(scope=OFFICIAL) 공용 — builder 는 둘이 섞여 온다.

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
  // (durationHours 자동 파생 제거 — 시간블록과 실제 이용시간이 다른 운영 사례(예: 6h 블록·5h 이용)가
  //  있어 신뢰 불가. 이용시간 표기는 이용권 name 의 "(N시간)"(어드민 입력)을 쓴다.)
}

/** 이용 옵션 1종 = 한 카드(일반권/하프권/종일권 …). 권종은 카드를 추가하는 것 — 이용시간은 파생. */
export interface VenueTicket {
  /**
   * 이용권 안정 식별자 — 코스 저장 시 CourseVenueRequest.tickets[].ticketRef 로 그대로 보낸다.
   * CUSTOM = DB pk 문자열, OFFICIAL = Sanity 배열 _key. ★ 예전 `id`(number) 가정 폐기 — OFFICIAL 은
   * id 가 없어 ticketRef 를 써야 한다.
   */
  ticketRef: string;
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
 * 위치 응답 — 목록은 `_embedded.venues`(CollectionModel). custom·official 공용.
 * - GET /venues : 내 custom 만 (scope 항상 'CUSTOM').
 * - GET /venues/builder : OFFICIAL(Sanity 캐시) + 내 CUSTOM 머지 (scope 섞임).
 * 코스는 `venueRefId`("CUSTOM:<pk>"|"OFFICIAL:<sanityId>")를 저장한다(안정 참조 토큰).
 * OFFICIAL 항목은 id=null·ownerId=null·lockedDisciplineCode=null.
 */
export interface VenueResponse extends HalLinks {
  /** BE custom PK. OFFICIAL 은 null. */
  id: number | null;
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
  scope: 'CUSTOM' | 'OFFICIAL';
  /** 코스가 저장하는 안정 참조 토큰. "CUSTOM:<pk>" | "OFFICIAL:<sanityId>". */
  venueRefId: string;
  /** 소유 강사 id. OFFICIAL 은 null. */
  ownerId: number | null;
  /** CUSTOM 만. OFFICIAL 은 null(이용권이 멀티 종목). */
  lockedDisciplineCode: string | null;
  closures: VenueClosure[];
  tickets: VenueTicket[];
  createdAt?: string;
  updatedAt?: string;
}

// ── 대여 장비 가격표 (equipment extension) — docs/architecture/venue.md ──
// 장비 대여료는 위치별로 다름(딥스테이션 무료포함 ↔ 5m풀 유료) → 강사 × 위치 단위 가격표(강사 전역,
// 모든 코스 공유, "어디서 바꿔도 신규 접수부터 적용"). 위치는 venueRefId(빌더 목록이 준 토큰)로 가리킴.
// 모두 인증(강사 트랙). GET /venue-equipment(?venueRefId= 단건/전체) · PUT /venue-equipment(upsert).

/** 사이즈 표기 형식. 미입력 시 SHOE_MM/APPAREL_SXL 은 서버 프리셋 자동, NONE 은 빈 목록, CUSTOM 은 직접. */
export type SizeFormat = 'NONE' | 'SHOE_MM' | 'APPAREL_SXL' | 'CUSTOM';

/** 장비 1종 (요청·응답 공용 모양). price 0 = 무료. */
export interface VenueEquipmentItem {
  /** 응답에만. */
  id?: number;
  name: string;
  price: number;
  /** 미지정 시 NONE 취급. */
  sizeFormat?: SizeFormat;
  /** 수강생이 고를 사이즈. 비우면 sizeFormat 프리셋으로 채워져 응답에 옴. */
  sizeOptions?: string[];
}

/** PUT /venue-equipment 요청 — 한 위치 가격표 저장(items 전량 교체 스냅샷). */
export interface VenueEquipmentRequest {
  /** "CUSTOM:<pk>" | "OFFICIAL:<sanityId>" (GET /venues/builder 항목의 venueRefId). */
  venueRefId: string;
  items: VenueEquipmentItem[];
}

/** 가격표 응답. 목록은 `_embedded.extensions`(CollectionModel). */
export interface VenueEquipmentResponse extends HalLinks {
  id: number;
  venueRefId: string;
  items: VenueEquipmentItem[];
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
// 코스 작성 (course-create 도메인) — 강사 강의 개설
// docs/features/course-create.md (정책) · docs/architecture/course.md (구현)
//
// FE 호출 흐름 (순서·소스가 types 만으론 안 보여서 여기 박음):
//   0. 로그인 후 GET /account 의 roles 로 강사/수강생 분기 (JWT 클레임 아님 — additive+서버 재계산이라 stale)
//   1. GET /disciplines (종목) → 그 값으로 ↓ 를 필터 (종목 없이는 코스 생성 400)
//   2. [Sanity 직접] orgsByDiscipline (단체) → 3. [Sanity 직접] certificationsByOrgAndDiscipline (자격증/레벨)
//   4. POST /course-images (사진 → fileURL)  5. GET /venues/builder (위치 + venueRefId)
//   6. PUT /venue-equipment (위치별 장비, 선택)  7. POST /courses
// ★ 단체/자격증/공식위치공개표시는 BE 아니라 Sanity 직접(useCdn:true, GROQ=sanity/queries.ts).
//   코스의 위치 선택만은 GET /venues/builder(official+custom 머지, venueRefId 부여)로.
// ============================================================

// ── 자격증 카탈로그 (Sanity certOrganization.certifications) ──
// 단체마다 명칭은 달라도(예: "Advanced Freediver") 평탄화 레벨로 정규화. FE 가 Sanity 를 GROQ
// (`sanity/queries.ts` certificationsByOrgAndDiscipline)로 직접 읽음 — BE 엔드포인트 아님.
// 코스 작성 "단체 → 레벨" 선택 + 강사 신청 본인 레벨 선택이 같은 카탈로그를 읽는다.

/**
 * 단체 명칭과 무관한 공통 사다리. BE 는 이 값만 enum 으로 저장(displayName 은 표시 전용).
 * INSTRUCTOR_TRAINER = 강사 양성 등급(예: Course Director, Instructor Trainer) — INSTRUCTOR 위 한 칸.
 */
export type CertLevel = 'LEVEL_1' | 'LEVEL_2' | 'LEVEL_3' | 'LEVEL_4' | 'INSTRUCTOR' | 'INSTRUCTOR_TRAINER';

/** Sanity 자격증 1종. 저장/비교는 level, UI 노출은 displayName. */
export interface Certification {
  disciplineCode: string; // 단체 disciplines 안의 값 (FREEDIVING / SCUBA / MERMAID)
  level: CertLevel;
  displayName: string; // 단체가 부르는 이름 (예: "AIDA 2", "PADI Advanced Open Water")
}

// ── 종목별 레벨 표시 라벨 (course /level-labels) ──
// GET /courses/level-labels?disciplineCode=SCUBA (공개) — 수강생 둘러보기 필터 칩용.
// 평탄화 코드(level)는 필터 쿼리값(levels=...), label 은 종목 무관 공통 단계명("레벨 1"),
// alias 는 종목 통용 명칭(스쿠버 "Open Water Diver", 프리다이빙은 null). 표기: alias ? `${label} (${alias})` : label.
// ★ 입문자(단계)+경험자(명칭) 동시 충족 위해 약어(OWD) 아닌 풀네임 사용.
// ★ 강사 코스 작성 화면은 이걸 안 씀 — 거긴 단체 선택됨 → Certification.displayName(단체 공식명) 병기.
//   여긴 단체 무관(필터가 단체 가로지름)이라 종목 공통 명칭. 응답은 CollectionModel(_embedded.levelLabels).

export interface LevelLabel {
  level: CertLevel; // 필터 쿼리값
  label: string; // 공통 단계명 (예: "레벨 1", "강사")
  alias: string | null; // 종목 통용 명칭 (스쿠버 "Open Water Diver"), 없으면 null
}

/**
 * POST /course-images 응답 (2-phase 1단계). 요청은 multipart/form-data, 파트 이름 `image`
 * (단일 파일, 여러 장이면 반복 호출). 받은 URL 을 코스 생성 JSON 의 media 에 넣는다.
 * 이번 단계는 사진만 — 영상 업로드는 후속.
 */
export interface CourseImageResponse extends HalLinks {
  fileURL: string;
}

// ── 코스 본체 (course) ──
// 기본정보 + 회차(설명·위치·이용권 변형) + (선택)추가세션. 위치는 venueRefId(GET /venues/builder 토큰),
// 위치별 장비는 강사×위치 가격표에서 읽기 시점 합성(응답에만, 저장 안 함). 모두 인증(강사 트랙).
//   POST /courses · GET /courses/mine · GET /courses/{id} · PUT /courses/{id} · PATCH /courses/{id}/status

/**
 * 코스 종류 — CERTIFICATION 만 levels(단체+레벨) 사용. TRIAL/TRAINING 은 자격 아님.
 * ★ FE: 종류는 **상호배타 세그먼트**(평탄화 X). CERTIFICATION 일 때만 단체→자격증(레벨) 노출
 *   (멀티선택=패키지), TRIAL/TRAINING 이면 단체·자격증 칸 숨김. "묶음" 욕구는 레벨에 섞지 말 것 —
 *   패키지=자격증 멀티 / 트레이닝 포함=추가세션(무료 N회) / 체험 프레이밍=제목·설명.
 */
export type CourseKind = 'TRIAL' | 'CERTIFICATION' | 'TRAINING';

/** 코스 상태 — 검수 없음. DRAFT 임시저장 / OPEN 노출중 / CLOSED 마감. */
export type CourseStatus = 'DRAFT' | 'OPEN' | 'CLOSED';

export type MediaKind = 'PHOTO' | 'VIDEO';
export type RoundKind = 'REGULAR' | 'EXTRA';

/** POST/PUT 요청. 회차 개수는 totalRounds 와 일치해야. CERTIFICATION 은 organizationCode+levels 필수. */
export interface CourseCreateRequest {
  title: string;
  kind: CourseKind;
  organizationCode?: string; // CERTIFICATION 필수
  disciplineCode: string;
  levels?: CertLevel[]; // CERTIFICATION 필수(>=1, >=2 ⇒ 패키지)
  totalRounds: number;
  price: number; // 부가세 포함 최종가
  description?: string;
  media?: { kind: MediaKind; url: string }[]; // url = POST /course-images 결과
  rounds: CourseRoundRequest[];
  extraSession?: CourseExtraSessionRequest; // 없으면 추가세션 없는 강의
}
export interface CourseRoundRequest {
  description?: string;
  venues: CourseVenueRequest[];
}
export interface CourseExtraSessionRequest {
  description?: string;
  freeCount: number; // N회까지 무료(0=처음부터 유료)
  perSessionPrice: number; // 무료 소진 후 회당
  venues: CourseVenueRequest[];
}
export interface CourseVenueRequest {
  venueRefId: string; // "CUSTOM:<pk>" | "OFFICIAL:<sanityId>"
  tickets: { ticketRef: string; daypart: DaypartKind }[];
}

/** 응답. 목록(GET /mine)은 `_embedded.courses`; 상세는 venue.equipment 합성 포함(목록은 null). */
export interface CourseResponse extends HalLinks {
  id: number;
  instructorId: number;
  title: string;
  kind: CourseKind;
  organizationCode: string | null;
  disciplineCode: string;
  levels: CertLevel[];
  isPackage: boolean;
  totalRounds: number;
  price: number;
  description?: string;
  status: CourseStatus;
  media: { id: number; kind: MediaKind; url: string; sortOrder: number }[];
  rounds: CourseRoundResponse[];
  createdAt?: string;
  updatedAt?: string;
}
export interface CourseRoundResponse {
  id: number;
  roundKind: RoundKind;
  roundIndex: number | null; // REGULAR 1..N, EXTRA null
  platformConfirmed: boolean;
  description?: string;
  freeCount?: number | null; // EXTRA 전용
  perSessionPrice?: number | null;
  venues: CourseVenueResponse[];
}
export interface CourseVenueResponse {
  venueRefId: string;
  tickets: { ticketRef: string; daypart: DaypartKind }[];
  /** 강사×위치 가격표에서 합성. 미설정 위치면 null. */
  equipment: VenueEquipmentResponse | null;
}

/** PATCH /courses/{id}/status 요청. */
export interface CourseStatusRequest {
  status: CourseStatus;
}

// ── 공개 둘러보기 (course browse) — 수강생 메인 홈/필터 시트 ──
// docs/features/course-discovery.md (정책) · docs/architecture/course.md (구현)
// GET /courses/browse — 공개(비로그인 가능). OPEN 코스만. 페이지네이션(PagedModel/HAL).
//   빈 결과는 에러 아님 → 200 + 빈 페이지(page.totalElements 0). "결과 N개" = page.totalElements.

/**
 * 둘러보기 지역 묶음 — 필터 칩(서울·경기/강원/제주/부산·경남)과 1:1. 강사가 따로 입력하지 않고
 * 위치 도로명주소의 시·도에서 BE 가 파생. 어느 묶음에도 안 맞는 시·도(충청·전라 등)는 ETC —
 * 명시 지역 필터엔 안 뜨지만 "전체"(region 생략)에는 포함된다.
 */
export type Region = 'SEOUL_GYEONGGI' | 'GANGWON' | 'JEJU' | 'BUSAN_GYEONGNAM' | 'ETC';

/** 둘러보기 정렬 — 인기순/가까운일정은 평점·확정일정 신호 도입(부킹·리뷰) 후 추가 예정. */
export type CourseBrowseSort = 'LATEST' | 'PRICE_ASC' | 'PRICE_DESC';

/**
 * GET /courses/browse 쿼리 파라미터(disciplineCode 외 전부 선택, 비-PII 라 querystring). 배열 파라미터는
 * 반복 키로 보낸다(`?levels=LEVEL_1&levels=LEVEL_2`).
 *
 * ★ 종류·레벨은 평탄화 멀티칩(필터 한정): 필터 시트는 [체험·L1·L2·L3·트레이닝]을 한 줄로 펼쳐 멀티선택,
 *   결과는 합집합(OR). 체험/트레이닝은 `kinds`, 자격은 `levels` 로 보낸다 — 필터엔 'CERTIFICATION' 칩이
 *   없어서(자격은 레벨 칩으로 표현) `kinds` 엔 TRIAL/TRAINING 만. BE 는 `(kind ∈ kinds) OR (CERTIFICATION
 *   & level ∈ levels)` 로 묶음. (※ 코스 *작성* 화면은 반대로 cascade — 종류 라디오→자격이면 레벨. 필터만
 *   탐색 편의로 평탄화.)
 * ★ 단체 칩 '상관없음' = organizationCodes 생략. 가격 밴드는 FE 가 칩을 min/max 로 변환해 전송.
 */
export interface CourseBrowseParams {
  disciplineCode: string; // 필수 — 종목별 카탈로그가 크게 달라 화면이 항상 한 종목으로 진입(메인 상단 select). 누락 시 400
  keyword?: string; // 제목 부분 일치
  region?: Region; // 생략 = 전체
  kinds?: CourseKind[]; // 평탄 멀티칩 — 체험·트레이닝 (자격은 levels 로). 생략 = 종류 무관
  levels?: CertLevel[]; // 평탄 멀티칩 — L1·L2·L3 (kinds 와 OR 합집합). 생략 = 레벨 무관
  organizationCodes?: string[]; // AIDA/PADI/SSI…
  minPrice?: number;
  maxPrice?: number;
  sort?: CourseBrowseSort; // 기본 LATEST
  page?: number; // 0-base
  size?: number;
}

/** 둘러보기 카드 1칸 — 상세(CourseResponse)와 달리 카드 표면 필드만. */
export interface CourseCardResponse {
  id: number;
  title: string;
  thumbnailUrl: string | null; // 미디어 0번, 없으면 null
  kind: CourseKind;
  organizationCode: string | null; // CERTIFICATION 한정
  levels: CertLevel[]; // CERTIFICATION 한정
  isPackage: boolean;
  instructorId: number | null;
  instructorName: string | null; // 강사 nickName
  locationName: string | null; // 대표 위치 이름
  regions: Region[]; // 회차 위치들이 속한 지역 묶음(들)
  price: number;
  totalRounds: number;
  disciplineCode: string;
  createdAt?: string;
}

/**
 * GET /courses/browse 응답 — Spring PagedModel(HAL). 카드는 `_embedded.courses`(빈 결과면 키 없음),
 * 페이지 메타는 `page`. FE 는 `page.totalElements` 로 "결과 N개" 표기.
 */
export interface CourseBrowseResponse extends HalLinks {
  _embedded?: { courses: CourseCardResponse[] };
  page: { size: number; totalElements: number; totalPages: number; number: number };
}

// ── 공개 강의 상세 (course public detail) — 카드 → 상세 ──
// docs/features/course-discovery.md (정책) · docs/architecture/course.md (구현)
// GET /courses/{id}/detail — 공개(비로그인 가능). OPEN 코스만(비OPEN/없음 400, 존재 숨김).
//   강사 편집용 GET /courses/{id}(CourseResponse, 원본 ticketRef·daypart) 와 달리 venue 를 합성:
//   위치명·type·주소(area)·입장료(이용권×평일/주말 daypart fee)·장비.

/**
 * 공개 상세. CourseResponse(강사용)와 차이: ① venue 합성 — venues[]에 위치명/주소/입장료(이용권×daypart
 * fee)/장비가 풀려 옴(강사용은 ticketRef·daypart 원본만). ② instructorName 만(경력·자격·평점은 강사 프로필/
 * 리뷰 통합 후속). ③ status 없음(항상 OPEN). 입장료·장비는 회차별 변동이라 표시/안내용 — 확정 결제는 부킹.
 */
export interface CourseDetailResponse extends HalLinks {
  id: number;
  title: string;
  kind: CourseKind;
  organizationCode: string | null;
  levels: CertLevel[];
  isPackage: boolean;
  disciplineCode: string;
  totalRounds: number;
  price: number; // 수강료(원)
  description?: string;
  media: { kind: MediaKind; url: string; sortOrder: number }[];
  instructorId: number | null;
  instructorName: string | null; // 강사 nickName
  rounds: CourseDetailRoundResponse[];
  venues: CourseDetailVenueResponse[]; // 회차 가로질러 dedupe + 합성 (진행 위치 섹션)
}
export interface CourseDetailRoundResponse {
  roundKind: RoundKind;
  roundIndex: number | null; // REGULAR 1..N, EXTRA null
  description?: string;
  freeCount?: number | null; // EXTRA 전용
  perSessionPrice?: number | null; // EXTRA 전용
  venueRefIds: string[]; // 이 회차 진행 위치(들) — venues[].venueRefId 참조
}
export interface CourseDetailVenueResponse {
  venueRefId: string;
  name: string;
  type: VenueType;
  area: string | null; // 도로명주소
  tickets: CourseDetailTicketResponse[]; // 코스가 그 위치에서 쓰는 이용권 + 입장료
  equipment: VenueEquipmentResponse | null;
}
export interface CourseDetailTicketResponse {
  ticketRef: string;
  ticketName: string; // 이용권 이름 (예: "일반권 (3시간)")
  fees: { daypart: DaypartKind; fee: number }[]; // 평일/주말 입장료 — 시안의 단일 entry 아님
}

// ============================================================
// 강사 가용시간 캘린더 (availability 도메인)
// docs/architecture/availability.md · docs/features/instructor-availability.md 참고
// ============================================================
// 2층 모델: 가용시간 window(이론적 가능성) + 점유 hold(외부/수동). 5상태는 저장값 아니라 점유에서 파생.
// v1 미연동: 풍덩 수강생 점유(pending/confirmed/applicants[])는 enrollment 도메인 산물 — 항상 0/빈 배열.

/** 가용시간 생성 반복 모드 — "이 날만 / 주 / 4주". */
export type RecurrenceMode = 'ONCE' | 'WEEKLY' | 'FOUR_WEEKS';

/** 슬롯 표시 상태 — 저장값 아님, 점유에서 파생. v1 은 AVAILABLE↔EXTERNAL/FULL 만 실제로 그려짐. */
export type SlotStatus = 'AVAILABLE' | 'PENDING' | 'CONFIRMED' | 'EXTERNAL' | 'FULL';

/**
 * 가용시간 생성 — POST /instructor/availability. instructor 는 현재 계정(바디 아님).
 * ONCE = date 하루. WEEKLY/FOUR_WEEKS = dayOfWeeks 요일들을 1주/4주에 전개(기준 주부터, 과거일 제외).
 * 응답은 전개된 window 들의 CollectionModel(_embedded.windows), 201.
 */
export interface AvailabilityCreateRequest {
  mode: RecurrenceMode;
  /** 기준 날짜 (ISO "YYYY-MM-DD"). */
  date: string;
  /** WEEKLY/FOUR_WEEKS 에서 열 요일(ISO DayOfWeek 대문자). ONCE 면 무시. */
  dayOfWeeks?: Weekday[];
  /** "HH:mm" 또는 "HH:mm:ss". */
  startTime: string;
  endTime: string;
  /** 정원 — 1 이상. */
  capacity: number;
  /** 위치 토큰(선택) — "CUSTOM:<pk>"|"OFFICIAL:<sanityId>". 빈 가용시간이면 생략. */
  venueRefId?: string;
  /** 세션 라벨(선택) — "1부"/"오후". */
  sessionLabel?: string;
}

/** 가용시간 수정 — PUT /instructor/availability/{id}. 정원을 현재 점유 미만으로 낮추면 400. */
export interface AvailabilityUpdateRequest {
  date: string;
  startTime: string;
  endTime: string;
  capacity: number;
  venueRefId?: string;
  sessionLabel?: string;
}

/**
 * 점유 추가 — POST /instructor/availability/{id}/holds (201). 단일 hold 테이블에 row 1개.
 * memo 없음 = ± 빠른조정 / memo 있음 = 외부예약. filled+count > capacity 면 정원 자동 확장.
 */
export interface HoldRequest {
  /** 인원 — 1 이상. */
  count: number;
  /** 외부예약 메모(선택). 생략/공백이면 ± 빠른조정. */
  memo?: string;
}

/** 점유 hold 1건 — window 응답 안 holds[]. */
export interface HoldResponse {
  id: number;
  count: number;
  /** null=±빠른조정, 값=외부예약. */
  memo: string | null;
}

/**
 * 슬롯 안 학생 요약 — 이름·단체레벨·대여장비. **v1 은 항상 빈 배열**(enrollment 미연동, 모양만).
 * kind='external' 이면 외부 점유 행, 없으면 풍덩 학생.
 */
export interface ApplicantSummaryResponse {
  name: string;
  courseTag: string;
  gear: string[];
  kind?: 'external';
}

/**
 * 가용시간 window 응답 — 캘린더 한 블록. 목록은 `_embedded.windows`(CollectionModel).
 * - GET /instructor/availability?from&to : 범위 조회(일/주/월 뷰는 FE 가 범위로).
 * - POST /instructor/availability : 전개된 windows 컬렉션(201).
 * - GET/PUT/POST {id}(/holds...) : 단건.
 * status·filled·*Count 는 BE 가 점유에서 파생(저장값 아님). confirmedCount/pendingCount/applicants 는 v1 0/빈.
 */
export interface AvailabilityWindowResponse extends HalLinks {
  id: number;
  date: string;
  startTime: string;
  endTime: string;
  capacity: number;
  status: SlotStatus;
  /** 찬 자리 = confirmedCount + externalCount. */
  filled: number;
  /** 풍덩 확정 점유 — v1 항상 0. */
  confirmedCount: number;
  /** 외부/수동 hold 점유 합. */
  externalCount: number;
  /** 풍덩 대기 신청 — v1 항상 0. */
  pendingCount: number;
  venueRefId: string | null;
  /** venueRefId 해석 표시명(미지정/미존재면 null). */
  venueName: string | null;
  sessionLabel: string | null;
  holds: HoldResponse[];
  /** v1 빈 배열 — enrollment 가 붙으면 채워짐. */
  applicants: ApplicantSummaryResponse[];
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
