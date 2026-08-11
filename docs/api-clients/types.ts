/**
 * Pungdong Backend — API Type Contract (TypeScript)
 *
 * 모바일 / 웹 클라이언트가 호출하는 REST API 의 단일 출처.
 * BE 컨트롤러 시그니처가 바뀌면 같은 PR 안에서 이 파일도 같이 갱신된다.
 *
 * 디렉토리 구성은 docs/api-clients/README.md 참고.
 * 도메인 별 의미 / 흐름은 docs/architecture/<domain>.md 참고.
 *
 * ⏰ 시간 값 = 두 종류 (docs/architecture/time-handling.md):
 *   • instant (절대시각 — *At/createdAt/verifiedAt/approvedAt/otpExpiresAt 등): ISO-8601 **offset 포함**(`2026-07-10T19:58:12Z`).
 *     `new Date(...)` 로 파싱해 **뷰어 로케일로 표시**(거래성 시각은 venue/마켓 TZ+라벨 — 후속 #173).
 *   • civil/local (슬롯·venue 운영시간 — date/startTime/endTime/blockStart/blockEnd/lectureTime): **offset 없음**(`2026-07-10`,`14:00:00`).
 *     그 장소의 벽시계라 **뷰어 TZ로 변환 금지**(그대로 표시). new Date() 절대시각 취급 금지.
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

/** 간편인증(APP) 공급자 — 향후 APP 방식용. SMS 실서비스에선 미사용(무시). */
export type IdentityProvider = 'KAKAO' | 'NAVER' | 'TOSS' | 'PASS' | 'KB' | 'PAYCO';

/** 본인확인 방식 — SMS(휴대폰 문자, 실서비스) / APP(간편인증, 향후). */
export type IdentityVerificationMethod = 'SMS' | 'APP';

/** 통신사 — SMS 발송 대상(요청 입력). 포트원 operator 와 동일 표기. */
export type Carrier = 'SKT' | 'KT' | 'LGU' | 'SKT_MVNO' | 'KT_MVNO' | 'LGU_MVNO';

/** 본인확인 레코드 생명주기. create=READY → confirm 결과 VERIFIED|FAILED. */
export type IdentityVerificationStatus = 'READY' | 'VERIFIED' | 'FAILED';

/**
 * OTP 확인 실패 사유(FE 문구 매핑). OTP_* 는 confirm 이 200 body 로 내려줌(재입력 정상 분기).
 * SMS_SEND_FAILED 는 발송/재발송 API 의 400(CommonResult) — confirm errorCode 로는 안 옴.
 */
export type IdentityVerificationErrorCode =
  | 'OTP_MISMATCH' | 'OTP_EXPIRED' | 'OTP_TOO_MANY_ATTEMPTS' | 'SMS_SEND_FAILED';

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

/**
 * POST /sign/sign-up 요청.
 * ⚠️ BE 형식 검증: `email` 유효 이메일, `password` **8자 이상 64자 이하**(위반 시 400).
 * (FE 는 8자+영문+숫자 게이팅 — BE 는 길이 하한만이라 FE 통과값은 항상 BE 통과.)
 */
