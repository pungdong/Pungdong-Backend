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
    /**
     * 강사 프로필 사진 — 카드의 강사명 앞 원형 아바타. 미설정이면 null.
     *
     * <p>추가 비용은 <b>페이지당 쿼리 1개</b>다: {@code Account.profilePhoto} 는 소유측
     * {@code @OneToOne(LAZY)} 이고 {@code default_batch_fetch_size: 100} 이라 한 페이지의 강사 사진이
     * IN 절 하나로 함께 온다(강사 프로필/추천 카드가 이미 쓰는 접근 패턴). 카드마다 따로 나가지 않는다.
     */
    private String instructorAvatarUrl;
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

    /**
     * 저장(북마크) 수. <b>내려주지만 노출은 FE 가 결정한다</b> — "N명이 저장" 은 판매 신호지만 초기라
     * 숫자가 낮으면 역효과라서, 표시를 끄는 게 필드를 빼는 것보다 되돌리기 쉽다. 인기순 정렬 신호로도
     * 쓸 수 있다(지금 {@code Sort} 에는 없다).
     */
    private long bookmarkCount;

    /**
     * 내가 저장했는지. <b>토큰이 있을 때만 의미가 있다</b> — 둘러보기는 공개라 비로그인은 에러가 아니라
     * 조용히 {@code false} 다. FE 가 캐시 때문에 토큰리스로 읽는 경로에서도 같으니, 개인화가 필요한
     * 표면은 하이드레이션으로 다시 읽어야 한다(커뮤니티에서 이미 밟은 함정).
     */
    private boolean bookmarkedByMe;

    public static CourseCardResponse from(Course c, long bookmarkCount, boolean bookmarkedByMe) {
        return CourseCardResponse.builder()
                .bookmarkCount(bookmarkCount)
                .bookmarkedByMe(bookmarkedByMe)
                .id(c.getId())
                .title(c.getTitle())
                .thumbnailUrl(c.getMedia().isEmpty() ? null : c.getMedia().get(0).getUrl())
                .kind(c.getKind())
                .organizationCode(c.getOrganizationCode())
                .levels(c.getLevels())
                .isPackage(c.isPackage())
                .instructorId(c.getInstructor() == null ? null : c.getInstructor().getId())
                .instructorName(c.getInstructor() == null ? null : c.getInstructor().getNickName())
                .instructorAvatarUrl(c.getInstructor() == null || c.getInstructor().getProfilePhoto() == null
                        ? null : c.getInstructor().getProfilePhoto().getImageUrl())
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
