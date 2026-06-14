package com.diving.pungdong.course;

import lombok.*;

import javax.persistence.*;

/**
 * 코스 미디어 1건 — 강의상세 상단 캐로셀(0번 = 커버, 첫 칸 영상 가능). url 은 /course-images 로 먼저
 * 업로드해 받은 값. 이번 단계는 사진만(영상 업로드 후속).
 */
@Entity
@Table(name = "course_media")
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class CourseMedia {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @Enumerated(EnumType.STRING)
    private MediaKind kind;

    private String url;

    private int sortOrder;
}
