package com.diving.pungdong.course;

import com.diving.pungdong.account.Account;
import lombok.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import javax.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 강의 북마크(저장) — 마커 행. 구조는 {@code CommunityPostBookmark} 와 <b>같다</b>(그 도메인의 판단을
 * 그대로 옮긴 것): {@code (대상, 계정)} UNIQUE 로 멱등을 얻고, 상태 컬럼 없이 행의 유무가 곧 상태다.
 *
 * <p><b>왜 별도 테이블인가</b> — 저장은 "나만 보는 책갈피"라 수강신청({@code enrollment})과 수명이 다르다.
 * 저장했다고 신청한 게 아니고, 신청을 취소했다고 저장이 풀리는 것도 아니다. 두 개를 한 테이블에 상태
 * 컬럼으로 합치면 "저장한 강의" 목록이 항상 그 필터를 달고 다녀야 하고, 한쪽에만 필드가 붙을 때
 * (예: 저장 폴더) 서로를 오염시킨다.
 *
 * <p>{@code (account_id, created_at)} 인덱스는 "저장한 강의" 목록이 계정 기준으로 읽히기 때문이다.
 */
@Entity
@Table(name = "course_bookmark",
        uniqueConstraints = @UniqueConstraint(name = "uk_course_bookmark",
                columnNames = {"course_id", "account_id"}))
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CourseBookmark {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