export interface SignUpRequest {
  email: string;
  password: string; // 8~64자
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

/**
 * POST /sign/check/email 요청 — 가입 전 사전 체크.
 * ⚠️ BE 가 이메일 형식을 검증한다(`@Email`) — 형식 오류는 **400**(존재 여부는 200 {exists}).
 * 형식 위반 400 의 `msg` 는 "이메일 형식이 올바르지 않습니다." (그대로 표시 가능).
 */
export interface CheckEmailRequest {
  email: string; // 유효한 이메일 형식
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
  birth?: string;       // yyyyMMdd (PUT /account 수용 형식과 동일 — GET=PUT 일치)
  gender?: Gender;
  phoneNumber?: string; // 숫자만 '01012345678' (PUT /account 도 하이픈 정규화 수용)
  /** 강사면 'INSTRUCTOR' 포함(STUDENT 와 함께). FE 탭 분기는 roles.includes('INSTRUCTOR'). */
  roles: Role[];
}

/**
 * GET /account/profile (인증·본인) — 마이페이지 프로필 카드. AccountBasicInfo + 프로필 사진 + 자격 뱃지(승인된 강사 신청의 자격증).
 * 비강사는 certs=[]. ⚠️ career(경력)·rating(평점)·자격 level 은 데이터 모델 부재로 미포함 — rating 은 V2 Course 리뷰 평균으로 신설 예정.
 */
export interface AccountProfileResponse extends HalLinks {
  id: number;
  email: string;
  nickName: string;
  /** 프로필 사진 URL(미설정이면 없음/null). */
  profilePhotoUrl?: string | null;
  roles: Role[];
  certs: CertBadge[];
}

export interface CertBadge {
  disciplineCode: string;        // 종목 코드, 예 'FREEDIVING'
  organizationCode: string;      // 발급 단체 코드(Sanity 카탈로그), 예 'AIDA'·'PADI'·'OTHER'
  organizationOther?: string | null; // organizationCode==='OTHER' 일 때 직접입력 단체명
}

// ── 회원탈퇴 / 복구 (account) — docs/features/account-deletion.md ──
// DELETE /account (인증) — soft delete: isDeleted=true + 현재 access token 즉시 블랙리스트.
//   유예기간(기본 30일) 동안은 PII 보유·복구 가능, 경과 후 서버가 PII 익명화(복구 불가).
//   ★ 요청 본문 없음 — 로그인 세션(JWT) 자체가 본인 증명이라 비밀번호 재확인을 받지 않는다.
//     본인확인은 FE 의 "의도 확인"(체크+버튼)으로, 실수/악의 삭제는 soft delete + 30일 복구가 안전망.
//     (하위호환: 구버전 앱이 { password } 를 동봉해도 BE 가 무시하고 그대로 204.)
//   성공 = 204 No Content. 탈퇴 후 클라이언트는 토큰 폐기 + 로그인 화면으로.
//   웹 삭제 경로(Google Play 요건): 로그인 → 설정 → 회원탈퇴 가 이 엔드포인트를 호출.

// PATCH /account/deleted-state (public) — 유예기간 내 탈퇴 계정 복구. 이메일 인증코드로 본인확인.
//   익명화가 끝났거나 유예가 지난 계정은 복구 불가(4xx). 성공 = 204 No Content.

/**
 * PATCH /account/deleted-state 요청 본문.
 * ⚠️ BE 형식 검증: `email` 은 유효 이메일, `emailAuthCode` 는 **6자리 숫자**(위반 시 400 + 필드 메시지).
 */
export interface RestoreAccountRequest {
  email: string;         // 유효한 이메일 형식
  emailAuthCode: string; // 6자리 숫자
}

// ============================================================
// 알림 / 디바이스 토큰 (notification 도메인)
// docs/architecture/notification.md 참고
// ============================================================

/**
 * POST /me/devices 요청 — 디바이스 FCM 토큰을 현재 로그인 계정에 묶는다 (벤더 비종속 네이밍).
 * 같은 토큰 재등록 = upsert (account_id 갱신, 행 추가 X). 신분은 세션(Authorization), 바디 아님.
 * platform 은 선택 (보내면 저장). 응답 200. 해제는 DELETE /me/devices/{token} → 204.
 * 발송 시 data.notificationId 로 중복 푸시를 dedup (at-least-once). docs/features/push.md.
 */
export interface RegisterDeviceRequest {
  token: string;
  platform?: DeviceType; // 'IOS' | 'ANDROID'
}

// ============================================================
// 본인확인 (identity-verification 도메인) — 계정 공유 자산
// docs/architecture/identity-verification.md · docs/features/identity-verification.md 참고
//
// 휴대폰 SMS 본인인증 (포트원 REST v2 / 다날) — 서버가 REST 로 진행, FE 는 자체 UI 로
// 전화번호+OTP 입력 화면을 만든다. SMS 2단계:
//   1) POST /identity-verifications          생성 + 문자 발송(결합) → status:READY
//   2) POST /identity-verifications/{id}/confirm  { otp }           → VERIFIED | FAILED
//   재발송: POST /identity-verifications/{id}/resend
// 수강/강사 어느 플로우든 같은 레코드를 만들고, GET /me 로 기존 VERIFIED 를 확인해 skip·재사용.
// 로컬/테스트는 stub(문자 미발송, 매직 OTP "000000"=성공). 실 다날은 CPID 개통 후 mode=real.
// ============================================================

/**
 * POST /identity-verifications 요청 — 생성 + SMS 발송(결합).
 * PII(실명·생년월일·휴대폰)는 POST body 로만 전송 (URL/쿼리 금지).
 * method='SMS'(기본) 면 carrier(통신사) 필수. provider 는 향후 APP 방식 전용(SMS 에선 무시).
 *
 * ⚠️ **BE 가 형식을 검증한다(400)** — FE 검증은 UX 용이지 방어선이 아니다(콘솔·직접 호출로 우회 가능).
 * 형식이 깨진 요청은 SMS 미발송·레코드 미생성·쿨다운 미소모. 단, **실존/해지/명의 판정은 다날 몫** —
 * 형식이 맞아도 발송이 실패할 수 있다(현재 SMS_SEND_FAILED 400 으로 뭉쳐 옴, CPID 개통 후 세분화 예정).
 *
 * 📩 **400 응답의 `msg` = 어느 필드가 왜 틀렸는지**(예: "휴대폰 번호 형식이 올바르지 않습니다.").
 * 형식 검증 메시지는 사용자용 한국어라 **FE 가 그대로 표시**하면 된다. (형식 규칙은 공개 계약이라
 * 노출해도 보안상 안전 — oracle 아님. 로그인의 "뭐가 틀렸는지 숨김"과 성격이 다르다.)
 */
export interface IdentityVerificationRequest {
  /** 최대 50자. */
  realName: string;
  /** yyyyMMdd. 하이픈 표기('1998-09-14')도 서버가 정규화해 받는다. 형식 위반 → 400. */
  birth: string;
  gender: Gender;
  /**
   * 한국 휴대폰 번호. 하이픈 유무 무관('010-1234-5678' / '01012345678') — 서버가 숫자만 남긴다.
   * 규칙: `010` + 구 2G `011/016/017/018/019`, 총 10~11자리. `013/014/015`(IoT·부가서비스)는 SMS
   * 수신 불가라 거부. 위반 → 400. (**KR 전용** — 국가 확장 시 본인확인기관이 통째로 갈린다)
   */
  phoneNumber: string;
  /** 통신사 — SMS 발송 대상. SMS 필수. */
  carrier: Carrier;
  /** 생략 시 서버가 'SMS' 로 간주. */
  method?: IdentityVerificationMethod;
  /** APP 방식 전용. SMS 에선 불필요. */
  provider?: IdentityProvider;
  /** 필수 약관 전체 동의(개인정보 수집·제3자(다날) 제공). false 면 400. */
  agreedRequiredTerms: boolean;
}

/**
 * POST /identity-verifications 응답(201) · POST /{id}/resend 응답(200).
 * **discriminated union** — 발송 성공(`IdentityVerificationSent`) 또는 쿨다운(`IdentityVerificationCooldown`).
 * BE 가 `@JsonInclude(NON_NULL)` 로 각 형태에 없는 필드를 **JSON 에서 아예 뺀다** → 타입도 union 이라
 * 필드 누락이 컴파일 타임에 잡힌다(성공 타입만 믿고 쿨다운에서 otpExpiresInSeconds 접근 → 컴파일 에러).
 *
 * 분기: `res.status === 'READY'`(쿨다운엔 status 없음) 또는 `'retryAfterSeconds' in res`.
 */
export type IdentityVerificationResponse =
  | IdentityVerificationSent
  | IdentityVerificationCooldown;

/**
 * 발송 성공 — SMS 발송됨, status='READY'. confirm 은 verificationId 로 호출.
 * ⚠️ 카운트다운은 반드시 **otpExpiresInSeconds** 로(서버 계산 잔여 초, TZ·기기 시계 무관).
 * TTL 은 서버 정책(stub 180s / real 300s) — 하드코딩 금지.
 */
export interface IdentityVerificationSent extends HalLinks {
  status: 'READY';
  verificationId: number;
  /**
   * OTP 잔여 초(발송 시점). 카운트다운의 단일 출처 — 이것만 쓰면 시계/TZ 버그 원천 차단.
   * ★ **영구 필드 — UTC+오프셋 글로벌화 이후에도 유지(제거 금지).** 리팩토링 후 FE 는 절대시각
   * (오프셋 포함 otpExpiresAt)을 primary 앵커로 승격하되, **이 값을 sanity check/fallback 으로 남긴다**:
   * 기기 시계가 서버와 크게 어긋나면(예: 시계 빠른 기기가 아직 유효한 코드를 조기 잠금 — 서버가 구제 못 하는
   * 실패) 잔여 초로 폴백. 그래서 절대시각으로 옮겨가도 BE 는 이 필드를 계속 내린다.
   */
  otpExpiresInSeconds: number;
  /** OTP 유효기한 절대시각(서버 KST wall-clock). 표시/디버그용 — 카운트다운엔 쓰지 말 것(오프셋 없음). */
  otpExpiresAt: string;
  /**
   * 다음 발송/재발송까지 남은 초(방금 잡은 쿨다운 창 = 서버 정책값, 0=쿨다운 없음).
   * FE 는 발송 직후 이걸로 재전송 버튼을 **미리 비활성 + 카운트다운**("눌러봐야 아는 버튼" 방지).
   * 쿨다운 응답의 retryAfterSeconds 와 같은 개념(지금부터 쿨다운 잔여)을 성공 상태에서 표현 — 하드코딩 금지.
   */
  resendAvailableInSeconds: number;
}

/**
 * 발송 쿨다운 — SMS 미발송(계정당 발송 최소 간격, 기본 30s 초과). 이 초만큼 뒤 재시도.
 * 성공 필드(status·verificationId·타이밍) 없음 — 접근하면 컴파일 에러(그게 이 union 의 목적).
 * create 쿨다운은 verificationId 도 없어 confirm/resend 불가 → 이 형태를 만나면 "N초 후 재시도" UI 만.
 */
export interface IdentityVerificationCooldown {
  retryAfterSeconds: number;
}

/** POST /identity-verifications/{id}/confirm 요청 — OTP 확인. */
export interface ConfirmIdentityVerificationRequest {
  /**
   * 6자리 숫자. 아니면 **400**(200 FAILED 아님) — 시도 횟수(5회 한도)를 소모하지 않는다.
   * FE 는 6자리를 채우기 전엔 제출 버튼을 막아, 사용자가 이 400 을 볼 일이 없게 할 것.
   */
  otp: string;
}

/**
 * POST /identity-verifications/{id}/confirm 응답(200) — 성공/실패 모두 200(OTP 재입력은 정상 분기).
 * status='VERIFIED' → realName 세팅, verificationId 를 강사 신청/결제에 재사용.
 * status='FAILED'   → errorCode(OTP_MISMATCH|OTP_EXPIRED|OTP_TOO_MANY_ATTEMPTS)로 문구 매핑.
 * (문자 발송 실패 SMS_SEND_FAILED 는 여기가 아니라 create/resend 의 400.)
 */
export interface ConfirmIdentityVerificationResponse extends HalLinks {
  verificationId: number;
  status: IdentityVerificationStatus; // 'VERIFIED' | 'FAILED'
  realName?: string;
  errorCode?: IdentityVerificationErrorCode;
}

/**
 * GET /identity-verifications/me — 내 최신 VERIFIED 상태 (계정 공유).
 * 미인증(또는 READY/FAILED 만 존재)도 200 `{ verified: false }` (404 아님).
 * verified 면 verificationId 를 강사 신청/결제에 재사용. verifiedAt 은 노출만, 만료 판단 안 함(무만료).
 * provider 는 SMS 방식이면 null.
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
 * POST /instructor-applications/certificate-images 응답 (2-phase 1단계). 비공개 강사신청 이미지 업로드.
 * 요청은 multipart/form-data, 파트 이름 `image` (단일 파일). 여러 장이면 반복 호출.
 * 자격증뿐 아니라 (선택) **보험 이미지**도 이 엔드포인트로 올려 fileKey 를 받아 submit 의 insuranceFileKey 에 넣는다.
 */
export interface CertificateImageResponse extends HalLinks {
  // 저장 참조 key (공개 URL 아님 — 자격증은 개인정보라 비공개 버킷에 올라감).
  // 제출 JSON 의 certificates[].fileKey 로 이 값을 그대로 돌려보낸다.
  // 미리보기는 방금 고른 로컬 파일 blob 으로(이 응답은 표시용 URL 이 아님).
  fileKey: string;
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
  fileKey: string; // 저장 참조 key. 업로드 응답의 fileKey 를 제출 시 그대로 보냄(라운드트립).
  viewUrl?: string; // 조회 응답에만 — 표시용 한시 presigned URL(짧은 TTL). 제출 시 미포함.
}

/** POST /instructor-applications (제출) · PUT /instructor-applications/me (재제출) 요청. */
export interface InstructorApplicationSubmitRequest {
  /** GET /disciplines 의 code. */
  disciplineCode: string;
  /** GET /identity-verifications/me 에서 재사용 (없으면 POST /identity-verifications 로 생성). */
  verificationId: number;
  /** 자격증 목록(단체+이미지). 자격증 필요 종목은 1건 이상, 불필요 종목은 생략. */
  certificates?: ApplicationCertificate[];
  /** (선택) 다이빙보험 이미지 — 업로드(POST /certificate-images) 응답의 fileKey. 옵셔널. 종목 신청별. */
  insuranceFileKey?: string;
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
  /** (선택) 보험 저장 참조 key — 재제출 시 그대로 전송(라운드트립). 없으면 미포함. */
  insuranceFileKey?: string;
  /** (선택) 보험 이미지 표시용 한시 presigned URL(조회 시점 발급). 없으면 미포함. */
  insuranceViewUrl?: string;
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
  /** (선택) 보험 저장 참조 key. 없으면 미포함. */
  insuranceFileKey?: string;
  /** (선택) 보험 이미지 표시용 한시 presigned URL(조회 시점 발급). 없으면 미포함. */
  insuranceViewUrl?: string;
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
  fileKey: string; // 업로드 응답의 fileKey
}

/** POST /admin/instructor-applications/{id}/reject 요청. */
export interface RejectInstructorApplicationRequest {
  reason: string;
}

/**
 * GET /instructors/public (비로그인) — 수강생 둘러보기 홈 "풍덩 공식 강사" 카드. 승인(APPROVED) 신청을 가진 실가입 강사만.
 * PagedModel — 배열은 `_embedded.instructors`, 페이지 메타는 `page`. `page.totalElements` → "N명" + 아바타 일부 + "+N" 파생.
 * 공개 필드만(PII 없음 — 이름/이메일/연락처 미포함). Pageable 쿼리(`?page=&size=`) 지원, 기본 정렬은 최근 가입(id desc).
 */
export interface PublicInstructorResponse {
  id: number;
  nickName: string;
  avatarUrl?: string | null;   // 프로필 사진(미설정이면 없음/null → FE 기본 아바타)
  disciplineCodes: string[];   // 승인 종목들, 예 ['FREEDIVING','SCUBA']
}

// ── 브랜딩 페이지 / 내 프로필 (branding) — docs/features/account-branding.md ──
// 강사에겐 "브랜딩 페이지", 일반 유저에겐 "내 프로필". 같은 스키마·같은 엔드포인트를 쓰고 응답 필드만 role 로 갈린다.
// ⚠️ 공개 URL 식별자는 id 가 아니라 nickName. FE 는 encodeURIComponent 로 인코딩해 보낸다.
//    한글·공백·'.'·'+' 는 정상 동작하지만, '/'·'\' 가 든 닉네임은 Spring Security 방화벽이 거부해 열리지 않는다.

export type Medal = 'GOLD' | 'SILVER' | 'BRONZE';

/** 프리다이빙 경기 세부종목. ⚠️ 종목(Discipline: FREEDIVING·SCUBA·MERMAID)과 다른 축이다. */
export type RecordEventCode = 'CWT' | 'FIM' | 'CNF' | 'DYN' | 'DNF' | 'STA';

export interface BrandingRecord {
  medal: Medal;
  eventCode: RecordEventCode;
  /** 단위가 종목마다 달라(깊이 '-75m' / 거리 '180m' / 시간 '6:24') 문자열 원문. 숫자로 파싱하지 말 것. */
  value: string;
}

/** 승인된 강사 신청에서 파생된 자격 뱃지. 자유입력 자격은 폐기됨(추후 '자격증 관리' 피처가 대체). */
export interface BrandingCertBadge {
  disciplineCode: string;
  organizationCode: string;
  organizationOther?: string | null;
}

/**
 * GET /instructors/{nickName} (비로그인) — 공개 브랜딩 페이지 / 내 프로필.
 * 없는 닉네임·미발행·탈퇴는 모두 400(존재 숨김) — 이 레포는 404 를 쓰지 않는다.
 *
 * ⚠️ 필드가 "없다"는 두 가지 뜻이다:
 *   - tagline·bio·locationLabel 은 유저가 지우면 null 이 명시적으로 내려온다
 *   - disciplineCodes·certs 는 강사가 아니면 키 자체가 빠진다(undefined)
 */
export interface BrandingProfileResponse extends HalLinks {
  nickName: string;
  avatarUrl?: string | null;
  tagline: string | null;
  bio: string | null;
  locationLabel: string | null;
  /** 인증마크(공식 강사) 렌더 여부 = 승인된 강사 신청 보유. */
  isInstructor: boolean;
  disciplineCodes?: string[];        // 강사만
  certs?: BrandingCertBadge[];       // 강사만
  records: BrandingRecord[];         // 없으면 [] → 섹션 숨김
  stats: BrandingStats;
  products?: BrandingProducts;       // 강사만
}

/** 전부 파생값(카운터를 저장하지 않는다). 미구현·비대상 필드는 키가 없다. */
export interface BrandingStats {
  /** 게시물 수 — 게시물 도메인 붙는 후속 PR에서 채워진다. 그동안 FE는 오너 목록의 page.totalElements 사용. */
  posts?: number;
  /** 누적 수강생 수 — 강사만. 확정(CONFIRMED) 회차를 가진 distinct 학생 수. */
  students?: number;
}

/** CTA 뱃지용. 투어(tours)는 CourseKind에 TOUR가 없어 이번 범위 밖(D4). */
export interface BrandingProducts {
  /** 공개(OPEN) 강의 수. 데모 시드 취급은 SiteSettings.showSeededCourses를 따른다. */
  lessons?: number;
}

/**
 * GET /branding/me (인증) — 오너 편집용 원본. PATCH /branding/me · PATCH /branding/me/publish 응답도 같은 형태다.
 * 아직 만들지 않았으면 200 { exists: false } 만 온다 — 조회는 생성하지 않는다(생성은 첫 쓰기가 한다).
 *
 * ⚠️ 공개 응답(BrandingProfileResponse)의 필드를 그대로 포함한다 — 오너 뷰가 퍼블릭과 같은 명함이라서.
 *    그래서 오너 화면을 이 호출 하나로 그릴 수 있고, 쓰기 응답에서 nickName을 얻어 캐시 무효화에 쓸 수 있다.
 *    특히 stats.students는 공개 응답이 미발행 시 400이라 여기서만 얻을 수 있다.
 */
export interface MyBrandingResponse extends HalLinks {
  exists: boolean;
  isPublished?: boolean;
  nickName?: string;
  avatarUrl?: string | null;
  tagline?: string | null;
  bio?: string | null;
  locationLabel?: string | null;
  isInstructor?: boolean;
  disciplineCodes?: string[];        // 강사만
  certs?: BrandingCertBadge[];       // 강사만
  stats?: BrandingStats;
  products?: BrandingProducts;       // 강사만
  records?: BrandingRecord[];
  /** 강사 신청 이력이 있을 때만. 없으면 키 자체가 빠지고 FE 는 검수 배너를 렌더하지 않는다. */
  reviewStatus?: InstructorApplicationStatus;
  /** APPROVED 일 때만. 웹 검수 배너가 "검수 통과 2026.05.13" 으로 렌더. UTC ISO-8601. */
  approvedAt?: string;
}

/**
 * PATCH /branding/me (인증) — 부분 수정. 미생성이면 이 호출이 생성한다(upsert).
 * ⚠️ 키 생략 = 변경 없음 / 명시적 null = 그 값 비우기. 둘은 다른 뜻이다.
 */
export interface BrandingUpdateRequest {
  tagline?: string | null;        // 최대 60자
  bio?: string | null;            // 최대 500자
  locationLabel?: string | null;  // 최대 60자
}

/**
 * PUT /branding/me/records (인증) — 공식 기록 **스냅샷 교체**. 미생성이면 이 호출이 프로필을 만든다(upsert).
 * ⚠️ 보낸 배열이 곧 최종 상태다 — 빈 배열을 보내면 기록이 전부 지워진다. 부분 추가/삭제 API가 아니다.
 * ⚠️ sortOrder를 보내지 않는다 — 배열 순서가 곧 표시 순서다.
 * 응답은 MyBrandingResponse.
 */
export interface BrandingRecordsUpdateRequest {
  records: BrandingRecord[];   // 최대 12개, value는 최대 16자
}

// ── 브랜딩 게시물 ──
export type BrandingMediaKind = 'PHOTO' | 'VIDEO';   // VIDEO는 스키마 자리만 예약(업로드 거부)

/**
 * GET /instructors/{nickName}/posts (비로그인) · GET /branding/me/posts (인증)
 * PagedModel — 배열은 `_embedded.posts`, 메타는 `page`. 정렬은 서버 고정(고정 먼저 → 최신순), size 상한 50.
 * ⚠️ 공개 목록은 숨긴 글을 빼고, 오너 목록은 포함하며 `hidden`이 실린다.
 */
export interface BrandingPostCard {
  id: number;
  thumbnailUrl?: string | null;   // 첫 장
  mediaCount: number;             // 2 이상이면 캐로셀 뱃지
  pinned: boolean;
  hidden?: boolean;               // 오너 목록에만
}

/** GET /branding-posts/{postId} (비로그인) — 미발행 프로필의 글·숨긴 글은 400(존재 숨김). */
export interface BrandingPostDetail extends HalLinks {
  id: number;
  author: { nickName: string; avatarUrl?: string | null };
  media: { kind: BrandingMediaKind; url: string; sortOrder: number }[];
  caption?: string | null;
  tags: string[];
  locationLabel?: string | null;
  /** UTC ISO-8601. "하루 전" 같은 상대시간은 FE가 만든다 — BE는 문자열을 만들지 않는다. */
  createdAt: string;
  pinned: boolean;
  /** 강사가 연결했을 때만. DRAFT(미공개)·삭제된 코스면 키 자체가 없다. */
  linkedCourse?: {
    id: number;
    title: string;
    thumbnailUrl?: string | null;
    price: number;
    status: 'OPEN' | 'CLOSED';
  };
}

/**
 * POST /branding/me/posts · PUT /branding/me/posts/{id} (인증)
 * ⚠️ 수정도 **스냅샷 교체**다 — 보낸 mediaUrls/tags가 최종 상태가 된다.
 * ⚠️ mediaUrls는 업로드(POST /branding-images)로 받은 우리 CDN URL만 허용. 배열 순서 = 표시 순서, 0번이 썸네일.
 */
export interface BrandingPostRequest {
  mediaUrls: string[];      // 1~10장
  caption?: string;         // 최대 2000자
  tags?: string[];          // 최대 10개, 각 30자
  locationLabel?: string;   // 최대 60자
  linkedCourseId?: number;  // 내 강의만. 남의 강의면 400
}

/** PATCH /branding/me/posts/{id}/pin */
export interface BrandingPostPinRequest { pinned: boolean }

/** PATCH /branding/me/posts/{id}/visibility — 삭제와 다르다(되돌릴 수 있고 공개 경로에서만 빠진다). */
export interface BrandingPostVisibilityRequest { hidden: boolean }

/** POST /branding-images (인증, multipart 파트명 `image`) — course-images와 동일 패턴. */
export interface BrandingImageUploadResponse extends HalLinks { fileURL: string }

/** PATCH /branding/me/publish (인증) — 승인 게이트 없음. 일반 유저도 발행할 수 있다. */
export interface BrandingPublishRequest {
  published: boolean;
}

// ── 커뮤니티 (community) — docs/features/community.md ──
// 글·사진·댓글·좋아요. 카테고리 4종. 강사 작성자를 시각적으로 구분해 프로필 → 강의 전환을 유도한다.
//
// ⚠️ 게시물 테이블은 브랜딩과 **공유**한다. 노출은 **브랜딩 → 커뮤니티 단방향**:
//    - 브랜딩(POST /branding/me/posts)에 올린 글 → 프로필 그리드 + 커뮤니티 피드 **둘 다**
//    - 커뮤니티(POST /community/posts)에 올린 글 → 피드에만. 프로필 그리드엔 안 나온다
//    브랜딩은 "남기고 싶은 하이라이트", 커뮤니티는 "오늘의 흐름" 이라 방향이 한쪽이다.

export type CommunityCategory = 'TOUR' | 'TRAINING' | 'MATCH' | 'QNA';
// 라벨은 클라이언트 소유(투어 자랑/트레이닝/같이가요/궁금해요). BE 는 코드만 준다.

/**
 * 게시물에 연결된 강의 미니카드. BrandingPostDetail.linkedCourse 와 **같은 형태**다(BE 도 같은 DTO).
 * ⚠️ 투어는 없다 — CourseKind 에 TOUR 가 없어서 강의만 연결된다.
 */
export interface LinkedCourse {
  id: number;
  title: string;
  thumbnailUrl?: string | null;
  price: number;
  status: 'OPEN' | 'CLOSED';
}

/** 피드·상세·댓글에 공통으로 실리는 작성자. 강사 강조 UI(링+✓+"강사 · 강의 N")의 유일한 소스. */
export interface CommunityAuthor {
  /** 공개 프로필 진입 키 — GET /instructors/{nickName} 에 그대로 쓴다. */
  nickName: string;
  avatarUrl?: string | null;
  /** 항상 온다(생략 아님) — 카드마다 분기하는 값이라 없으면 "안 온 것"과 구분이 안 된다. */
  isInstructor: boolean;
  /** 강사만. 일반 유저는 **키 자체가 없다** — 0 을 주면 "강의 0개인 강사" 로 읽힌다. */
  lessonCount?: number;
}

/**
 * 같이가요 모집 정보 — MATCH 카테고리 글에만.
 * ⚠️ 일정은 civil time(오프셋 없음) — 다이브 포인트의 벽시계라 뷰어 TZ 로 변환 금지.
 * ⚠️ 참여자 수가 없다. "참여 신청" 은 만들지 않기로 확정(신청류는 기존 수강신청 플로우).
 *    모집 칸은 capacity 만으로 "N명 모집" 렌더.
 */
export interface CommunityMatch {
  meetDate: string;            // "2026-05-24"
  meetTime?: string | null;    // "09:00:00"
  capacity: number;
  levelLabel?: string | null;  // "AOWD 이상 · 보트다이빙 경험"
  /** meetDate >= today. 뱃지용이 아니라 **지난 모집글을 흐리게** 처리하는 용도. */
  open: boolean;
}

/**
 * GET /community/posts (비로그인 가능) — 피드.
 * PagedModel — 배열은 `_embedded.posts`(빈 결과면 키 없음), 메타는 `page`.
 * 쿼리: `?category=&sort=&authorType=&bookmarkedByMe=&page=&size=` · size 상한 50.
 * `sort` 는 **`CommunityFeedSort`(LATEST 기본 · POPULAR) 둘뿐** — 그 외 값은 400. 메인 피드의 최신/인기 pill 이 이걸 쓴다.
 * `authorType='INSTRUCTOR'` 는 "강사 글" pill — **승인된 강사**가 쓴 글만(작성자 칩 `isInstructor` 와 같은 축).
 *   생략 = 전체. 인기순·같이가요 피드에도 함께 걸린다.
 * ⚠️ `category=MATCH` 면 `sort` 와 무관하게 **일정 임박순**으로 자동 전환된다(정렬 pill 을 노출하지 않는 화면이라 서버 기본 동작으로 처리).
 * ⚠️ `bookmarkedByMe=true` 는 인증 필요 — 비로그인이면 에러가 아니라 **빈 페이지**.
 */
export interface CommunityPostCard {
  id: number;
  /** 브랜딩에서 올라온 글은 카테고리가 없을 수 있다 → 그런 글은 "전체" 피드에만 뜬다. */
  category?: CommunityCategory;
  /** 브랜딩발 글은 제목이 없을 수 있다. 카드가 제목을 조건부 렌더하므로 없어도 깨지지 않는다. */
  title?: string;
  bodyExcerpt?: string | null;   // 앞 200자 — FE 가 CSS 3줄 클램프
  author: CommunityAuthor;
  thumbnailUrls: string[];       // 앞 3장만 (카드가 3장 + "+N" 구조)
  mediaCount: number;
  locationLabel?: string;
  createdAt: string;             // UTC ISO-8601. "15분 전" 은 FE 가 만든다
  likeCount: number; commentCount: number; bookmarkCount: number;
  likedByMe: boolean; bookmarkedByMe: boolean;
  /** 공개 피드에선 항상 false. **오너가 자기 글을 볼 때만** true — 숨김 배지·토글 상태용. */
  hidden: boolean;
  linkedCourse?: LinkedCourse;   // 강사 글만. DRAFT·삭제 코스면 키 없음
  match?: CommunityMatch;        // MATCH 만
}

/** GET /community/posts/{postId} (비로그인 가능). 숨김·미노출은 400(존재 숨김) — 단 오너 본인은 열린다. */
export interface CommunityPostDetail extends HalLinks {
  id: number;
  category?: CommunityCategory;
  title?: string;
  body?: string | null;
  author: CommunityAuthor;
  media: { url: string; sortOrder: number }[];
  /** 카드·상세 어디에도 렌더되지 않는다 — **수정 폼 프리필용**. */
  tags: string[];
  locationLabel?: string;
  createdAt: string;
  updatedAt: string;
  likeCount: number; commentCount: number; bookmarkCount: number;
  likedByMe: boolean; bookmarkedByMe: boolean;
  hidden: boolean;
  /** 내 글이면 "더보기" 에 수정·삭제 노출. 카드엔 메뉴가 없어 불필요. */
  mine: boolean;
  linkedCourse?: LinkedCourse;
  match?: CommunityMatch;
}

/**
 * POST /community/posts · PUT /community/posts/{postId} (인증)
 * ⚠️ 수정도 **스냅샷 교체** — 보낸 mediaUrls/tags 가 최종 상태다.
 * ⚠️ mediaUrls 는 업로드(POST /branding-images)로 받은 우리 CDN URL 만. 배열 순서 = 표시 순서.
 */
export interface CommunityPostRequest {
  category: CommunityCategory;   // 필수
  title: string;                 // 필수 2~100자
  body?: string;                 // 최대 5000자
  mediaUrls?: string[];          // 최대 10장. 사진 없는 글 허용(궁금해요·같이가요)
  tags?: string[];               // 최대 5개, 각 30자
  locationLabel?: string;        // 최대 60자
  /**
   * 내 코스만 — 남의 코스는 400(존재 숨김). ⚠️ category==='MATCH' 면 **연결 불가**(영리활동 금지 가드) → 400
   *
   * DRAFT 코스도 **요청은 통과**하고 공개 응답에서 linkedCourse 키만 생략된다(OPEN 으로 바꾸면 그때 나타남).
   * "준비 중인 강의를 미리 걸어두고 공개되면 뜨게" 가 유효한 사용이라 서버는 막지 않는다 —
   * 대신 **FE 가 선택 시점과 선택 후 두 번 고지**한다(시트/드롭다운은 닫히면 안 읽히므로 폼에도 남긴다).
   * `CLOSED` 는 거르지도 고지하지도 않는다 — 응답에 그대로 오고 미니카드가 마감으로 그린다.
   */
  linkedCourseId?: number;
  /** category==='MATCH' 일 때 필수. */
  match?: {
    meetDate: string;            // 오늘 이후
    meetTime?: string;
    capacity: number;            // 2~20
    levelLabel: string;          // 필수, 최대 60자
  };
}

/** PATCH /community/posts/{postId}/visibility — 삭제와 다르다(되돌릴 수 있고 공개 경로에서만 빠진다). */
export interface CommunityPostVisibilityRequest { hidden: boolean }

// 사진 업로드는 **기존 POST /branding-images 를 그대로 쓴다**(같은 공개 버킷·같은 검증). 신규 엔드포인트 없음.

/** 피드 정렬. `MATCH` 진입 시엔 이 값과 무관하게 서버가 **일정 임박순**으로 자동 전환한다. */
export type CommunityFeedSort = 'LATEST' | 'POPULAR';

/**
 * 작성자 유형 필터 — 웹 피드의 "강사 글" pill. 값이 하나뿐인 건 필터가 하나뿐이기 때문이다
 * ("일반 유저 글만" 은 화면에 없어서 만들지 않았다 — 죽은 값을 남기지 않는다).
 */
export type CommunityAuthorType = 'INSTRUCTOR';
// POPULAR = **최근 7일 안에서** 좋아요 많은 순. 기간을 자르지 않으면 오래된 인기글이 상단을 영구 점유한다.

/**
 * POST·DELETE /community/posts/{postId}/like · /bookmark (인증)
 * ⚠️ **멱등**이다 — 같은 요청을 두 번 보내도 결과가 같다. 낙관적 업데이트 후 이 값으로 덮어쓰면 항상 수렴한다.
 * ⚠️ 숨김·없는 글에는 반응할 수 없다 → 400(존재 숨김).
 */
export interface ReactionResponse {
  count: number;    // 갱신된 총 개수
  active: boolean;  // 내 상태. POST 뒤 true, DELETE 뒤 false
}

/** GET /community/categories (비로그인 가능) — 4-up 그리드. 배열은 `_embedded.categories`. */
export interface CommunityCategoryCount {
  category: CommunityCategory;
  /** 최근 7일 글 수. **4종이 항상 전부 온다** — 0개인 카테고리도 칸은 그려져야 하므로 0 으로 채워 준다. */
  weeklyPostCount: number;
}
// HOT 뱃지 임계값(>50)은 클라이언트 상수다 — 서버는 숫자만 준다.

/** GET /community/tags/popular?limit=8 (비로그인 가능) — 배열은 `_embedded.tags`. 건수 내림차순. */
export interface PopularTag {
  tag: string;   // '#' 없는 순수 문자열. 표시용 '#' 은 클라이언트가 붙인다
  count: number;
}

// GET /community/posts/{postId}/related?limit=3 (비로그인 가능) — 배열은 `_embedded.posts`(CommunityPostCard).
// 같은 카테고리·자기 제외·최신순. ⚠️ 카테고리가 없는 글(브랜딩발)은 **빈 배열** — 묶을 축이 없어서다.

/**
 * GET /community/posts/{postId}/comments (비로그인 가능) — 배열은 `_embedded.comments`.
 * ⚠️ **페이지네이션이 없다** — CollectionModel 이라 `page` 키가 없고 스레드 전체가 한 번에 온다.
 *    (1-depth 트리를 나눠 조회하면 그 사이에 달린 댓글이 유실된다. 켜게 되면 최상위만 페이징하고
 *     대댓글은 계속 인라인이며, 그때 응답이 PagedModel 로 바뀐다.)
 * ⚠️ 정렬 파라미터가 **없다**. 서버가 `createdAt ASC` 로 고정한다(스레드는 위→아래로 흐른다).
 *    디자인의 "최신순 ▾" 은 다른 옵션이 정의된 곳이 없어 정적 라벨로 처리한다.
 * ⚠️ **1-depth 고정** — `replies` 안의 항목은 항상 빈 `replies` 를 갖는다.
 */
export interface CommunityComment {
  id: number;
  author: CommunityAuthor;
  /** 삭제된 댓글이면 원문 대신 "삭제된 댓글입니다." 가 온다 — `deleted` 로 구분한다. */
  body: string;
  /**
   * 삭제 표식. **대댓글이 달린 댓글만 자리가 남는다**(스레드가 끊기면 안 되므로).
   * 대댓글이 없는 댓글은 완전히 사라져 목록에 아예 없다.
   */
  deleted: boolean;
  createdAt: string;
  likeCount: number;
  likedByMe: boolean;
  mine: boolean;
  replies: CommunityComment[];
  /** 지금은 `replies.length` 와 같다. 나중에 인라인을 잘라도 계약이 안 바뀌도록 따로 준다. */
  replyCount: number;
}

/**
 * POST /community/posts/{postId}/comments · PUT /community/comments/{commentId} (인증)
 * ⚠️ `parentCommentId` 는 **최상위 댓글만** 가리킬 수 있다 — 대댓글에 달면 400.
 * ⚠️ 수정 시 `parentCommentId` 는 무시된다(부모 변경은 스레드 재배치라 본문 수정과 다른 동작).
 */
export interface CommunityCommentRequest {
  body: string;              // 필수, 최대 1000자
  parentCommentId?: number;
}

// DELETE /community/comments/{commentId} (인증) — 204.
//   대댓글이 있으면 soft(자리 유지), 없으면 hard(완전 삭제). 서버가 판단한다.
// POST|DELETE /community/comments/{commentId}/like (인증) — ReactionResponse.
//   ⚠️ 삭제된 댓글에는 누를 수 없다 → 400.
// ⚠️ 게시물의 `commentCount` 는 **삭제된 댓글을 뺀 수**다("댓글 3" 인데 2개 보이면 안 되므로).

/**
 * POST /community/reports (인증) — 신고 접수.
 * ⚠️ 중복 신고는 **200 멱등**(기존 건 반환). 자기 글·댓글은 400. 없는 대상도 400.
 * ⚠️ `reason === 'OTHER'` 면 `detail` 필수 — 없으면 400.
 */
export type ReportTargetType = 'POST' | 'COMMENT';

/** 신고 사유 6종. `OTHER` 면 `detail` 필수. */
export type ReportReason = 'SPAM' | 'ABUSE' | 'SEXUAL' | 'COMMERCIAL' | 'FALSE_INFO' | 'OTHER';

/** 어드민 처리 상태. `ACTIONED` 는 대상이 실제로 숨겨졌다는 뜻이다(상태만 바뀌는 게 아니다). */
export type ReportStatus = 'PENDING' | 'ACTIONED' | 'DISMISSED';

export interface ContentReportRequest {
  targetType: ReportTargetType;
  targetId: number;
  reason: ReportReason;
  detail?: string;   // 최대 500자
}

export interface ContentReport {
  id: number;
  targetType: ReportTargetType;
  targetId: number;
  reason: ReportReason;
  detail?: string;
  status: ReportStatus;
  createdAt: string;
  handledAt?: string;
  /** 어드민 목록에만. 접수 응답에는 키가 없다. */
  reporterNickName?: string;
  /** 어드민 목록에만. 대상이 이미 지워졌으면 키가 없다. */
  targetPreview?: string;
}

// 어드민(ROLE_ADMIN) — 신고 처리 큐. 어드민 FE 용이라 모바일/웹 클라이언트는 쓰지 않는다.
//   GET   /admin/community/reports?status=&page=&size=   배열은 `_embedded.reports`
//   GET   /admin/community/reports/counts                {pending, actioned, dismissed}
//   PATCH /admin/community/reports/{reportId}            {status: 'ACTIONED' | 'DISMISSED'}
//   ⚠️ ACTIONED 는 **대상 콘텐츠를 실제로 숨긴다**(게시물 hidden / 댓글 soft delete).

// ── 알림 (커뮤니티分) ──
// 댓글·답글이 달리면 수신자에게 푸시 1건. data.type = 'COMMUNITY_COMMENT', data.postId·data.commentId 동봉.
//   딥링크는 BE 가 URL 을 만들지 않는다 — 클라이언트가 그 id 들로 조립한다(기존 알림과 동일).
// ⚠️ Android 채널은 기존 `notice` 를 재사용한다(앱이 채널을 만들므로 새 채널은 릴리스에 묶인다).
// ⚠️ 좋아요 알림은 없다(소음). 자기 글에 자기가 단 댓글도 알림이 없다.
// ⚠️ 답글은 **부모 댓글 작성자에게만** 간다 — 글 작성자까지 보내면 스레드가 길수록 소음이 된다.

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
//   · GET /venue-favorites · POST /venue-favorites · DELETE /venue-favorites?venueRefId=
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
  /**
   * 지역 묶음 — BE 가 `address` 에서 읽을 때 파생한다(저장 컬럼 아님). 주소가 없어도 'ETC' 라 항상 존재.
   * ★ 둘러보기 지역 필터(`CourseBrowseParams.region`)와 **같은 규칙**이므로 picker 지역칩은 이 값을
   * 그대로 쓴다 — FE 가 주소 문자열에서 따로 파생하지 말 것(규칙이 갈라진다).
   */
  region: Region;
  scope: 'CUSTOM' | 'OFFICIAL';
  /** 코스가 저장하는 안정 참조 토큰. "CUSTOM:<pk>" | "OFFICIAL:<sanityId>". */
  venueRefId: string;
  /** 소유 강사 id. OFFICIAL 은 null. */
  ownerId: number | null;
  /** CUSTOM 만. OFFICIAL 은 null(이용권이 멀티 종목). */
  lockedDisciplineCode: string | null;
  /**
   * 호출 강사가 이 위치를 즐겨찾기했는가 — picker 가 초기 상태를 알려고 따로 호출하지 않아도 된다.
   * GET /venues · GET /venues/builder 응답에서만 채워진다.
   */
  favorite: boolean;
  closures: VenueClosure[];
  tickets: VenueTicket[];
  createdAt?: string;
  updatedAt?: string;
}

