package com.diving.pungdong.course.dto;

import com.diving.pungdong.course.*;
import com.diving.pungdong.venue.Region;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import org.springframework.hateoas.server.core.Relation;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 둘러보기 강의 카드 — 수강생 메인 홈 가로 스크롤/더보기 리스트 한 칸. 상세({@link CourseResponse})와 달리
 * 회차·장비·설명은 빼고 카드 표면에 필요한 것만(썸네일·종류/단체/레벨 칩·강사·위치·가격·회차수). 위치명/지역은
 * 저장 시점 비정규화된 값을 그대로 — 읽기 시 위치 재해석(N+1) 안 함.
 *
 * <p>카드의 {@code org}/{@code level} 칩: 자격(CERTIFICATION)은 단체코드+레벨, 체험/트레이닝은 {@code kind}
 * 자체가 칩 라벨이라 FE 가 {@code kind} 로 분기. CollectionModel/PagedModel 키 = "courses".
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@Relation(collectionRelation = "courses")
public class CourseCardResponse {

    private Long id;
    private String title;
    /** 커버 이미지 url(미디어 0번) — 없으면 null. */
    private String thumbnailUrl;
    private CourseKind kind;
    /** CERTIFICATION 한정 — 자격증 단체 코드. */
    private String organizationCode;
    /** CERTIFICATION 한정 — 목표 평탄화 레벨. */
    private Set<CertLevel> levels;
    @JsonProperty("isPackage")
    private boolean isPackage;
    private Long instructorId;
    private String instructorName;
    /** 대표 위치 이름(카드 location). */
    private String locationName;
    /** 회차 위치들이 속한 지역 묶음(들). */
    private Set<Region> regions;
    private int price;
    private int totalRounds;
    private String disciplineCode;
    /** 데모(샘플) 코스 — FE 가 "샘플용" 태그로 구분 노출. */
    private boolean seeded;
    private OffsetDateTime createdAt;

    public static CourseCardResponse from(Course c) {
        return CourseCardResponse.builder()
                .id(c.getId())
                .title(c.getTitle())
                .thumbnailUrl(c.getMedia().isEmpty() ? null : c.getMedia().get(0).getUrl())
                .kind(c.getKind())
                .organizationCode(c.getOrganizationCode())
                .levels(c.getLevels())
                .isPackage(c.isPackage())
                .instructorId(c.getInstructor() == null ? null : c.getInstructor().getId())
                .instructorName(c.getInstructor() == null ? null : c.getInstructor().getNickName())
                .locationName(c.getPrimaryLocationName())
                .regions(c.getRegions())
                .price(c.getPrice())
                .totalRounds(c.getTotalRounds())
                .disciplineCode(c.getDisciplineCode())
                .seeded(c.isSeeded())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
