package com.diving.pungdong.community;

import com.diving.pungdong.branding.BrandingPost;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 같이가요({@code MATCH}) 글의 모집 정보 — 게시물과 1:1.
 *
 * <p><b>왜 메인 테이블 컬럼이 아닌가.</b> 4개 카테고리 중 하나에만 있는 필드라 메인 테이블에 nullable 로
 * 붙이면 전체 글의 일부에만 값이 있는 컬럼이 상주하고, "MATCH 면 NOT NULL" 을 DB 로 표현할 수 없다.
 * JSON 컬럼도 기각했다 — {@code meetDate} 로 정렬·마감 판정을 하는데 JSON 은 색인이 안 걸려 풀스캔이 된다
 * (태그를 JSON 이 아니라 자식 행으로 둔 것과 같은 이유).
 *
 * <p><b>일정은 civil time 이다.</b> {@link LocalDate}/{@link LocalTime} 으로 오프셋 없이 저장한다 —
 * 다이브 포인트의 벽시계 시각이라 뷰어 타임존으로 변환하면 안 된다. (절대시각인 {@code createdAt} 과
 * 다른 축이다. docs/architecture/time-handling.md)
 *
 * <p><b>참여자 개념은 없다.</b> "참여 신청" 은 별도 기능으로 만들지 않기로 확정됐다 — 신청류는 기존
 * 수강신청(예약) 플로우로 간다. 그래서 {@code capacity} 는 있지만 참여자 테이블도 joinedCount 도 없고,
 * 클라이언트는 모집 칸을 "N명 모집" 으로 렌더한다.
 */
@Entity
@Table(name = "community_post_match")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "postId")
public class CommunityPostMatch {

    /**
     * 게시물 id 를 그대로 PK 로 쓴다(1:1). 별도 시퀀스를 둘 이유가 없다.
     *
     * <p>{@code @Column} 을 달지 않는다 — 컬럼은 아래 {@code @MapsId} 의 {@code @JoinColumn} 이 정의한다.
     * 둘 다 {@code post_id} 를 선언하면 Hibernate 가 "Repeated column in mapping" 으로 부팅에 실패한다.
     */
    @Id
    private Long postId;

    /** 빌더로 만들 때는 {@code post} 만 넣으면 된다 — {@code postId} 는 {@code @MapsId} 가 채운다. */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id")
    private BrandingPost post;

    @Column(name = "meet_date", nullable = false)
    private LocalDate meetDate;

    /** 입수 시각. 선택 — 날짜만 정하고 시간은 협의하는 모집도 있다. */
    @Column(name = "meet_time")
    private LocalTime meetTime;

    /** 모집 정원. 참여자를 세지 않으므로 표시 전용 값이다. */
    @Column(nullable = false)
    private int capacity;

    /** 요구 자격 자유 텍스트 — "AOWD 이상 · 보트다이빙 경험" 처럼 등급과 조건이 섞여 온다. */
    @Column(name = "level_label", length = 60)
    private String levelLabel;

    /**
     * 모집이 아직 열려 있나. <b>파생값이고 저장하지 않는다.</b>
     *
     * <p>정원 대비 신청자를 세지 않으므로(참여 신청 기능 없음) 판정 기준은 일정뿐이다. 지난 모집글을
     * 멀쩡해 보이게 두지 않으려고 존재하는 값이지 뱃지용이 아니다 — 클라이언트는 이걸로 지난 글을
     * 흐리게 처리한다.
     */
    public boolean isOpen() {
        return !meetDate.isBefore(LocalDate.now());
    }
}