// ── 위치 즐겨찾기 (venue favorite) — docs/architecture/venue.md ──
// 강사가 "자주 쓰는 위치"로 선언한 표식. picker 의 "내 위치" 묶음을 채운다(기기 로컬 아님 — 서버 영속).
// 위치는 venueRefId 로 가리키므로 공식·커스텀을 같은 엔드포인트가 다룬다. 모두 인증(강사 트랙).
// ⚠️ 해제는 **쿼리 파라미터**다(DELETE 본문 아님) — 일부 HTTP 클라이언트/프록시가 DELETE 본문을 흘린다.
//    venueRefId 의 콜론은 쿼리 문자열에서 그대로 허용되는 문자라 인코딩 없이 보내도 되고, %3A 로 보내도 된다.
// 마크·해제 모두 **멱등** — 이미 즐겨찾기한 걸 또 마크해도 200, 없는 걸 해제해도 204.

/** POST /venue-favorites — 마크. */
export interface VenueFavoriteRequest {
  /** "CUSTOM:<pk>" | "OFFICIAL:<sanityId>" (위치 목록이 준 토큰). 형식 어긋나면 400. */
  venueRefId: string;
}

/** 즐겨찾기 1건 — 목록은 `_embedded.venueFavorites`(CollectionModel). */
export interface VenueFavoriteResponse extends HalLinks {
  venueRefId: string;
  createdAt?: string;
}

