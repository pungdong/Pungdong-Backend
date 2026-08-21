package com.diving.pungdong.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 강의 상세에 인라인되는 강사 요약 — 상세 화면의 강사 카드(아바타 · 이름 · 인증마크 · 한마디)를
 * <b>추가 호출 없이</b> 그리기 위한 것.
 *
 * <p><b>왜 인라인하나.</b> 예전엔 상세 응답에 {@code instructorName}(= 닉네임)만 있어서 클라이언트가
 * {@code GET /instructors/{nickName}} 을 한 번 더 불러야 했다. 닉네임을 상세 응답에서 얻어야 하니
 * <b>병렬화가 불가능한 순차 왕복</b>이고, 그 엔드포인트는 프로필 미발행이면 400 이라 폴백 분기까지
 * 따라다녔다. 그런데 여기 실리는 값 중 <b>브랜딩 프로필이 실제로 소유하는 건 tagline·bio 뿐</b>이다 —
 * 아바타는 {@code account}, 인증마크·자격은 {@code instructorapplication} 소유라 프로필을 만든 적
 * 없는 강사도 값이 있다. 브랜딩 엔드포인트 뒤에 있었다는 이유로 같이 잠겨 있었을 뿐이다.
 *
 * <p><b>모양은 커뮤니티 작성자 카드와 맞춘다</b>({@code CommunityAuthorResponse}) — 같은 "강사 칩"
 * UI 라 필드가 갈리면 클라이언트가 컴포넌트를 두 벌 들고 다녀야 한다.
 */
@Getter
@Builder
public class CourseInstructorResponse {

    /** 공개 프로필 진입 키 — 클라이언트가 {@code GET /instructors/{nickName}} 으로 그대로 쓴다. */
    private final String nickName;

    /** 미설정이면 null — 클라이언트가 기본 아바타를 그린다({@code ProfilePhoto.displayUrl}). */
    private final String avatarUrl;

    /**
     * <b>승인된</b> 강사 여부 = 인증마크. <b>항상 내려간다</b>(생략하지 않는다) — 없으면 클라이언트가
     * "아직 안 온 것" 과 "승인 전인 것" 을 구분할 수 없다.
     *
     * <p>강의를 가진 사람이면 당연히 true 일 것 같지만 아니다 — 강의 준비는 <b>승인 전(신청 보유)</b>
     * 에도 열려 있어서, 심사 중인 사람의 강의가 여기 올 수 있다. 인증마크는 승인에만 붙는다.
     *
     * <p>⚠️ 필드명이 {@code instructor} 인 게 핵심이다. Lombok 게터가 {@code isInstructor()} 라
     * Jackson 이 암묵 프로퍼티 {@code "instructor"} 로 보는데, 필드까지 {@code isInstructor} 로 두면
     * 암묵 이름이 갈려 <b>{@code {"instructor":…, "isInstructor":…}} 두 키가 모두</b> 나간다.
     */
    @JsonProperty("isInstructor")
    private final boolean instructor;

    /** 한 줄 소개("강사의 한마디"). 프로필 미작성·미입력이면 null. */
    private final String tagline;

    /** 자기소개 본문. 프로필 미작성·미입력이면 null. */
    private final String bio;

    /**
     * 자격 뱃지 — 승인된 강사 신청에서 파생한다(자유입력이 아니다: 허위 자격은 안전 문제로 번진다).
     * <b>승인 전이면 키 자체가 없다</b> — 빈 배열은 "자격 없는 강사" 로 읽힌다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<CertBadge> certs;

    /**
     * 공개 강의 수 — "강사 · 강의 N" 칩. <b>승인 전이면 키 자체가 없다.</b> 브랜딩
     * {@code products.lessons} · 커뮤니티 칩과 <b>같은 규칙</b>(데모 시드 노출 설정까지 동일)이라
     * 같은 강사의 숫자가 화면마다 어긋나지 않는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Integer lessonCount;

    /**
     * 자격 뱃지 1개. {@code branding} 의 같은 이름 타입과 필드가 같지만 <b>여기서 다시 선언한다</b> —
     * {@code branding → course} 단방향이라 {@code course} 는 {@code branding} 을 import 할 수 없다
     * (반대로 걸면 패키지 순환).
     */
    @Getter
    @Builder
    public static class CertBadge {
        private final String disciplineCode;
        private final String organizationCode;
        /** {@code organizationCode} 가 "OTHER" 일 때 직접입력 단체명. */
        private final String organizationOther;
    }
}
