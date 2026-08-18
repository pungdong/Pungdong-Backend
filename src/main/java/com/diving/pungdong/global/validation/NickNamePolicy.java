package com.diving.pungdong.global.validation;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 닉네임 정책 — <b>형식(shape)</b> + <b>예약어</b>. 여러 도메인이 공유하는 크로스도메인 상수라 global.
 *
 * <p><b>왜 닉네임에만 이렇게 빡빡한가</b>: 닉네임은 단순 표시명이 아니라 <b>공개 URL 식별자</b>다
 * ({@code GET /instructors/{nickName}}, D3 결정 — features/account-branding.md). 그래서 세 가지가 걸린다.
 *
 * <ol>
 *   <li><b>라우트 충돌</b> — {@code /instructors/public}·{@code /suggested} 같은 리터럴 경로가 path
 *       variable 보다 우선한다. 그 값을 닉네임으로 가진 계정은 프로필이 <b>영영 안 열린다</b>.</li>
 *   <li><b>사칭</b> — "풍덩공식"·"우리동네관리자" 는 플랫폼/운영자를 사칭한다. 다이빙은 안전이 걸린
 *       도메인이라 운영자를 사칭한 안내가 실제 위험으로 번진다.</li>
 *   <li><b>선점</b> — 우리가 나중에 진짜 공식 계정을 만들 때 이름이 남아 있어야 한다.</li>
 * </ol>
 *
 * <p><b>형식 가드가 사칭 방지의 절반이다.</b> 문자셋을 한글(완성형)·영문·숫자·밑줄로 좁히면 동형이의
 * 문자(키릴 {@code а} vs 라틴 {@code a})·제로폭 문자·이모지·공백이 <b>원천 차단</b>된다 — 예약어 목록을
 * 아무리 늘려도 못 막는 우회 경로가 문자셋 하나로 닫힌다.
 *
 * <p><b>정규화 후 판정한다</b>({@link #normalize}) — 대소문자·구분자·전각/호환문자·리트(leet) 치환을
 * 모두 접어서 {@code "P U N G_D0NG"} 과 {@code "pungdong"} 이 같은 값이 되게 한다. 형식 가드가 이미
 * 공백·특수문자를 막지만, 정규화는 <b>형식을 통과한 값끼리의 우회</b>(밑줄·숫자 섞기)를 잡는다.
 *
 * <p>기존 계정은 재검증하지 않는다 — 이 정책은 <b>가입·닉네임 변경 시점</b>에만 적용된다. 이미 예약어를
 * 가진 계정은 {@code account/audit} 리포트로 잡아 개별 안내한다(자동 변경 안 함).
 */
public final class NickNamePolicy {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 16;

    /**
     * 한글(완성형)·영문·숫자·밑줄만, 2~16자. 자모 단독({@code ㅋㅋㅋ})·공백·이모지·구두점은 거부.
     * <p>{@code @Pattern(regexp = NickNamePolicy.PATTERN)} 로 DTO 에서 쓴다(컴파일 상수 연결).
     */
    public static final String PATTERN = "^[0-9A-Za-z가-힣_]{" + MIN_LENGTH + "," + MAX_LENGTH + "}$";

    /** FE 가 그대로 노출하는 사용자 문구 — 형식 규칙은 공개 정보라 어느 필드가 왜 틀렸는지 밝혀도 안전. */
    public static final String MESSAGE =
            "닉네임은 " + MIN_LENGTH + "~" + MAX_LENGTH + "자의 한글·영문·숫자·밑줄(_)만 쓸 수 있습니다.";

    /**
     * 예약어 거부 문구. <b>어떤 단어가 예약어인지는 알려주지 않는다</b> — 목록을 노출하면 우회 사전이 된다.
     * (형식 오류와 달리 여기서만 비대칭을 두는 이유: 형식은 규칙 자체가 공개 계약이지만, 예약어 목록은
     * 운영 정책이라 언제든 늘어난다.)
     */
    public static final String RESERVED_MESSAGE = "사용할 수 없는 닉네임입니다.";

    private static final Pattern FORMAT = Pattern.compile(PATTERN);

    /**
     * <b>어디에 들어가도</b> 차단 — 브랜드명과 한글 역할어. 한글은 단어 경계가 없어 부분일치가 정답이다
     * ({@code "우리동네관리자"} 도 사칭). 라틴 브랜드어({@code pungdong}·{@code plop})는 일반 단어에
     * 우연히 포함될 일이 없어 같이 둔다.
     */
    private static final List<String> BLOCKED_ANYWHERE = List.of(
            // 브랜드 — 풍덩 / 법인 plop
            "풍덩", "pungdong", "plop",
            // 운영 주체 사칭
            "관리자", "어드민", "운영자", "운영팀", "운영진", "관리팀",
            "고객센터", "고객지원", "공식계정", "공식운영");

    /**
     * <b>정확일치 또는 접두</b>일 때만 차단 — 라틴 역할어. 부분일치로 하면 {@code badminton} 이
     * {@code admin} 에 걸리는 식의 오탐이 난다. 접두까지 보는 건 {@code admin_kim}·{@code officialdiver}
     * 같은 흔한 사칭형을 잡기 위해서다.
     *
     * <p>{@code master} 는 뺐다 — {@code masterdiver} 는 실제 다이빙 등급 표기라 오탐 비용이 크다
     * (단독 {@code master} 만 {@link #RESERVED_EXACT} 에서 막는다).
     */
    private static final List<String> BLOCKED_PREFIX = List.of(
            "admin", "official", "support", "staff", "manager", "moderator",
            "system", "root", "helpdesk", "notice");

    /**
     * <b>정확일치</b>만 차단 — (1) {@code /instructors/*} 네임스페이스의 리터럴 경로와 앞으로 생길 만한
     * 경로, (2) 시스템/자리표시 값, (3) 단독으로 쓰면 사칭이지만 부분일치로 막으면 오탐이 큰 한글 단어.
     */
    private static final Set<String> RESERVED_EXACT = Set.of(
            // 라우트 — 지금 실제로 부딪히는 값(public·suggested) + 이 네임스페이스에 생길 만한 값
            "public", "suggested", "me", "new", "search", "about", "help", "login", "logout",
            "signup", "signin", "api", "docs", "account", "accounts", "instructor", "instructors",
            "course", "courses", "community", "branding", "settings", "profile", "home",
            "terms", "privacy", "faq", "contact", "event", "events",
            // 시스템 / 자리표시
            "null", "undefined", "none", "test", "guest", "user", "users", "anonymous", "master",
            // 한글 단독어 — 부분일치로 막으면 오탐이 큰 것들
            "공식", "운영", "관리", "스탭", "스태프", "매니저", "마스터", "시스템",
            "익명", "탈퇴", "공지", "알림");

    private NickNamePolicy() {
    }

    /** 형식(길이·문자셋) 통과 여부. */
    public static boolean isValidFormat(String nickName) {
        return nickName != null && FORMAT.matcher(nickName).matches();
    }

    /** 예약어 여부 — 정규화 후 판정하므로 {@code "P_U_N_G_D0NG"} 도 걸린다. */
    public static boolean isReserved(String nickName) {
        String normalized = normalize(nickName);
        if (normalized.isEmpty()) {
            return false;
        }
        if (RESERVED_EXACT.contains(normalized)) {
            return true;
        }
        for (String blocked : BLOCKED_ANYWHERE) {
            if (normalized.contains(blocked)) {
                return true;
            }
        }
        for (String blocked : BLOCKED_PREFIX) {
            if (normalized.startsWith(blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 예약어 판정용 정규화 — NFKC(전각·호환문자 접기) → 소문자 → 리트 치환 → 한글/영문/숫자 외 제거.
     *
     * <p>구분자를 <b>지우는</b> 게 핵심이다. {@code "풍_덩"}·{@code "a.d.m.i.n"} 처럼 사이에 뭘 끼워
     * 넣는 게 가장 흔한 우회다. 리트 치환({@code 0→o, 1→l, 3→e, 4→a, 5→s, 7→t})은 {@code "pungd0ng"}
     * 류를 접는다 — 숫자가 글자로 접히므로 이 값은 <b>표시용이 아니라 판정 전용</b>이다.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = leet(folded.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c >= '가' && c <= '힣')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static char leet(char c) {
        switch (c) {
            case '0': return 'o';
            case '1': return 'l';
            case '3': return 'e';
            case '4': return 'a';
            case '5': return 's';
            case '7': return 't';
            default: return c;
        }
    }
}