// ── 대여 장비 가격표 (equipment extension) — docs/architecture/venue.md ──
// 장비 대여료는 위치별로 다름(딥스테이션 무료포함 ↔ 5m풀 유료) → 강사 × 위치 단위 가격표(강사 전역,
// 모든 코스 공유, "어디서 바꿔도 신규 접수부터 적용"). 위치는 venueRefId(빌더 목록이 준 토큰)로 가리킴.
// 모두 인증(강사 트랙). GET /venue-equipment(?venueRefId= 단건/전체) · PUT /venue-equipment(upsert).

/** 사이즈 표기 형식. 미입력 시 SHOE_MM/APPAREL_SXL 은 서버 프리셋 자동, NONE 은 빈 목록, CUSTOM 은 직접. */
export type SizeFormat = 'NONE' | 'SHOE_MM' | 'APPAREL_SXL';

/** 장비 1종 (요청·응답 공용 모양). price 0 = 무료. */
export interface VenueEquipmentItem {
  /** 응답에만. VENUE_DEFAULT 일 때 null. */
  id?: number | null;
  name: string;
  price: number;
  /** 미지정 시 NONE 취급. */
  sizeFormat?: SizeFormat;
  /** 수강생이 고를 사이즈. 비우면 sizeFormat 프리셋으로 채워져 응답에 옴. VENUE_DEFAULT 일 때 null(= 자동 — 프리셋 폴백). */
  sizeOptions?: string[] | null;
}

