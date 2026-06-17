package com.diving.pungdong.availability;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 일정(session) — 위치·정원·사람을 가진 점유 레이어. 예약가능시간({@link AvailabilityCoverage}) 위에 놓인
 * 한 (위치, 시간블록) 세션이다. 강사가 원자적으로 추가하거나(외부예약/수동) 학생 첫 신청으로 생성된다.
 *
 * <p><b>정체성 = (instructor, date, venueRefId, startTime, endTime)</b>. 같은 (위치,시간)에 점유를 또 추가하면
 * 새 session 이 아니라 기존 session 에 누적(외부 hold 또는 enrollment join). 부분겹침 없이 venue 운영 블록
 * 단위로만 존재.
 *
 * <p>정원: {@code capacityOverride==null} 이면 강사 {@code Account.defaultCapacity} 를 라이브로 따른다(PR #69
 * 모델). 점유 = 외부/수동 {@link AvailabilityHold} + 풍덩 enrollment(enrollment 도메인 소유).
 */
@Entity
@Table(name = "availability_session")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class AvailabilitySession {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id")
    private Account instructor;

    private LocalDate date;
    private LocalTime startTime;
    private LocalTime endTime;

    /**
     * 이 일정만의 정원 override(sparse). null = 계정 기본값({@code Account.defaultCapacity}) 라이브 참조,
     * 값 있음 = 그 날만 고정. 유효정원 = {@link #effectiveCapacity()}.
     */
    private Integer capacityOverride;

    /** "CUSTOM:&lt;pk&gt;" | "OFFICIAL:&lt;sanityId&gt;". 위치 없는 점유(±/일반 바쁨)면 null. */
    private String venueRefId;

    /** "1부"/"2부"/"오후" 같은 세션 라벨(선택). */
    private String sessionLabel;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id asc")
    @Builder.Default
    private List<AvailabilityHold> holds = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void addHold(AvailabilityHold h) {
        h.setSession(this);
        this.holds.add(h);
    }

    /** 외부/수동 점유 합계 — 모든 hold 의 인원 합(±빠른조정 + 외부예약). */
    public int heldCount() {
        return holds.stream().mapToInt(AvailabilityHold::getCount).sum();
    }

    /**
     * 유효정원 = override 가 있으면 그 값, 없으면 강사 계정 기본값({@code instructor.effectiveDefaultCapacity()}).
     * {@code instructor} 는 LAZY 라 트랜잭션 안에서 호출.
     */
    public int effectiveCapacity() {
        if (capacityOverride != null) {
            return capacityOverride;
        }
        return instructor != null ? instructor.effectiveDefaultCapacity() : Account.DEFAULT_CAPACITY;
    }

    /** 그 날만 직접 정한 값이 있는지(= override). FE 배지·"기본값 따르기" 노출용. */
    public boolean isCapacityOverridden() {
        return capacityOverride != null;
    }
}
