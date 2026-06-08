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
// 강사 신청 (instructor-application 도메인)
// docs/architecture/instructor-application.md 참고
//
// 흐름(2-phase): 본인확인(stub) → 자격증 이미지 업로드 → 신청 제출.
// 단체 목록(PADI/SSI/AIDA/.../OTHER) + 폼 안내문구는 Sanity 카탈로그가 출처 —
// BE 는 선택된 organizationCode 문자열을 그대로 저장한다 (enum 아님).
// ============================================================

/**
 * POST /instructor-applications/identity-verification 요청 — 본인확인(간편인증 stub).
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

/** 본인확인 결과 — 신청 제출 시 verificationId 를 참조. */
export interface IdentityVerificationResponse extends HalLinks {
  verificationId: number;
  verified: boolean;
  realName: string;
}

/**
 * POST /instructor-applications/certificate-images 응답 (2-phase 1단계).
 * 요청은 multipart/form-data, 파트 이름 `image` (단일 파일). 여러 장이면 반복 호출.
 */
export interface CertificateImageResponse extends HalLinks {
  fileURL: string;
}

/** POST /instructor-applications (제출) · PUT /instructor-applications/me (재제출) 요청. */
export interface InstructorApplicationSubmitRequest {
  verificationId: number;
  /** Sanity 카탈로그의 단체 code. 'OTHER' 면 organizationOther 필수. */
  organizationCode: string;
  organizationOther?: string;
  /** 1단계에서 업로드한 자격증 이미지 URL 목록 (1장 이상). */
  certificateImageUrls: string[];
}

/** 신청 제출/재제출 결과. POST 는 201, PUT 은 200. */
export interface InstructorApplicationResponse extends HalLinks {
  applicationId: number;
  status: InstructorApplicationStatus;
}

/**
 * GET /instructor-applications/me — 내 신청 상태 (프로필 탭 버튼/타임라인용).
 * 미신청이면 status: 'NONE' 으로 200 (404 아님).
 */
export interface MyInstructorApplicationResponse extends HalLinks {
  status: InstructorApplicationStatus | 'NONE';
  organizationCode?: string;
  organizationOther?: string;
  certificateImageUrls?: string[];
  identityVerified: boolean;
  /** REJECTED 일 때 반려 사유. */
  rejectionReason?: string;
  submittedAt?: string;
  reviewedAt?: string;
}

/** 어드민 대기 목록 (GET /admin/instructor-applications?status=) 의 한 행. PagedModel 로 감싸짐. */
export interface InstructorApplicationSummary extends HalLinks {
  applicationId: number;
  accountId: number;
  nickName: string;
  organizationCode: string;
  organizationOther?: string;
  status: InstructorApplicationStatus;
  submittedAt: string;
}

/** GET /admin/instructor-applications/{id} — 어드민 상세 (본인확인 PII 포함, ADMIN 전용). */
export interface InstructorApplicationDetailResponse extends HalLinks {
  applicationId: number;
  accountId: number;
  email: string;
  nickName: string;
  status: InstructorApplicationStatus;
  organizationCode: string;
  organizationOther?: string;
  certificateImageUrls: string[];
  realName: string;
  birth: string;
  phoneNumber: string;
  rejectionReason?: string;
  submittedAt: string;
  reviewedAt?: string;
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