/** PUT /venue-equipment 요청 — 한 위치 가격표 저장(items 전량 교체 스냅샷). */
export interface VenueEquipmentRequest {
  /** "CUSTOM:<pk>" | "OFFICIAL:<sanityId>" (GET /venues/builder 항목의 venueRefId). */
  venueRefId: string;
  items: VenueEquipmentItem[];
}

/** 가격표 응답. 목록은 `_embedded.extensions`(CollectionModel). */
export interface VenueEquipmentResponse extends HalLinks {
  /** VENUE_DEFAULT 일 때 null(아직 저장 행 없음). */
  id: number | null;
  venueRefId: string;
  items: VenueEquipmentItem[];
  /** 'MINE' = 강사 저장분(기존 동작). 'VENUE_DEFAULT' = 저장분 없음 → venue 기본 장비 prefill.
   *  VENUE_DEFAULT 일 때 item.id 는 null (예약 불가 — Step3 저장 시 실체화되며 id 부여). */
  source?: 'MINE' | 'VENUE_DEFAULT';
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
 * 지역 묶음 — 둘러보기 필터 칩, 그리고 코스빌더 위치 picker 의 지역 칩(`VenueResponse.region`)이 공유.
 * 강사가 따로 입력하지 않고 위치 도로명주소의 시·도에서 **BE 가 파생**한다.
 *
 * ★ **주소에서 클라이언트가 다시 파생하지 말 것** — BE `Region.fromAddress` 가 단일 출처다.
 *   따로 파생하면 둘러보기와 picker 의 "지역"이 갈라진다(실제로 그렇게 어긋난 적 있음).
 *
 * ★ **행정구역(17개 시·도)이 아니라 "권역" 묶음이다** — 광역시를 인접 도에 묶는다:
 *   `인천 → SEOUL_GYEONGGI`(수도권), `울산 → BUSAN_GYEONGNAM`(동남권=부울경).
 *   그래서 표시 라벨을 `'서울·경기'`/`'부산·경남'` 으로 쓰면 **묶인 광역시가 배제된 것처럼 보인다**
 *   (인천 위치가 "서울·경기" 칩에 뜬다). 칩 문구는 `'서울·인천·경기'`/`'부산·울산·경남'`(또는
 *   수도권/동남권)처럼 묶음을 드러내는 쪽을 권장. 라벨은 클라이언트 소유 — BE 는 이 값만 내보낸다.
 *
 * ★ **`ETC` 는 드문 예외가 아니다** — 실카탈로그(공식 위치 23곳) 기준 7곳(약 30%)이 ETC 이고
 *   `GANGWON` 은 0곳이다. 지역 칩만 노출하고 `ETC` 를 빼면 30% 가 필터로 도달 불가해지니,
 *   "전체"를 기본값으로 두거나 `ETC` 칩을 함께 노출할 것. (분포·근거: docs/features/course-discovery.md)
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
/**
 * 대여 장비 1줄(표시용) — EnrollmentRoundEquipment 스냅샷의 단일 투영. 강사 hub · 학생 hub · 강사 캘린더 신청자 행이
 * 모두 이 한 형태를 공유(BE 도 enrollment/dto/GearItem 하나로 통합). sizeLabel 은 저장값("270"·"L") — 단위(mm)는 FE 표기, 없으면 null.
 */
export interface GearItem {
  name: string;
  sizeLabel: string | null;
}

export interface ApplicantSummaryResponse {
  name: string;
  organizationCode: string | null;
  disciplineCode: string | null;
  /** 평탄 레벨 enum: 'LEVEL_1'|'LEVEL_2'|'LEVEL_3'|'LEVEL_4'|'INSTRUCTOR'|'INSTRUCTOR_TRAINER'. 범위 코스면 여러 개. */
  levels: CertLevel[];
  /** 이번 세션 대여 장비 내역. 강사/학생 hub 의 gearItems 와 동일 형태(구조화 — 옛 string[] 대체). 없으면 []. */
  gear: GearItem[];
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
// 흐름(선결제 — **전 회차 동일**, 2026-08-09 통일): 신청(PENDING, 좌석 점유·결제 대기)
//   → **즉시 결제**(POST /payments/prepare·confirm) → ACCEPT_PENDING(결제완료·강사 결정 대기)
//   → 강사 수락(CONFIRMED) / 거절·무응답 만료(REJECTED·CANCELLED + 전액 자동환불).
// 즉, 신청과 동시에 결제하고 강사는 그 뒤에 결정한다(표준 이커머스 "주문→결제"). 2회차+ 도 같다.
// 강사의 세 번째 선택지 = **일정조정 제안**(propose-slots) → 학생은 결제가 아니라 ㅇㅋ(pick-slot → 곧장 CONFIRMED)
//   / ㄴㄴ(cancel → 전액환불, 또는 reschedule 로 내 슬롯 재제안)만 한다. 결제는 payment 섹션 참고.
// 거절·취소된 회차는 그 회차만 무효 → **같은 회차를 다른 날짜로 다시 신청**할 수 있다(POST /rounds 재호출).

// PENDING          = 신청 직후·미결제(좌석 점유). 여기서 바로 결제창을 띄운다. 미결제 방치 시 자동 만료
//                    (window 는 운영에서 조정하는 값 — Sanity siteSettings. 짧게는 1h 수준으로 운영).
// ACCEPT_PENDING   = 결제완료·강사 결정 대기(좌석 점유). 강사가 수락/거절/일정조정 제안. 24h 무응답 시 자동환불.
// CONFIRMED        = 확정. REJECTED/CANCELLED = 거절·취소(결제분은 자동환불).
export type EnrollmentStatus = 'PENDING' | 'ACCEPT_PENDING' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED';

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
  ticketName: string | null; // 이용권 표시명("일반권"·"하프권"). OFFICIAL/CUSTOM 합성 — CourseDetail 의 ticketName 과 동일 출처
  entryFee: number;         // 입장료(이용권 × 그 날짜 평일/주말 daypart fee)
  capacity: number;
  remaining: number;        // capacity − 확정 − 외부 hold
  full: boolean;
  /**
   * 선택 불가 사유 — null 이면 선택 가능. 강사가 내놓은 시간(coverage∩운영)이지만 지금 막힌 슬롯만 표기(coverage 밖·휴무는 슬롯 자체가 없음).
   * non-null 이면 FE 가 비활성 처리. 'FULL'=만석(full===true 와 동치), 'TIME_CONFLICT'=강사가 같은 시간 다른 위치/블록에 일정(카피 예: "다른 일정이 있어요").
   */
  unavailableReason: 'FULL' | 'TIME_CONFLICT' | null;
}

export interface EnrollmentEquipmentOption {
  itemRef: string;
  name: string;
  price: number;
  sizeFormat?: string | null;  // 있으면 사이즈 칩(v1 은 표시만, 캡처 후속)
  sizeOptions?: string[];
}

/**
 * 신청 — POST /enrollments → 201 PENDING. 옵션이 준 슬롯 식별자(date,위치,블록) echo. 서버가 모두 재검증.
 * ⚠️ 선행: 본인인증 미완료면 403 IDENTITY_VERIFICATION_REQUIRED(-1017) — 신청 화면 진입 전 GET /identity-verifications/me
 * 로 확인하고, 없으면 본인인증부터. (2회차+ POST /{id}/rounds 는 1회차에서 이미 통과했으므로 별도 게이트 없음.)
 */
export interface EnrollmentCreateRequest {
  courseId: number;
  date: string;             // "YYYY-MM-DD" — 슬롯의 date (예전 availabilityWindowId 대체)
  venueRefId: string;
  ticketRef: string;
  blockStart: string;       // "14:00:00"
  blockEnd: string;
  equipmentRefs?: string[]; // 선택 장비 itemRef
  /** 선택 장비 사이즈(itemRef → "270"·"L"). 사이즈 있는 품목만 넣으면 됨. 서버가 그 품목 sizeOptions 멤버십 검증(프리셋 밖=400). 회차 스냅샷에 저장돼 강사 hub gearItems.sizeLabel 로 노출. */
  equipmentSizes?: Record<string, string>;
}

/** 강사 거절 — POST /instructor/enrollments/{roundId}/reject. 거절은 1회차(진입)만(진행 중은 일정변경요청). */
export interface RejectRequest {
  reason?: string;
}

/**
 * 2회차+ 일정 신청 — POST /enrollments/{enrollmentId}/rounds → 201 PENDING. 어느 회차인지는 서버가 판정
 * ★ **미결제 재신청은 그냥 다시 호출하면 된다**(supersede) — 결제 전이면 옛 미결제 건을 갈아끼우므로 회차가
 *   중복 생기지 않고 옛 좌석도 자동 반납된다. 취소 후 재신청할 필요 없다(1회차는 POST /enrollments 재호출).
 * (다음 schedulable 회차 — 직전 정규 CONFIRMED 게이트, 정규 끝나면 EXTRA). 옵션은 GET /enrollments/{enrollmentId}/next-options.
 */
export interface RoundScheduleRequest {
  date: string;             // "YYYY-MM-DD"
  venueRefId: string;
  ticketRef: string;
  blockStart: string;       // "14:00:00"
  blockEnd: string;
  equipmentRefs?: string[];
  /** 선택 장비 사이즈(itemRef → "270"·"L"). EnrollmentCreateRequest 와 동일 규칙(sizeOptions 멤버십 검증). */
  equipmentSizes?: Record<string, string>;
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
 * 슬롯 UI 재사용). 슬롯의 `unavailableReason`(FULL/TIME_CONFLICT)으로 **선택 불가 슬롯을 비활성화**해 강사가 안 고르게 한다(제안 보장 hold 도 잔여에 반영).
 *
 * 슬롯 규칙:
 *  - **위치 고정(이 강사 제안 엔드포인트 한정)**: 회차가 잡은 venueRefId 1개로만 슬롯이 온다(편의 — 학생이 고른 위치 그대로 시간만 제안).
 *    FE 가 venueRefId 로 거를 필요 없음. ⚠️ 학생 직접 일정수정(GET /enrollments/rounds/{roundId}/options)은 위치 고정 아님 — 회차의 모든 후보 위치를 자유 선택.
 *  - **날짜 window**: 오늘부터 8주 안에서 강사 coverage(예약가능시간)가 있는 날만(coverage 끝이 더 가까우면 거기까지). FE 는 응답 날짜를 그대로 노출만.
 *  - **중복 없음**: 같은 (date, venueRefId, ticketRef, blockStart, blockEnd) 슬롯은 한 번만 — FE dedupe 불필요.
 *  - **선택 불가 표기**: 만석/시간겹침 슬롯은 필터되지 않고 `unavailableReason` 과 함께 내려온다 — FE 는 비활성 + 사유(`TIME_CONFLICT`→"다른 일정이 있어요")로 그린다. coverage 밖·휴무는 슬롯 자체가 없음.
 *  - 각 슬롯은 `ticketName`(표시명)을 담는다 — 그룹 헤더를 ticketRef 대신 ticketName 으로.
 */

/**
 * 강사 일정변경요청 — POST /instructor/enrollments/{roundId}/propose-slots. 완전한 대안 슬롯 제안(**최대 3개**).
 * **결제완료(ACCEPT_PENDING) 회차에만** — 수락/거절과 나란한 강사의 세 번째 선택지다.
 * 서버가 bookable + **좌석 여유**인 것만 채택하고, 채택된 슬롯마다 그 일정에 **좌석 보장 hold**(proposalTtl, 기본 6h)를
 * 잡아 학생 pick 이 만석으로 막히지 않게 한다(그 동안 다른 학생 신청은 막힘).
 * ★ **더 비싼 시간대도 제안할 수 있다**(2026-08-10 부터 — 옛 "결제액 이하만" 필터는 제거됐다). 학생이 그걸 고르면
 *   pick-slot 이 -1018 로 돌려보내고 **차액만 결제**하는 경로로 간다. 강사 클라이언트가 비싼 슬롯을 미리 숨기지 말 것.
 * 4개 이상이거나 전부 불가/만석이면 400.
 */
export interface ProposeSlotsRequest {
  slots: SlotProposal[]; // 최대 3
}

/**
 * 제안 슬롯 선택("ㅇㅋ") — POST /enrollments/rounds/{roundId}/pick-slot → 200.
 * 이미 결제된 회차 + 강사가 승인한 자리라 **추가 결제도 재수락도 없이 곧장 CONFIRMED**(입장료가 싸졌으면 차액 자동환불).
 * ★ **단 더 비싼 제안은 예외** — 그걸 고르면 **-1018**(ADDITIONAL_PAYMENT_REQUIRED)로 거부되고, 차액 결제
 *   (POST /payments/prepare + target*)를 거쳐야 한다. 그 경로의 종착지는 CONFIRMED 가 아니라 **ACCEPT_PENDING**(강사 재수락).
 *   거부는 완전 롤백이라 제안이 그대로 남아 다시 고를 수 있다.
 * 좌석은 제안 시점 hold 로 보장돼 있어 만석으로 막히지 않는다.
 * 제안이 TTL 만료로 사라졌으면 **-1020**(PROPOSAL_EXPIRED) — 사용자 잘못이 아니니 "일정을 직접 골라보세요"로 안내하고
 *   reschedule 로 유도할 것. 회차는 살아 있고(status 가 WAITING 으로 복귀) 다시 잡으면 된다.
 * 제안이 다 안 맞으면("ㄴㄴ") cancel(전액환불) 또는 reschedule(내 슬롯으로 재제안).
 */
export type PickSlotRequest = SlotProposal; // proposedSlots 중 하나

/**
 * 직접 일정 수정 (제안 외 원하는 슬롯) — POST /enrollments/rounds/{roundId}/reschedule (body = RoundScheduleRequest) → 200.
 * 회차를 **제자리 변경**(취소 아님 — 회차 id 유지, 옛 슬롯은 slotHistory 적재). 날짜에 따라 위치가 다를 수 있어 위치/장비
 * 재선택 가능. 두 경로:
 *  - 미결제(PENDING) → status 그대로 **PENDING**(결제 시계 재시작).
 *  - 결제완료(ACCEPT_PENDING) → **결제를 유지한 채 학생 재제안**. status 그대로 **ACCEPT_PENDING**(강사 24h 시계 재시작,
 *    강사 hub 엔 CHANGING). 금액이 줄면 차액 자동환불, **늘면 -1018**(ADDITIONAL_PAYMENT_REQUIRED)
 *    → 이때는 **차액 결제 경로**를 쓴다: POST /payments/prepare 에 target* 넷을 실어 차액만 결제(payment 섹션).
 *    단 차액 경로는 **일정(날짜·이용권·블록)만** 바꾼다 — **위치까지 바꾸며 금액이 오르면 -1019**
 *    (VENUE_CHANGE_REQUIRES_REAPPLY, 차액 결제 불가 → 취소 후 재신청). 위치가 바뀌어도 **같거나 싸면** 그냥 된다.
 * 확정(CONFIRMED)·거절·취소된 회차는 400.
 * 슬롯 후보는 GET /enrollments/rounds/{roundId}/options (1회차 옵션과 동일 EnrollmentOptionsResponse — 슬롯 UI 재사용).
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
  /**
   * 결제 기한까지 남은 **초**. 미결제(status='PENDING')일 때만 값이 있고 그 외엔 null.
   * ★ 카운트다운은 이 값을 앵커로 쓴다 — TTL 은 Sanity 운영값이라 배포 없이 바뀌므로 FE 하드코딩 금지,
   *   절대시각이 아닌 이유는 기기 시계가 틀어져도 안 밀리게 하려는 것(otpExpiresInSeconds 와 같은 규칙).
   * ⚠️ 0 이 곧 "결제 불가"는 아니다 — 만료 스윕이 주기 폴링(약 5분)이라 잠깐은 결제가 성사된다.
   *   0 은 "곧 만료"로 다루면 된다(늦게 결제해도 취소되지 않는다).
   */
  paymentExpiresInSeconds: number | null;
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
  | 'WAITING'       // ACCEPT_PENDING(결제완료·강사 결정 대기) — 강사 확인 중
  | 'RESCHEDULING'  // 강사 일정변경 제안 — 학생이 proposedSlots 중 골라 pick-slot(ㅇㅋ) 또는 cancel/reschedule(ㄴㄴ)
  | 'PAYMENT_DUE'   // 결제 필요 — 미결제(PENDING). 전 회차 동일
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
  /** 내가 그 회차에 신청한 대여 장비 내역(신청 시점 스냅샷). 강사 hub gearItems 와 동일 형태. 없으면 []. */
  gearItems: GearItem[];
  /** 강사 일정변경 제안 슬롯(RESCHEDULING). 학생이 골라 POST /enrollments/rounds/{roundId}/pick-slot. */
  proposedSlots: SlotProposal[];
  rejectionReason: string | null; // REJECTED만
  /**
   * 결제 기한까지 남은 **초**. status='PAYMENT_DUE'(미결제)일 때만 값이 있고 그 외엔 null.
   * "OO분 안에 결제" 안내의 단일 출처 — 주의사항은 EnrollmentResponse.paymentExpiresInSeconds 와 동일.
   */
  paymentExpiresInSeconds: number | null;
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
  | 'WAITING'      // 결제완료 신규 신청(ACCEPT_PENDING) — 수락/거절/일정조정 제안. 전 회차 동일
  | 'CHANGING'     // 학생이 직접 일정수정(결제 유지한 재제안) — 검토(previousSlot 노출)
  | 'PROPOSED'     // 강사가 일정변경요청함 — 학생 선택 대기(강사 액션 아님)
  | 'PAYMENT_DUE'  // 학생 결제 대기 — 미결제(PENDING). 전 회차 동일
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
  gearCount: number;             // = gearItems.length (하위호환)
  /** 학생이 그 회차에 신청한 대여 장비 내역(신청 시점 스냅샷). 없으면 []. */
  gearItems: GearItem[];
  /** CHANGING 일 때 학생이 바꾸기 전 슬롯(변경 검토 diff). 없으면 null. */
  previousSlot: { date: string | null; venueRefId: string | null; ticketRef: string | null;
                  blockStart: string | null; blockEnd: string | null } | null;
}

