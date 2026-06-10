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

/** GET /disciplines (공개) 항목 — CollectionModel(_embedded 배열)로 감싸짐. */
export interface DisciplineResponse {
  code: string; // "FREEDIVING" | "SCUBA" | "SWIMMING" | "SURFING" ...
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
 * GET /instructor-applications/me — 내 신청 목록 (종목별 여러 건). CollectionModel(_embedded 배열).
 * 미신청 종목은 항목 없음 → FE 가 선택된 종목으로 필터, 없으면 "신청하기" 노출.
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
 * 어드민 목록 (GET /admin/instructor-applications) 의 한 행. PagedModel 로 감싸짐.
 * `status` 쿼리 생략 시 전체, 지정 시 탭별(검수중 SUBMITTED / 통과 APPROVED / 불통과 REJECTED).
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
