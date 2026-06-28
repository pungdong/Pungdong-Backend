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

// ── 회원탈퇴 / 복구 (account) — docs/features/account-deletion.md ──
// DELETE /account (인증) — soft delete: isDeleted=true + 현재 access token 즉시 블랙리스트.
//   유예기간(기본 30일) 동안은 PII 보유·복구 가능, 경과 후 서버가 PII 익명화(복구 불가).
//   ★ 비밀번호는 PII 아님이지만 본인확인용으로 본문에 담아 검증한다(POST 가 아니라 DELETE + body).
//   성공 = 204 No Content. 탈퇴 후 클라이언트는 토큰 폐기 + 로그인 화면으로.
//   웹 삭제 경로(Google Play 요건): 로그인 → 설정 → 회원탈퇴 가 이 엔드포인트를 호출.

/** DELETE /account 요청 본문 — 본인확인용 현재 비밀번호. */
export interface DeleteAccountRequest {
  password: string;
}

// PATCH /account/deleted-state (public) — 유예기간 내 탈퇴 계정 복구. 이메일 인증코드로 본인확인.
//   익명화가 끝났거나 유예가 지난 계정은 복구 불가(4xx). 성공 = 204 No Content.

/** PATCH /account/deleted-state 요청 본문. */
export interface RestoreAccountRequest {
  email: string;
  emailAuthCode: string;
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
   * CUSTOM = 위치 수정(전량교체)에도 보존되는 안정 UUID, OFFICIAL = Sanity 배열 _key. ★ `id`(number) 가정
   * 폐기(OFFICIAL 은 id 없음), PK 가정도 폐기 — 위치 수정 시 내부 PK 는 바뀌지만 ticketRef 는 유지된다.
   * ★ 위치 수정(PUT /venues/{id}) 시 기존 이용권은 이 ticketRef 를 그대로 다시 보내야 보존된다(신규는 생략).
   * 응답엔 항상 존재. 요청에선 optional — 생성/신규 티켓은 생략(BE 가 새로 발급).
   */
  ticketRef?: string;
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
export type SizeFormat = 'NONE' | 'SHOE_MM' | 'APPAREL_SXL';

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
  seeded: boolean; // 데모(샘플) 코스 — FE 가 "샘플용" 태그로 구분 노출. siteSettings.showSeededCourses=false 면 목록에서 빠짐
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
  seeded: boolean; // 데모(샘플) 코스. siteSettings.showSeededCourses=false 면 이 상세는 400(존재 숨김)
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
// 강사 캘린더 — coverage(예약가능시간) + session(일정) 2레이어 (availability 도메인)
// docs/architecture/availability.md · docs/features/instructor-availability.md 참고
// ============================================================
// 2레이어: coverage(예약가능시간 = 순수 시간 띠, 위치/정원/사람 없음) + session(일정 = 위치·정원·점유).
// 결합은 시간 포함뿐(session 이 coverage id 를 참조하지 않음 — coverage 는 머지/분할로 id 휘발).
//
// 핵심 동작:
// - 일정 원자 추가: POST /sessions 한 번 → coverage 확장+머지 + (위치,시간) session 생성/join + 점유.
//   (예전 create-window + add-hold 2-call 폐기.)
// - coverage 직접 편집: POST /coverage(열기, union+머지) / DELETE /coverage(닫기, subtract). 닫기가 일정을
//   가로지르면 거부(code -1014 COVERAGE_HAS_SESSION → "내부 일정 먼저 정리" 안내).
// - 범위 조회 GET ?from&to → { coverage:[...], sessions:[...] } 분리.
// - 정원: 계정 기본값(defaultCapacity) + session override. override 없으면 기본값을 라이브로 따름.
//   유효정원을 점유보다 낮춰도 확정 점유 유지(취소 없음, 추가만 차단).
//
// 학생 신청 자격: venue 운영 부가 coverage 에 **통째로 ⊆** 일 때만(부분겹침 불가). 첫 신청이 그 (위치,블록)
// session 생성, 같은 (위치,블록) 신청은 join. (enrollment 도메인 참고.)

/** 가용시간 전개 반복 모드 — "이 날만 / 주 / 4주"(coverage 열기에서 사용). */
export type RecurrenceMode = 'ONCE' | 'WEEKLY' | 'FOUR_WEEKS';

/** 일정(session) 표시 상태 — 저장값 아님, 점유에서 파생. */
export type SlotStatus = 'AVAILABLE' | 'PENDING' | 'CONFIRMED' | 'EXTERNAL' | 'FULL';

/** 강사 스케줄 설정 — GET/PATCH /instructor/availability/settings. */
export interface AvailabilitySettingsResponse {
  /** override 없는 일정들의 유효정원(신규 강사 기본 4). */
  defaultCapacity: number;
}

/**
 * 정원 값 1개 — PATCH /settings(계정 기본값) 와 PATCH /sessions/{id}/capacity(일정 override) 공유.
 * 1 이상. 일정 override 해제는 본문 없는 DELETE /sessions/{id}/capacity.
 */
export interface CapacityRequest {
  capacity: number;
}

/**
 * 예약가능시간(coverage) 열기/닫기 — POST /coverage(union) · DELETE /coverage(subtract).
 * 열기는 mode 로 반복 전개. 닫기는 단일 date+시간만(반복 무시). 응답은 영향 받은 coverage 구간[].
 */
export interface CoverageRequest {
  /** 열기 전개 모드(닫기는 무시). 생략 = ONCE. */
  mode?: RecurrenceMode;
  /** 기준 날짜 (ISO "YYYY-MM-DD"). */
  date: string;
  /** WEEKLY/FOUR_WEEKS 에서 열 요일(ISO DayOfWeek 대문자). */
  dayOfWeeks?: Weekday[];
  /** "HH:mm" 또는 "HH:mm:ss". */
  startTime: string;
  endTime: string;
}

/** 예약가능시간 한 구간 — 순수 시간 띠(위치/정원/사람 없음). 캘린더의 coverage[]. */
export interface CoverageRangeResponse {
  date: string;
  startTime: string;
  endTime: string;
}

/**
 * 일정(session) 원자 추가 — POST /instructor/availability/sessions (201).
 * 한 트랜잭션: coverage 확장+머지 → (date,위치,시간) session 생성/join → 점유(hold) 기록.
 * ※ 일정 = 점유. **count 는 1 이상 필수**(빈 일정 생성 불가; 점유 없이 시간만 열려면 POST /coverage).
 *   점유가 0 이 되면(아래 removeHold·거절·취소) 그 일정은 자동 삭제됨(session 존재 ⟺ 점유>0).
 * ※ **시간 겹침 불가**: 새 일정이 같은 날 강사의 기존 일정과 시간상 겹치면 거부(code -1015 SESSION_TIME_OVERLAP).
 *   한 강사 = 한 번에 한 세션. 맞닿는 경계(08–11 + 11–14)는 허용, 정확히 같은 (위치,시간)은 join.
 */
export interface SessionCreateRequest {
  date: string;
  startTime: string;
  endTime: string;
  /** 위치 토큰(선택) — "CUSTOM:<pk>"|"OFFICIAL:<sanityId>". 위치 없는 점유면 생략. */
  venueRefId?: string;
  /** 이용권 안정 식별자(선택, enrollment 와 동일 키). 명칭은 BE 가 해석. ticketRef 보내면 venueRefId 필수. */
  ticketRef?: string;
  /** 점유 인원 — 1 이상 필수. */
  count: number;
  /** 외부예약 메모(선택). 있으면 외부예약, 없으면 ± 빠른조정. */
  memo?: string;
  /** 이 일정 정원 override(선택). 생략하면 계정 기본값을 따름. 1 이상. */
  capacity?: number;
}

/**
 * 기존 일정에 점유 추가 — POST /instructor/availability/sessions/{id}/holds (201).
 * memo 없음 = ± 빠른조정 / memo 있음 = 외부예약. **점유가 정원을 넘기면 그 일정이 커스텀 정원(=점유)으로 확장**
 * (6명 넣으면 capacity 6·capacityOverridden true). ※ X/Y(X>Y over) 는 정원을 *낮췄을* 때만(확정 바닥).
 *
 * 점유 제거는 DELETE /sessions/{id}/holds/{holdId} — 제거 후 그 일정의 점유가 0 이면 **빈 일정이 자동 삭제되고 204**
 * (카드 제거). 남으면 200 + 갱신 session. (신청 거절/취소로 0명이 된 일정도 동일하게 사라짐 — enrollment 이력은 보존.)
 */
export interface HoldRequest {
  count: number;
  memo?: string;
}

/** 점유 hold 1건 — session 응답 안 holds[]. */
export interface HoldResponse {
  id: number;
  count: number;
  /** null=±빠른조정/제안, 값=외부예약. */
  memo: string | null;
  /** 점유 종류 — 'PROPOSAL'(강사 일정변경 제안 보장 hold, "제안중")·'EXTERNAL'(외부예약)·'QUICK'(±빠른조정). 라벨/색 구분용. */
  kind: 'PROPOSAL' | 'EXTERNAL' | 'QUICK';
}

/**
 * 슬롯 안 학생 요약 — 이름·단체/종목/레벨·대여장비. kind='external' 이면 외부 점유 행, 없으면 풍덩 학생.
 *
 * 단체·레벨은 **평탄 3종**(organizationCode/disciplineCode/levels)으로 내려온다 = BE↔Sanity 공유 키.
 * - 리스트: FE 가 평탄 포맷("AIDA · L2", levels[0]→"L2"). 단일레벨이면 그대로, 범위면 FE 가 규칙 결정.
 * - 상세: FE 가 (org, discipline, level) 로 Sanity cert 카탈로그(certificationsByOrgAndDiscipline)에서
 *   그 단체 displayName 룩업(예: PADI·SCUBA·LEVEL_1 → "Open Water Diver"). BE 는 단체별 명칭 모름(FE-direct CDN).
 * levels = 신청한 **코스의 목표 레벨**(학생 본인 자격 아님). organizationCode/disciplineCode 는 코스 단체
 * 미지정(기타) 또는 코스 없는 행이면 null.
 */
export interface ApplicantSummaryResponse {
  name: string;
  organizationCode: string | null;
  disciplineCode: string | null;
  /** 평탄 레벨 enum: 'LEVEL_1'|'LEVEL_2'|'LEVEL_3'|'LEVEL_4'|'INSTRUCTOR'|'INSTRUCTOR_TRAINER'. 범위 코스면 여러 개. */
  levels: CertLevel[];
  gear: string[];
  kind?: 'external';
}

/**
 * 일정(session) 응답 — 캘린더의 한 점유 블록(위치·정원·사람). status·filled·*Count 는 BE 가 점유에서 파생.
 * 반환처: GET/POST /sessions/{id}(/holds...), PATCH/DELETE /sessions/{id}/capacity, 캘린더의 sessions[].
 */
export interface AvailabilitySessionResponse {
  id: number;
  date: string;
  startTime: string;
  endTime: string;
  /** 유효정원 = 이 일정 override 가 있으면 그것, 없으면 강사 계정 기본값(파생). */
  capacity: number;
  /** true = 그 날만 직접 정한 값(override). FE "직접 설정" 배지·"기본값 따르기" 노출 판단용. */
  capacityOverridden: boolean;
  status: SlotStatus;
  /** 찬 자리 = confirmedCount + externalCount. */
  filled: number;
  confirmedCount: number;
  /** 외부/수동 hold 점유 합. */
  externalCount: number;
  pendingCount: number;
  venueRefId: string | null;
  /** venueRefId 해석 표시명(미지정/미존재면 null). */
  venueName: string | null;
  ticketRef: string | null;
  /** ticketRef 해석 이용권 명칭(미지정/미존재면 null). BE 가 venue 에서 해석 — FE 는 그대로 표시(라벨 생성 X). */
  sessionLabel: string | null;
  holds: HoldResponse[];
  applicants: ApplicantSummaryResponse[];
}

/**
 * 강사 캘린더 범위 조회 — GET /instructor/availability?from&to. 두 레이어 분리.
 * coverage = 머지된 예약가능시간 띠(배경), sessions = 그 위 일정 카드. FE 는 coverage 를 깔고 sessions 를 얹는다.
 */
export interface AvailabilityCalendarResponse {
  coverage: CoverageRangeResponse[];
  sessions: AvailabilitySessionResponse[];
}

// ============================================================
// 수강신청 (enrollment / booking 도메인)
// docs/architecture/enrollment.md · docs/features/booking.md 참고
// ============================================================
// 선택지 = 강사 coverage(예약가능시간) ∩ venue 운영블록 ∩ 코스 1회차 위치(교집합, BE 가 평탄 slots 로 계산).
// venue 부가 coverage 에 통째로 ⊆ 일 때만 옵션이 됨(부분겹침 불가). 강사 기존 일정과 시간 겹치는 부는 제외
// (이중부킹 방지 — submit 도 -1015 로 재검증). 첫 신청이 그 (위치,블록) session 생성, 같은 (위치,블록) 신청은
// join. 슬롯 식별자 = (date, venueRefId, blockStart, blockEnd) — windowId 없음.
// 흐름: 신청(PENDING) → 강사 수락(PAYMENT_PENDING, 결제 대기·슬롯 점유) → 결제 승인(CONFIRMED).
// 결제는 payment 섹션(POST /payments/prepare·confirm, 토스 결제위젯) 참고.

// PAYMENT_PENDING = 강사가 수락해 결제를 기다리는 상태(좌석 점유). 결제 완료 시 CONFIRMED.
export type EnrollmentStatus = 'PENDING' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED';

/**
 * 신청 옵션 — GET /enrollments/options?courseId= (authenticated). 교집합 평탄 슬롯 + 위치별 장비.
 * FE 는 slots 를 날짜→위치→시간으로 그룹핑(UX 순서는 계산 순서와 분리). 단건 응답(EntityModel, _links 동봉).
 */
export interface EnrollmentOptionsResponse {
  course: {
    id: number;
    title: string;
    disciplineCode: string;
    levels: CertLevel[];
    price: number;          // 수강료(원)
    roundLabel: string;     // "1회차 · 첫 만남"
    instructorId: number;
    instructorName: string;
  };
  /** 평탄 슬롯(날짜×위치×시간블록). 신청 시 date·venueRefId·ticketRef·blockStart·blockEnd 를 echo. */
  slots: EnrollmentSlot[];
  /** 위치별 대여 장비(venueRefId → 아이템들). */
  equipmentByVenue: Record<string, EnrollmentEquipmentOption[]>;
}

export interface EnrollmentSlot {
  date: string;             // "YYYY-MM-DD" — 슬롯 식별자의 일부
  venueRefId: string;       // "CUSTOM:<pk>" | "OFFICIAL:<sanityId>"
  venueName: string;
  venueType: VenueType;
  area: string;             // 도로명주소
  blockStart: string;       // "14:00:00"
  blockEnd: string;
  sessionLabel: string;     // "14:00–17:00"
  ticketRef: string;
  entryFee: number;         // 입장료(이용권 × 그 날짜 평일/주말 daypart fee)
  capacity: number;
  remaining: number;        // capacity − 확정 − 외부 hold
  full: boolean;
}

export interface EnrollmentEquipmentOption {
  itemRef: string;
  name: string;
  price: number;
  sizeFormat?: string | null;  // 있으면 사이즈 칩(v1 은 표시만, 캡처 후속)
  sizeOptions?: string[];
}

/** 신청 — POST /enrollments → 201 PENDING. 옵션이 준 슬롯 식별자(date,위치,블록) echo. 서버가 모두 재검증. */
export interface EnrollmentCreateRequest {
  courseId: number;
  date: string;             // "YYYY-MM-DD" — 슬롯의 date (예전 availabilityWindowId 대체)
  venueRefId: string;
  ticketRef: string;
  blockStart: string;       // "14:00:00"
  blockEnd: string;
  equipmentRefs?: string[]; // 선택 장비 itemRef
}

/** 강사 거절 — POST /instructor/enrollments/{roundId}/reject. 거절은 1회차(진입)만(진행 중은 일정변경요청). */
export interface RejectRequest {
  reason?: string;
}

/**
 * 2회차+ 일정 신청 — POST /enrollments/{enrollmentId}/rounds → 201 PENDING. 어느 회차인지는 서버가 판정
 * (다음 schedulable 회차 — 직전 정규 CONFIRMED 게이트, 정규 끝나면 EXTRA). 옵션은 GET /enrollments/{enrollmentId}/next-options.
 */
export interface RoundScheduleRequest {
  date: string;             // "YYYY-MM-DD"
  venueRefId: string;
  ticketRef: string;
  blockStart: string;       // "14:00:00"
  blockEnd: string;
  equipmentRefs?: string[];
}

/** 완전한 슬롯 — 날짜+이용권+블록. 위치는 회차에 고정(날짜만 바꾸면 daypart=이용권·입장료·블록이 달라지므로). */
export interface SlotProposal {
  date: string;             // "YYYY-MM-DD"
  ticketRef: string;
  blockStart: string;       // "14:00:00"
  blockEnd: string;
}

/**
 * 강사 일정변경 제안 옵션 — GET /instructor/enrollments/{roundId}/propose-options (강사, 내 코스 회차만).
 * 강사가 대안 슬롯을 고를 때 보는 교집합(학생 GET /enrollments/rounds/{roundId}/options 와 동일 EnrollmentOptionsResponse —
 * 슬롯 UI 재사용). 각 슬롯의 `remaining`/`full` 로 **만석 슬롯을 비활성화**해 강사가 안 고르게 한다(제안 보장 hold 도 잔여에 반영).
 * 위치는 회차 고정.
 */

/**
 * 강사 일정변경요청 — POST /instructor/enrollments/{roundId}/propose-slots. 완전한 대안 슬롯 제안(**최대 3개**).
 * 서버가 bookable + **좌석 여유**인 것만 채택하고, 채택된 슬롯마다 그 일정에 **좌석 보장 hold**(proposalTtl, 기본 6h)를 잡아
 * 학생 pick 이 만석으로 막히지 않게 한다(그 동안 다른 학생 신청은 막힘). 4개 이상이거나 전부 불가/만석이면 400.
 */
export interface ProposeSlotsRequest {
  slots: SlotProposal[]; // 최대 3
}

/**
 * 제안 슬롯 선택 — POST /enrollments/rounds/{roundId}/pick-slot → 200. 사전 수락이라 곧장 PAYMENT_PENDING.
 * 좌석은 제안 시점 hold 로 보장돼 있어 만석으로 막히지 않는다(제안이 TTL 만료로 사라졌으면 400 — status 가 WAITING 으로 돌아감).
 */
export type PickSlotRequest = SlotProposal; // proposedSlots 중 하나

/**
 * 직접 일정 수정 (제안 외 원하는 슬롯) — POST /enrollments/rounds/{roundId}/reschedule (body = RoundScheduleRequest) → 200.
 * 회차를 **제자리 변경**(취소 아님 — 회차 id 유지, 옛 슬롯은 slotHistory 적재). 날짜에 따라 위치가 다를 수 있어 위치/장비
 * 재선택 가능. 강사가 제안 안 한 슬롯이라 → status **PENDING**(강사 재수락). 결제 전(PENDING) 회차만.
 * 슬롯 후보는 GET /enrollments/rounds/{roundId}/options (1회차 옵션과 동일 EnrollmentOptionsResponse — 슬롯 UI 재사용).
 * (제안 슬롯을 그대로 고르는 빠른 길은 pick-slot → 즉시 PAYMENT_PENDING.)
 */

export interface EnrollmentEquipmentLine {
  itemRef: string;
  name: string;
  price: number;
  size: string | null; // 선택 사이즈(SHOE_MM/APPAREL_SXL), NONE 형식이면 null
}

/** 슬롯 변경 이력 1줄 — 일정 수정/제안 선택으로 슬롯이 바뀐 기록(CS 추적). 변경 없으면 빈 배열. */
export interface SlotHistoryLine {
  date: string | null;
  venueRefId: string | null;
  ticketRef: string | null;
  blockStart: string | null;
  blockEnd: string | null;
  changedAt: string | null; // ISO date-time
}

/**
 * 내 회차(학생) — 신청 직후 / GET /enrollments/mine. 목록은 `_embedded.enrollments`.
 * 다회차: `id` 는 **회차 id**(취소·결제 등 행위 단위). 수강료(tuition)는 첫 만남 회차만, 부대비용은 회차별.
 * 금액은 신청 시점 추정 스냅샷 — 권위(청구) 금액은 결제 시점 POST /payments/prepare 응답의 amount.
 */
export interface EnrollmentResponse extends HalLinks {
  id: number; // 회차 id
  courseId: number | null;
  courseTitle: string | null;
  instructorName: string | null;
  roundIndex: number | null; // REGULAR 1..N, EXTRA null
  date: string | null;
  blockStart: string | null;
  blockEnd: string | null;
  venueRefId: string | null;
  venueName: string | null;
  status: EnrollmentStatus;
  rejectionReason: string | null;
  tuition: number;
  entry: number;
  equipmentTotal: number;
  total: number;
  equipment: EnrollmentEquipmentLine[];
  createdAt: string | null;
  respondedAt: string | null;
  slotHistory: SlotHistoryLine[]; // 슬롯 변경 이력(reschedule/pick-slot 시 적재) — CS 추적
}

// ── 수강생 강의일정 hub — GET /enrollments/mine/schedule (authenticated) ──
// 내 수강을 강의(course) 단위로 묶고 회차 진행상태 파생. 2회차+ 진행·일정변경요청 반영. docs/features/student-schedule.md.
// ⚠️ 설계의 done/finalizing/completed/메모/세션채팅/환불은 BE 미구현이라 여기 없음(로드맵).
//   응답은 EntityModel(HAL) — { filters, courses, _links }.

/** 회차(=EnrollmentRound 1건) 진행상태. BE EnrollmentStatus + 일정변경 제안 파생. */
export type RoundScheduleStatus =
  | 'WAITING'       // PENDING(제안 없음) — 강사 확인 중
  | 'RESCHEDULING'  // 강사 일정변경 제안 — 학생이 proposedSlots 중 골라 pick-slot
  | 'PAYMENT_DUE'
  | 'CONFIRMED'     // 확정·미완료(진행 대기)
  | 'DONE'          // 회차 수강 완료(강사 complete 또는 세션일 +24h 자동)
  | 'REJECTED'
  | 'CANCELLED';

/** 강의(=회차들) 진행상태. 회차들에서 액션 우선으로 파생(결제대기>일정변경>수락대기>진행중>완료>취소). */
export type CourseScheduleStatus =
  | 'PAYMENT_DUE'
  | 'RESCHEDULING'
  | 'WAITING'
  | 'PROGRESS'
  | 'COMPLETED'   // 모든 정규회차 수강 완료
  | 'CANCELLED';

export interface ScheduleRound {
  roundId: number; // 회차 id — 취소·결제·일정변경 행위 단위
  roundIndex: number | null; // REGULAR 1..N, EXTRA null
  roundKind: 'REGULAR' | 'EXTRA' | null;
  status: RoundScheduleStatus;
  date: string | null;
  blockStart: string | null;
  blockEnd: string | null;
  venueRefId: string | null;
  venueName: string | null;
  /** 신청 시점 추정 총액 스냅샷(원). 권위 결제금액은 POST /payments/prepare. */
  amount: number;
  /** 강사 일정변경 제안 슬롯(RESCHEDULING). 학생이 골라 POST /enrollments/rounds/{roundId}/pick-slot. */
  proposedSlots: SlotProposal[];
  rejectionReason: string | null; // REJECTED만
  createdAt: string | null;
  respondedAt: string | null;
}

export interface ScheduleCourse {
  enrollmentId: number | null; // 수강 id — 다음 회차 신청(POST /enrollments/{enrollmentId}/rounds) 대상
  courseId: number | null;
  title: string | null;
  organizationCode: string | null; // 자격 단체 코드(Sanity)
  disciplineCode: string | null;
  levels: CertLevel[];
  instructorName: string | null;
  status: CourseScheduleStatus;
  totalRounds: number;             // 정규 회차 총 수 — FE 가 미잡힌(locked) 회차 placeholder 렌더
  nextRoundIndex: number | null;   // 지금 신청 가능한 다음 정규 회차 번호(없으면 null)
  canScheduleExtra: boolean;       // 정규 끝나 추가세션(EXTRA) 신청 가능
  rounds: ScheduleRound[];         // 잡은 회차만, roundIndex 순
}

/** 필터 칩 — id='all' 또는 CourseScheduleStatus 이름, label 한글, count. */
export interface ScheduleFilterCount {
  id: string;
  label: string;
  count: number;
}

export interface ScheduleHubResponse extends HalLinks {
  filters: ScheduleFilterCount[];
  courses: ScheduleCourse[]; // 액션 우선 정렬
}

/**
 * 강사가 받은 신청 — GET /instructor/enrollments?status= · accept/reject/propose-slots/complete 응답.
 * 목록은 `_embedded.enrollments`. status 생략 시 PENDING.
 *
 * 완료(done) 엔드포인트:
 * - POST /instructor/enrollments/{roundId}/complete → 회차 done(확정 회차만). 다음 회차 게이트가 열림. 응답=이 타입.
 * - POST /instructor/enrollments/sessions/{sessionId}/complete → 그 세션의 모든 확정 회차 일괄 done. 응답={ completed: number }.
 *   (세션일 +24h 지나면 서버가 자동 done — 강사 미마킹 fallback.)
 */
export interface InstructorEnrollmentResponse extends HalLinks {
  id: number;
  studentId: number | null;
  studentName: string | null;
  courseId: number | null;
  courseTitle: string | null;
  date: string | null;
  blockStart: string | null;
  blockEnd: string | null;
  venueRefId: string | null;
  venueName: string | null;
  status: EnrollmentStatus;
  total: number;
  equipment: EnrollmentEquipmentLine[];
  createdAt: string | null;
}

// ── 강사 수강관리 hub — GET /instructor/enrollments/hub?filter= (authenticated, 강사) ──
// 거래 단위 = 수강(수강생×강의). 학생 hub(/enrollments/mine/schedule)의 강사 거울. 신청검토·일정변경검토·마무리를
// 한 곳에서. 액션은 기존 엔드포인트 재사용(accept/reject/propose-slots/complete). 응답은 EntityModel(HAL).
// 정렬: ACTION_NEEDED → PROGRESS → COMPLETED → CANCELLED. filter = all(기본)|action|progress|completed.
// 회차 채팅·다이브로그는 미구현(별도 피처)이라 없음.

/** 거래 카드 상태(강사 시점, 회차들에서 파생). */
export type InstructorEnrollmentStatus = 'ACTION_NEEDED' | 'PROGRESS' | 'COMPLETED' | 'CANCELLED';
/** 카드 1차 액션 플래그. 없으면 null. */
export type InstructorActionFlag = 'NEW_REQUEST' | 'CHANGE_REQUEST' | 'CLOSING';
/** 회차 상태(강사 시점). */
export type InstructorRoundStatus =
  | 'WAITING'      // 신규 신청 — 수락/거절/일정변경요청
  | 'CHANGING'     // 학생이 직접 일정수정 — 검토(previousSlot 노출)
  | 'PROPOSED'     // 강사가 일정변경요청함 — 학생 선택 대기(강사 액션 아님)
  | 'PAYMENT_DUE'  // 수락됨, 학생 결제 대기
  | 'CONFIRMED'    // 확정·진행 예정
  | 'CLOSING'      // 세션 종료 — 마무리(done) 필요
  | 'DONE' | 'REJECTED' | 'CANCELLED';

export interface InstructorScheduleHubResponse extends HalLinks {
  filters: { id: string; label: string; count: number }[]; // all/action/progress/completed
  enrollments: InstructorEnrollmentCard[];
}
export interface InstructorEnrollmentCard {
  enrollmentId: number;
  student: {
    accountId: number;
    name: string;        // nickName(실명 미수집)
    initials: string;
    isNew: boolean;      // 이 강사와 과거 수강 0
    historyCount: number;
  } | null;
  courseId: number | null;
  courseTitle: string | null;
  organizationCode: string | null;
  disciplineCode: string | null;
  levels: CertLevel[];
  status: InstructorEnrollmentStatus;
  flag: InstructorActionFlag | null;
  actionLine: string | null;     // 액션 안내 한 줄
  totalRounds: number;
  rounds: InstructorRoundCard[];  // 취소 회차 제외
}
export interface InstructorRoundCard {
  roundId: number;
  roundIndex: number | null;
  roundKind: string;             // REGULAR | EXTRA
  status: InstructorRoundStatus;
  date: string | null;
  blockStart: string | null;
  blockEnd: string | null;
  venueRefId: string | null;
  venueName: string | null;
  amount: number;
  gearCount: number;
  /** CHANGING 일 때 학생이 바꾸기 전 슬롯(변경 검토 diff). 없으면 null. */
  previousSlot: { date: string | null; venueRefId: string | null; ticketRef: string | null;
                  blockStart: string | null; blockEnd: string | null } | null;
}

// ============================================================
// 결제 (payment 도메인) — 토스페이먼츠 결제위젯 v2
// docs/features/payment.md · docs/architecture/payment.md 참고
// ============================================================
// 흐름: 강사 수락(enrollment = PAYMENT_PENDING) → POST /payments/prepare(주문 생성, 위젯 구동값)
//   → FE 위젯 렌더 + requestPayment → 토스가 successUrl?paymentKey&orderId&amount 로 리다이렉트
//   → POST /payments/confirm(그 3개 값) → 서버가 금액 대조 후 토스 승인 → enrollment CONFIRMED.
// ★ amount·orderId 는 서버가 정한 값(권위) — FE 는 prepare 응답값을 그대로 위젯/confirm 에 넘긴다.
//   임의 변경 시 승인 거절(서버가 저장한 금액과 대조 + 토스도 같은 금액으로 승인). clientKey 만 공개값.
// 로컬/테스트는 stub(토스 미호출·즉시 DONE), staging/prod 만 실연동(PAYMENT_MODE=toss).

export type PaymentStatus = 'READY' | 'DONE' | 'CANCELED' | 'FAILED';

/** 결제 준비 — POST /payments/prepare (authenticated). 수락된(PAYMENT_PENDING) 회차에 대해 주문 생성. */
export interface PaymentPrepareRequest {
  enrollmentId: number; // ★ 회차 id (다회차: 결제 단위는 회차). 필드명은 호환 유지.
}

/** 위젯 구동값. amount·orderId·orderName 은 서버 권위값 — 그대로 위젯에 넘긴다. clientKey 는 공개. */
export interface PaymentPrepareResponse {
  orderId: string;      // 토스 멱등키 — 위젯/confirm 에 그대로. 내부 식별(표시 X)
  orderNo: string;      // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화). FE 의 "주문번호" 표시는 이걸로
  amount: number;       // 원 — (첫 만남이면 수강료 스냅샷) + 입장료 + 장비 + 추가세션비. 회차 단위
  orderName: string;    // "코스명 (N회차)"
  clientKey: string;    // 토스 위젯 클라이언트 키(공개값)
  customerKey: string;  // 위젯 customerKey(계정 식별, PII 아님)
}

/** 결제 승인 — POST /payments/confirm (authenticated). 위젯 성공 리다이렉트의 3개 값 그대로. */
export interface PaymentConfirmRequest {
  paymentKey: string;
  orderId: string;
  amount: number;       // 서버 권위 금액과 다르면 400 (FE 는 prepare 의 amount 를 그대로)
}

/** 승인 결과 + 그 결과로 확정된 신청 상태. 멱등 — 이미 DONE 인 주문 재승인도 200 DONE. */
export interface PaymentConfirmResponse {
  orderId: string;                // 토스 멱등키(내부). 완료 화면 "주문번호" 표시는 orderNo 사용
  orderNo: string;                // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화·가역)
  status: PaymentStatus;          // 성공 = 'DONE'
  amount: number;
  approvedAt: string | null;      // ISO-8601 offset
  enrollmentId: number | null;        // ★ 회차 id (다회차)
  enrollmentStatus: EnrollmentStatus; // 회차 상태 — 성공 후 'CONFIRMED'
}

/**
 * 수강 환불(남은 회차 환불) — POST /enrollments/{enrollmentId}/refund (authenticated). 진행 중 "환불신청".
 * 활성·미완료 회차를 전부 취소하고 회차별로 환불(토스 부분취소). 응답 = 회차별 환불 내역.
 *
 * 정책(회차당): 수강 완료(done)=0 · 미배정 회차=수강료/N(100%) · 배정취소=(수강료/N+부대)×환불율.
 * 환불율 = 당일0/전날50/2일전70/3일전+100, 신청 1h 내 100. 수강료는 1회차에 전액 냈으므로 1회차 결제주문 부분취소.
 */
export interface RefundQuote {
  total: number;          // 총 환불액(원)
  lines: RefundLine[];
}
export interface RefundLine {
  roundIndex: number | null; // 정규 회차 번호(미배정도 번호 있음), EXTRA는 null
  roundId: number | null;    // 잡힌 회차만(미배정은 null)
  amount: number;            // 이 줄 환불액 = tuitionPart + extraPart
  tuitionPart: number;       // 수강료 몫(→1회차 주문 부분취소)
  extraPart: number;         // 부대 몫(→그 회차 주문 부분취소)
  ratePct: number;           // 적용 환불율 0~100
  reason: string;            // "수강 완료" | "미배정 수강료" | "배정취소(50%)" 등
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
  PRE_LAUNCH: -1016, // 정식 런칭 전 수강신청 시도(POST /enrollments, 403). FE 는 "런칭 대기" 안내로 분기
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];

// ============================================================
// 사이트 설정 (siteSettings) — 런칭 토글
// ⚠️ BE 엔드포인트가 아니라 **Sanity 싱글톤**. FE 가 Sanity CDN 에서 직접 읽는다
//   (cert org/term 과 동일 패턴). 값 하나 바꿔 publish 하면 FE/BE 양쪽 무배포로 런칭 전환.
//   GROQ: *[_type == "siteSettings"][0]{launched, showSeededCourses, pendingTtlHours, paymentTtlHours}
//   BE 도 같은 문서를 서버사이드로 읽어 신청 차단(PRE_LAUNCH)·데모 필터·좌석 lock 만료를 강제한다.
// ============================================================

export interface SiteSettings {
  launched: boolean; // false → 전 코스 신청 차단(BE 403 PRE_LAUNCH) + "정식 런칭을 기다려주세요" 배너
  showSeededCourses: boolean; // false → 데모(seeded) 코스가 둘러보기/상세에서 빠짐(데이터는 보존)
  pendingTtlHours: number;  // BE 내부 — 신청 좌석 lock 만료(강사 무응답, 기본 24). FE 미사용
  paymentTtlHours: number;  // BE 내부 — 결제 대기 만료(미결제, 기본 12). FE 미사용
}

// ============================================================
// 법적 고지 (legal) — 이용약관/개인정보처리방침/취소·환불 전문
// ★ GET /legal/{slug} (slug = terms | privacy | refund). 공개(인증 불필요), 404 = 없음.
//   BE 가 Sanity legalDocument 를 read 토큰으로 서버사이드 조회해 제공한다.
//   (원래는 FE 가 Sanity CDN 직접 읽기였으나, 이 Sanity 프로젝트가 2026-06-11 이후 생성 문서를
//    익명에서 거부 → BE 프록시로 전환. Sanity 지원이 접근을 고치면 FE-direct 로 되돌릴 수 있음.)
//   body 는 Portable Text(블록 배열) — <PortableTextBody/> 로 렌더.
// ============================================================

export type LegalDocumentSlug = 'terms' | 'privacy' | 'refund';

/** GET /legal/{slug} 응답. */
export interface LegalDocument {
  slug: LegalDocumentSlug;
  title: string;
  body: unknown[]; // Portable Text 블록 배열 (@portabletext/react 의 PortableTextBlock[])
  version?: string; // 표시용 개정 버전 (예: '1.0')
  effectiveDate?: string; // ISO date (YYYY-MM-DD)
}