// ============================================================
// 결제 (payment 도메인) — PG 중립 (토스페이먼츠 / KG이니시스 INIpay PRO 표준결제)
// docs/features/payment.md · docs/architecture/payment.md 참고
// ============================================================
// ★★ 2026-07~08 변경: PG 를 갈아끼울 수 있게 계약이 PG 중립으로 바뀌었다(토스 심사 적체 → KCP 시도 →
//    KCP "중개 미지원" 거절 → KG이니시스로 전환). prepare 응답의 clientKey/customerKey → provider + params(맵),
//    confirm 의 paymentKey → pgPayload(맵). 토스·이니시스는 공존(BE 가 PAYMENT_MODE 로 스왑, FE 는 provider 로만 분기).
//
// 흐름(선결제 — **전 회차 동일**): 신청 직후(enrollment = PENDING) 곧바로 POST /payments/prepare(주문 생성 + 결제창 구동값)
//   → FE 가 provider 로 분기해 결제창 구동 → (TOSS/STUB) FE 가 confirm / (INICIS) 결제창이 BE 콜백으로 POST → BE 승인
//   → enrollment **ACCEPT_PENDING**(결제완료·강사 결정 대기). 이후 강사 수락 시 CONFIRMED / 거절·무응답 만료 시 자동환불.
//   ※ 2회차+ 도 같다(2026-08-09 통일). 금액만 다르다 — 수강료는 1회차 주문에 전액, 2회차+ 는 부대비용만.
//   ※ pick-slot(강사 제안 수락)은 이미 결제된 회차의 일정 변경이라 **결제 없이** CONFIRMED 로 간다.
//
// ★ amount·orderId 는 서버가 정한 값(권위) — FE 는 prepare 응답값을 그대로 결제창/confirm 에 넘긴다.
//   임의 변경 시 승인 거절(서버가 저장한 금액으로 PG 에 승인 요청하므로 결제창 금액과 다르면 PG 가 거절).
// 로컬/테스트는 stub(외부 미호출·즉시 승인), staging/prod 만 실연동(PAYMENT_MODE=toss|inicis).

