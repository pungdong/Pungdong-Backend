package com.diving.pungdong.branding.dto;

import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.util.List;

/**
 * 강사 둘러보기 카드 1칸 — {@code GET /instructors/browse} 의 {@code _embedded.instructors} 원소.
 *
 * <p><b>{@code id} 를 싣지 않는다.</b> 공개 표면의 강사 식별자는 순번이 아니라 닉네임이다(레포 규약:
 * 공개 "fetch by id" 는 비순차 handle 로). 카드를 누르면 {@code GET /instructors/{nickName}} 으로 가고,
 * 모수가 발행된 프로필만이라 그 상세는 <b>반드시 열린다</b>. {@code /instructors/suggested} 와 같은 형태.
 *
 * <p><b>없는 필드가 있는 게 정상이다</b> — 평점·후기수(리뷰 도메인이 없다) · 투어 수 · 자격 등급 텍스트
 * (강사 쪽에 등급 필드가 없다) · 인증 뱃지(모수가 이미 APPROVED 라 늘 참). 디자인에 있던 것들이고
 * 신호가 생기면 그때 붙인다.
 *
 * <p>nullable 3종({@code avatarUrl}·{@code tagline}·{@code locationLabel})은 <b>키를 생략하지 않고
 * {@code null} 로 내보낸다</b> — 클라이언트가 "아직 안 온 값" 과 "유저가 비운 값" 을 구분할 필요가 없고,
 * 키가 사라지면 타입이 흔들린다.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "instructors")
public class InstructorBrowseCardResponse {

    /** 공개 핸들 겸 프로필 경로 값. */
    private String nickName;

    /** 프로필 사진. 미설정이면 null. */
    private String avatarUrl;

    /** 한 줄 소개({@code AccountBranding.tagline}). 유저가 비웠으면 null. */
    private String tagline;

    /**
     * 활동지역 자유 텍스트(예 "잠실 · 송파"). <b>{@code venue.Region} 이 아니다</b> —
     * 필터·칩으로 쓰면 안 되고 표시 전용이다.
     */
    private String locationLabel;

    /** 그 강사의 <b>승인 종목 전부</b>(요청 종목만이 아니다). 모수상 최소 1개. */
    private List<String> disciplineCodes;

    /** <b>요청 종목</b> 승인 신청에 달린 자격증 단체 코드(중복 제거). 자격증이 필요 없는 종목이면 빈 배열. */
    private List<String> organizationCodes;

    /**
     * <b>요청 종목</b>의 공개중 강의 수. "공개중" 의 정의는 강의 둘러보기가 실제로 보여주는 것과 같다
     * (OPEN · 미차단 · 데모 가림 설정 반영) — 아니면 "강의 3" 카드를 눌렀는데 목록이 0건이 된다.
     */
    private long openCourseCount;
}
