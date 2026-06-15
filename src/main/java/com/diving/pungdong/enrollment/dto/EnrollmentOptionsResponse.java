package com.diving.pungdong.enrollment.dto;

import com.diving.pungdong.course.CertLevel;
import com.diving.pungdong.venue.VenueType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 수강신청 옵션 — V2 booking 의 일정/위치/시간/장비 선택지. <b>BE 가 교집합(강사 availability ∩ venue
 * 운영블록 ∩ 코스 회차 위치)을 한 번에 계산해 평탄 {@link Slot} 집합으로 내려준다.</b> UX 순서(날짜→위치→시간)는
 * FE 가 이 평탄 집합을 그룹핑해 표현 — 계산 순서와 분리.
 *
 * <p>각 슬롯은 자기 기술적(self-describing): 신청 시 {@code windowId·venueRefId·ticketRef·blockStart·blockEnd}
 * 를 그대로 echo 한다. 장비는 위치별({@link #equipmentByVenue}).
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class EnrollmentOptionsResponse {

    private CourseSummary course;
    /** 평탄 슬롯 집합(날짜×위치×시간블록). FE 가 날짜→위치→시간으로 그룹핑. */
    private List<Slot> slots;
    /** 위치별 대여 장비(venueRefId → 아이템들). */
    private Map<String, List<EquipmentOption>> equipmentByVenue;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CourseSummary {
        private Long id;
        private String title;
        private String disciplineCode;
        private Set<CertLevel> levels;
        /** 수강료(원). */
        private int price;
        /** 신청 대상 회차 라벨 — v1 "1회차 · 첫 만남". */
        private String roundLabel;
        private Long instructorId;
        private String instructorName;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Slot {
        /** 신청 시 echo — 이 슬롯을 받치는 강사 가용시간. */
        private Long windowId;
        private LocalDate date;
        private String venueRefId;
        private String venueName;
        private VenueType venueType;
        /** 도로명주소(시안의 area). */
        private String area;
        private LocalTime blockStart;
        private LocalTime blockEnd;
        /** 표시 라벨 — "14:00–17:00". */
        private String sessionLabel;
        private String ticketRef;
        /** 입장료(이용권 × 그 날짜 평일/주말 daypart fee, 원). */
        private int entryFee;
        private int capacity;
        /** 남은 자리 = capacity − 확정 − 외부 hold. */
        private int remaining;
        private boolean full;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EquipmentOption {
        private String itemRef;
        private String name;
        private int price;
        /** 사이즈 형식(있으면 FE 가 사이즈 칩 노출). v1 은 사이즈 캡처 후속이라 표시만. */
        private String sizeFormat;
        private List<String> sizeOptions;
    }
}