export type PaymentStatus = 'READY' | 'DONE' | 'CANCELED' | 'FAILED';

/**
 * 어떤 PG 로 결제하나. 신규 주문의 PG 선택은 전역 설정이 정한다(한 번에 하나).
 * 단, 기존 주문의 승인·환불은 그 주문에 <b>박제된 provider</b> 로 라우팅된다 —
 * PG 를 갈아탄 뒤에도 과거 이니시스 주문의 환불이 이니시스로 가야 하므로(BE 가 보장, FE 는 신경 쓸 것 없음).
 */
export type PaymentProvider = 'STUB' | 'TOSS' | 'INICIS';

/** 결제 준비 — POST /payments/prepare (authenticated). 미결제 회차(신청 직후 PENDING)에 대해 주문 생성.
 *  전 회차 동일. 그 외 상태면 400(이미 결제/확정/취소/만료).
 *
 *  ★ **슬롯 변경 차액 결제**: `target*` 넷을 모두 보내면 "더 비싼 시간대로 옮기며 차액만 결제"가 된다.
 *    - `amount` = 차액(목표 슬롯 회차금액 − 현재 회차금액). 결제창이 떠 있는 동안 목표 슬롯 좌석이 잡힌다.
 *    - confirm 이 성공하는 **그 순간 슬롯이 교체**되고, 회차는 **강사 결정 대기(ACCEPT_PENDING)로 되돌아간다**
 *      — 학생이 고른 시간은 강사가 동의한 적이 없으므로 **강사 수락이 필요**하다(거절 시 차액 포함 전액 환불).
 *      재수락이 없는 건 강사가 낸 제안을 고르는 pick-slot 뿐.
 *    - 결제를 포기하면 주문만 만료되고 예약은 원래 슬롯 그대로 — 되돌릴 게 없다.
 *    - 위치·장비는 현재 것을 유지한다(바꾸려면 취소 후 재신청). 같거나 싼 슬롯은 이 경로가 아니라
 *      pick-slot / reschedule 로 결제 없이 즉시 바뀐다(싸지면 차액 자동환불).
 */
export interface PaymentPrepareRequest {
  /**
   * ★ 회차(EnrollmentRound) id. 결제 단위는 회차다.
   * ⚠️ 이 값은 회차 id — 환불 path 의 {enrollmentId}(수강 id)와 다른 엔티티다. 둘 다 number 라 타입으로 안 잡히니 주의.
   */
  roundId: number;
  /** @deprecated roundId 를 쓸 것. 회차 id 를 담던 옛 이름 — 하위호환으로 당분간만 허용. */
  enrollmentId?: number;
  /**
   * 모바일 환경 여부(기본 false). 이니시스 표준결제의 P_DEVICE_TYPE(MOBILE/WEB) 분기에 쓰인다.
   * TOSS·STUB 는 무시. 웹은 UA/뷰포트로, 앱은 항상 true.
   */
  mobile?: boolean;
  /**
   * 클라이언트 종류(기본 'web'). **이니시스 콜백 리다이렉트 타겟**(web URL / app 스킴 plop://)을 BE 가 고르는 데 쓴다.
   * ★ mobile 과 독립 축: mobile=결제창 레이아웃, client=리다이렉트 타겟. 웹 모바일브라우저 = { mobile:true, client:'web' }.
   * 앱 → 'app', 웹(데스크탑·모바일 모두) → 'web'. TOSS·STUB 는 무시.
   */
  client?: 'web' | 'app';

  // ★ 슬롯 변경 차액 결제(선택) — 넷을 모두 보낼 때만 활성. 하나라도 빠지면 일반 결제로 처리된다.
  targetDate?: string;        // 'YYYY-MM-DD'
  targetTicketRef?: string;
  // ★ 'HH:mm' · 'HH:mm:ss' 둘 다 받는다 → EnrollmentSlot.blockStart("14:00:00")를 **그대로 되보내면 된다**(자르지 말 것).
  //   (예전엔 'HH:mm' 만 받아 "14:00:00" 이 400 이었다. 2026-08-11 수정.)
  targetBlockStart?: string;
  targetBlockEnd?: string;
  /**
   * 목표 슬롯 **위치**(선택 — 보내면 서버가 대조한다). ★ 차액 결제는 **위치를 바꾸지 못한다** —
   * 서버는 언제나 회차의 현재 위치로 목표 슬롯을 해석한다.
   * 그래서 다른 위치를 띄워놓고 보내면 (이용권·시간이 현재 위치에도 우연히 있을 경우) 학생이 고른 적 없는
   * 원래 위치로 조용히 옮겨진다 → 값을 보내면 다를 때 **-1019 로 거부**한다.
   * **사용자에게 보여준 위치를 항상 실어 보낼 것**(안 보내면 이 방어가 꺼진다).
   */
  targetVenueRefId?: string;
}

/**
 * 결제창 구동값. amount·orderId·orderName 은 서버 권위값 — 그대로 결제창에 넘긴다.
 * ★ params 의 키는 provider 마다 다르다 — 반드시 provider 로 분기해서 꺼낼 것:
 *   - 'TOSS'   → clientKey(공개값), customerKey  … 결제위젯 v2 로 렌더
 *   - 'INICIS' → P_ 파라미터 일습(P_MID, P_OID, P_PAY_TYPE, P_DEVICE_TYPE, P_IDCCODE, P_AMT, P_GOODS,
 *                P_UNAME, P_NEXT_URL, P_TIMESTAMP, P_CHKFAKE(서명), P_CHARSET).
 *                · INIPayPro_v2.js(https://paypro.inicis.com/std/payment/js/INIPayPro_v2.js)를 로드하고
 *                  INIPayPro.requestPayment(params) 로 결제창 구동. ⚠️ 구버전 stdpay.inicis.com 아님.
 *                · P_GOODS/P_UNAME 은 서명 대상이 아니라 표시용 — FE 가 덮어도 금액/주문은 위조 불가.
 *   - 'STUB'   → customerKey 만. 결제창 없이 바로 confirm 호출 가능(로컬 개발)
 */
export interface PaymentPrepareResponse {
  orderId: string;      // PG 멱등키 — 결제창/confirm 에 그대로. 내부 식별(표시 X)
  orderNo: string;      // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화). FE 의 "주문번호" 표시는 이걸로
  amount: number;       // 원 — (첫 만남이면 수강료 스냅샷) + 입장료 + 장비 + 추가세션비. 회차 단위
  orderName: string;    // "코스명 (N회차)"
  provider: PaymentProvider;
  params: Record<string, string>;
  /**
   * 이 결제창을 닫아야 하는 기한까지 남은 **초**(계산 불가면 null).
   * 일반 결제는 회차의 미결제 window(신청 시각 기준), **차액 결제는 주문의 window**(좌석 hold 와 같은 기한) —
   * 시계가 서로 다르니 이 값을 그대로 쓸 것.
   */
  paymentExpiresInSeconds: number | null;
}

// ★★ confirm 주체가 provider 마다 다르다 (WebView POST 제약):
//   - TOSS/STUB → **FE 가** POST /payments/confirm 호출 (아래).
//   - INICIS    → **FE 는 confirm 을 호출하지 않는다.** 결제창이 인증결과를 BE 콜백(P_NEXT_URL=BE)으로 form POST 하고,
//                 BE 가 서버사이드 승인까지 끝낸 뒤 GET 리다이렉트한다:
//                   web  → {origin}/payment/success?orderId&orderNo&status=paid   (실패: /payment/fail?...&status=failed)
//                   app  → plop://payment/success?orderId&orderNo&status=paid     (실패: plop://payment/fail)
//                 리다이렉트 타겟은 prepare 의 client(web/app)로 BE 가 고름(오픈리다이렉트 방지 — 클라가 URL 안 정함).
//                 FE 는 그 성공화면에서 orderId 로 GET /payments/orders/{orderId} 조회해 금액·상태를 채운다.

