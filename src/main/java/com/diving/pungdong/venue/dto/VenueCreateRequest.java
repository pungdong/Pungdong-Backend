package com.diving.pungdong.venue.dto;

import com.diving.pungdong.venue.*;
import lombok.*;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 강사 커스텀(CUSTOM) 위치 생성/수정 요청. {@code owner} 는 바디가 아니라 컨트롤러가 현재 계정으로
 * 주입한다. 조건부 규칙(FIXED→블록, 종목 잠금 일치 등)은 bean validation 으로 다 못 잡으므로
 * {@code VenueService} 에서 검증한다. (공식 위치는 BE 아님 — Sanity authoring.)
 *
 * <p>중첩 DTO 를 한 파일에 모은 건, Spring 초심자가 요청 모양(이용 옵션 = 평일/주말 daypart = 시간블록)을
 * 한눈에 스펙처럼 읽게 하기 위함.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class VenueCreateRequest {

    @NotNull
    private String name;

    @NotNull
    private VenueType type;

    /** 정식 도로명주소 (위/경도 기준). */
    private String address;
    /** 세부주소 (동·호수 등, 선택). geocoding 대상 아님. */
    private String addressDetail;
    private Double latitude;
    private Double longitude;
    /** 최대수심(m, 선택). */
    private Integer maxDepth;

    /** 위치가 잠길 종목 코드 (필수). 모든 이용 옵션이 이 종목으로 고정된다. */
    private String lockedDisciplineCode;

    @Valid
    @Builder.Default
    private List<Closure> closures = new ArrayList<>();

    @NotEmpty
    @Valid
    @Builder.Default
    private List<Ticket> tickets = new ArrayList<>();

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Ticket {
        private String name;
        private int sortOrder;
        /** 적용 종목 코드들. CUSTOM 은 lockedDisciplineCode 로 강제(불일치 시 400). */
        @Builder.Default
        private List<String> disciplineCodes = new ArrayList<>();
        @Valid
        @NotEmpty
        @Builder.Default
        private List<Daypart> dayparts = new ArrayList<>();
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Daypart {
        @NotNull
        private DaypartKind kind;
        private boolean sold;
        private Integer fee;
        private TimeMode timeMode;
        /** OPEN 모드. */
        private LocalTime openStart;
        private LocalTime openEnd;
        private Integer holdHours;
        /** FIXED 모드 — 시간블록 리스트. */
        @Valid
        @Builder.Default
        private List<TimeBlock> timeBlocks = new ArrayList<>();
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TimeBlock {
        @NotNull
        private LocalTime startTime;
        @NotNull
        private LocalTime endTime;
        private int sortOrder;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Closure {
        @NotNull
        private ClosureType type;
        /** WEEKLY — 매주 X·Y요일. */
        @Builder.Default
        private List<DayOfWeek> weekdays = new ArrayList<>();
        /** MONTHLY — 몇째 주 1건 (1~5). "2·4주"는 MONTHLY 항목 2개로. */
        private Integer nth;
        /** MONTHLY — 요일 1개. */
        private DayOfWeek monthlyWeekday;
    }
}
