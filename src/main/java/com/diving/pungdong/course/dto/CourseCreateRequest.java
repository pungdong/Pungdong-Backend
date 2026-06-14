package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.course.CourseKind;
import com.diving.pungdong.course.MediaKind;
import com.diving.pungdong.venue.DaypartKind;
import lombok.*;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 코스 생성/수정 요청 — 기본정보 + 회차(설명·위치·이용권 변형) + (선택)추가세션. 중첩 DTO 를 한 파일에
 * 모아 요청 모양을 스펙처럼 읽게 한다(venue 패턴). 조건부 규칙(CERTIFICATION→레벨·단체, 회차 수 일치,
 * venueRefId 검증)은 {@code CourseService} 에서.
 *
 * <p>미디어 url 은 {@code POST /course-images} 로 먼저 업로드해 받은 값. 위치는 {@code venueRefId}
 * ({@code GET /venues/builder} 항목의 토큰).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class CourseCreateRequest {

    @NotNull
    private String title;

    @NotNull
    private CourseKind kind;

    /** CERTIFICATION 필수(Sanity certOrg.code). */
    private String organizationCode;

    @NotNull
    private String disciplineCode;

    /** CERTIFICATION 필수(>=1). 2개 이상이면 패키지. 비-자격은 비움. */
    @Builder.Default
    private Set<CertLevel> levels = new LinkedHashSet<>();

    @Positive
    private int totalRounds;

    @PositiveOrZero
    private int price;

    private String description;

    @Valid
    @Builder.Default
    private List<Media> media = new ArrayList<>();

    /** 정규 회차 — 개수가 totalRounds 와 일치해야 함. 순서대로 1회차..N회차. */
    @Valid
    @NotNull
    @Builder.Default
    private List<Round> rounds = new ArrayList<>();

    /** 추가세션(선택). 없으면 추가세션 없는 강의. */
    @Valid
    private ExtraSession extraSession;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Media {
        @NotNull
        private MediaKind kind;
        @NotNull
        private String url;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Round {
        /** 회차 설명(회차별, 공통 아님). */
        private String description;
        @Valid
        @Builder.Default
        private List<Venue> venues = new ArrayList<>();
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ExtraSession {
        private String description;
        /** N회까지 무료(0 = 처음부터 유료). */
        @PositiveOrZero
        private int freeCount;
        /** 무료 소진 후 회당 가격(원). */
        @PositiveOrZero
        private int perSessionPrice;
        @Valid
        @Builder.Default
        private List<Venue> venues = new ArrayList<>();
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Venue {
        /** "CUSTOM:<pk>" | "OFFICIAL:<sanityId>". */
        @NotNull
        private String venueRefId;
        @Valid
        @Builder.Default
        private List<Ticket> tickets = new ArrayList<>();
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Ticket {
        /** 위치 내 이용권 식별자(빌더 응답이 준 값). */
        @NotNull
        private String ticketRef;
        @NotNull
        private DaypartKind daypart;
    }
}