/**
 * 결제 승인 — POST /payments/confirm (authenticated). **TOSS/STUB 전용** (INICIS 는 BE 콜백이 처리 — 위 주석).
 * ★ pgPayload 키는 provider 별로 다르다:
 *   - 'TOSS' → { paymentKey }   (위젯 성공 리다이렉트의 값)
 *   - 'STUB' → {} (생략 가능)
 * 필요한 키가 없으면 400.
 */
export interface PaymentConfirmRequest {
  orderId: string;
  amount: number;       // 서버 권위 금액과 다르면 400 (FE 는 prepare 의 amount 를 그대로)
  pgPayload?: Record<string, string>;
}

/**
 * 승인 결과 + 그 결과로 확정된 신청 상태. 멱등 — 이미 DONE 인 주문 재승인도 200 DONE.
 * GET /payments/orders/{orderId} 응답도 같은 모양(성공화면 재사용).
 */
export interface PaymentConfirmResponse {
  orderId: string;                // PG 멱등키(내부). 완료 화면 "주문번호" 표시는 orderNo 사용
  orderNo: string;                // CS·고객용 주문번호(PD-YYMMDD-XXXXXXXX, 날짜+난독화·가역)
  status: PaymentStatus;          // 성공 = 'DONE'
  amount: number;
  approvedAt: string | null;      // ISO-8601 offset
  /** ★ 회차(EnrollmentRound) id. 옛 이름 `enrollmentId` 에서 개명(2026-08-11) — 담는 값이 회차 id 인데
   *  환불 경로 `POST /enrollments/{enrollmentId}/refund` 의 것은 **수강 id** 라 헷갈렸고 둘 다 number 라
   *  타입으로도 안 잡혔다. */
  roundId: number | null;
  /**
   * ★ **이 응답을 만든 시점의** 회차 상태 — **결제의 결과가 아니다**. 옛 이름 `enrollmentStatus` 에서 개명(2026-08-11).
   * 결제의 결과는 언제나 'ACCEPT_PENDING' 이지만, 이 필드는 회차를 **live 로** 읽으므로 결제와 조회 사이에
   * 강사가 수락하면 'CONFIRMED', 거절/취소/만료면 'REJECTED'/'CANCELLED' 가 온다.
   * 특히 GET /payments/orders/{orderId}(이니시스 성공화면)와 **멱등 재-confirm** 에서 그렇다 —
   * **결제는 멱등인데 이 필드는 아니다.**
   * → 완료 화면 문구는 이 필드가 아니라 `status` + `scheduleChange`(둘 다 멱등)로 가를 것.
   *   이 필드는 "지금 상태 표시" 용으로만 쓰고, **모르는 값은 '확정 단정 안 함' 으로 떨어뜨릴 것**.
   */
  currentEnrollmentStatus: EnrollmentStatus;
  /**
   * 이 주문이 **일정 변경 차액** 결제인가 — 완료 화면 문구 분기용
   * (false: "결제가 완료됐어요" / true: "일정 변경을 요청했어요").
   * currentEnrollmentStatus 는 두 경우 모두 'ACCEPT_PENDING' 이라 구분이 안 되고, 이니시스는 성공 URL 을 BE 가 만들어
   * 302 하므로 FE 가 쿼리를 실을 수도 없다 → 서버가 알려준다. **쿠키/sessionStorage 우회 불필요.**
   */
  scheduleChange: boolean;
}

/**
 * 주문 상세 조회 — GET /payments/orders/{orderId} (authenticated, 소유권 검증).
 * 이니시스 성공화면(confirm 을 FE 가 안 해 리다이렉트 쿼리만 옴)에서 금액·상태를 채우는 용도 + 새로고침/딥링크 재진입 복구.
 * 응답 = PaymentConfirmResponse 와 동일 모양. 비소유/없음 = 400(존재 숨김).
 */
export type PaymentOrderResponse = PaymentConfirmResponse;

// ── 결제 에러 (공통 envelope: { success:false, code:number, msg:string }) ──
// 결제 도메인은 "없음/비소유"도 404 가 아니라 400 이다(존재 숨김, repo 컨벤션). 아래는 code/msg 예시.
//   금액 위변조 / 남의 회차 prepare / 이미 취소·만료 주문 confirm / pgPayload 필수키 누락
//     → 400, code -1011, msg "보내신 요청 정보가 옳지 않습니다."  (구분 불가 — 의도적. 세분 code 없음)
//   PG 승인 거절(카드 한도·정지 등) → 400, code -1011. ⚠️ PG 원문 메시지는 msg 로 넘어오지 않는다
//     (BE 가 로그로만 남기고 고정 msg 로 응답 — 카드사 사유는 결제창 단계에서 사용자에게 노출됨).
//   미인증/토큰만료 → 401 (code -1002), 권한없음 → 403 (code -1003).



/**
 * 수강 환불(남은 회차 환불) — POST /enrollments/{enrollmentId}/refund (authenticated). 진행 중 "환불신청".
 * 활성·미완료 회차를 전부 취소하고 회차별로 환불(PG 부분취소). 응답 = 회차별 환불 내역.
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
  /** @deprecated BE 가 발행하지 않는다 — 대응하는 예외·i18n 키가 없음. 분기에 쓰지 말 것. */
  EXPIRED_ACCESS_TOKEN: -1005,
  EXPIRED_REFRESH_TOKEN: -1006,
  FORBIDDEN_TOKEN: -1007,
  PRE_LAUNCH: -1016, // 정식 런칭 전 수강신청 시도(POST /enrollments, 403). FE 는 "런칭 대기" 안내로 분기
  // 본인인증 미완료 상태로 선행-조건 동작 시도(403). FE 는 본인인증(POST /identity-verifications) 화면으로 분기.
  //   · POST /enrollments (수강신청 전 선행) — 세션 계정으로 조회, 최신 VERIFIED 없으면.
  //   · POST /instructor-applications (강사 전환 전 선행) — verificationId 가 가리키는 레코드가 VERIFIED 아님.
  //     (없는/남의 verificationId 는 이 코드가 아니라 -1011(BAD_REQUEST, 400) 유지 — "본인인증하러 가라"가 아님.)
  IDENTITY_VERIFICATION_REQUIRED: -1017,

  // ── 도메인 코드 (아래는 전부 HTTP 400) ──
  NO_PERMISSIONS: -1008,
  RESOURCE_NOT_FOUND: -1009, // 없음/비소유 통일(존재 숨김)
  RESERVATION_FULL: -1010,
  /** 범용 400. reschedule/prepare 등에서 여러 실패 사유가 이 코드를 공유하니 이걸로 사유를 가리지 말 것. */
  BAD_REQUEST: -1011,
  EMAIL_DUPLICATION: -1012,
  CLOSED_LECTURE: -1013,
  COVERAGE_HAS_SESSION: -1014,
  /** 그 시간에 강사의 다른 일정이 있음. 일정 추가/신청/일정변경(reschedule·pick-slot) 공통. */
  SESSION_TIME_OVERLAP: -1015,
  /**
   * 옮기려는 슬롯이 지금보다 **비싸서** 추가 결제 없이는 못 바꿈.
   * FE 는 이 코드일 때만 "추가 결제하고 변경하기"로 분기한다 —
   * POST /payments/prepare 에 target* 4필드를 실어 보내면 서버가 차액을 계산해 결제창을 연다.
   * 나오는 곳: POST /enrollments/rounds/{roundId}/reschedule, POST /enrollments/rounds/{roundId}/pick-slot.
   */
  ADDITIONAL_PAYMENT_REQUIRED: -1018,
  /**
   * 위치까지 바꾸면서 금액이 오르는 변경 — **차액 결제로는 갈 수 없는 조합**이다.
   * ★ 이 코드에는 "추가 결제하고 변경하기" 를 띄우면 안 된다. 차액 결제 경로는 위치를 못 바꾸므로,
   *   결제를 태우면 학생이 고른 적 없는 **원래 위치**의 슬롯으로 옮겨진다(성공 화면은 정상으로 보인다).
   *   안내: "위치까지 바꾸려면 지금 예약을 취소하고 다시 신청" (= cancel → 재신청).
   * 나오는 곳: POST /enrollments/rounds/{roundId}/reschedule (위치 변경 + 금액 상승),
   *           POST /payments/prepare (targetVenueRefId 가 회차의 현재 위치와 다를 때).
   * 참고: 위치 변경이라도 **같거나 싸면** reschedule 로 그대로 된다(차액 자동환불).
   */
  VENUE_CHANGE_REQUIRES_REAPPLY: -1019,
  /**
   * 강사가 낸 일정 제안이 만료돼 고를 수 없음 (proposalTtlHours, 기본 6h 경과).
   * ★ 사용자 잘못이 아니다 — "제안이 만료됐어요 · 일정을 직접 골라보세요" 로 안내하고 reschedule 로 유도할 것.
   * 회차는 그대로 살아 있다(ACCEPT_PENDING, hub 에서 WAITING). 나오는 곳: POST /enrollments/rounds/{roundId}/pick-slot.
   * 참고: 제안은 살아 있는데 **목록 밖 슬롯**을 고른 경우는 성격이 달라 -1011 유지.
   */
  PROPOSAL_EXPIRED: -1020,
} as const;

export type ErrorCodeValue = (typeof ErrorCode)[keyof typeof ErrorCode];

// ============================================================
// 사이트 설정 (siteSettings) — 런칭 토글
// ⚠️ BE 엔드포인트가 아니라 **Sanity 싱글톤**. FE 가 Sanity CDN 에서 직접 읽는다
//   (cert org/term 과 동일 패턴). 값 하나 바꿔 publish 하면 FE/BE 양쪽 무배포로 런칭 전환.
//   GROQ: *[_type == "siteSettings"][0]{launched, showSeededCourses, pendingTtlHours, paymentTtlHours, proposalTtlHours}
//   BE 도 같은 문서를 서버사이드로 읽어 신청 차단(PRE_LAUNCH)·데모 필터·좌석 lock 만료를 강제한다.
// ============================================================

export interface SiteSettings {
  launched: boolean; // false → 전 코스 신청 차단(BE 403 PRE_LAUNCH) + "정식 런칭을 기다려주세요" 배너
  showSeededCourses: boolean; // false → 데모(seeded) 코스가 둘러보기/상세에서 빠짐(데이터는 보존)
  pendingTtlHours: number;  // BE 내부 — 결제완료·강사 무응답 만료 + 전액 자동환불(기본 24). FE 미사용
  paymentTtlHours: number;  // BE 내부 — 미결제 만료(신청 시각 기준, 기본 12 / 현재 운영값 1).
                            // ★ FE 는 이 값을 읽지 말 것 — 카운트다운은 응답의 paymentExpiresInSeconds 를 쓴다
  proposalTtlHours: number; // BE 내부 — 강사 제안 슬롯·보장 hold 만료(기본 6). 만료 후 pick-slot 은 -1020
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
