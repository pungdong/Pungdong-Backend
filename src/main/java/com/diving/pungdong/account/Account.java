package com.diving.pungdong.account;

import com.diving.pungdong.domain.LectureMark;
import com.diving.pungdong.domain.lecture.Lecture;
import com.diving.pungdong.domain.review.Review;
import lombok.*;

import javax.persistence.*;
import javax.validation.constraints.Email;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Getter @Setter @EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor @AllArgsConstructor
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Email
    private String email;

    private String password;

    /**
     * OAuth 공급자에서 발급한 사용자 식별값. provider != EMAIL 인 경우 not null.
     * (provider, socialId) 조합이 사실상의 식별 키 — DB 유니크 제약은 OAuth 통합 PR에서.
     */
    private String socialId;

    /**
     * 가입 경로. EMAIL = 직접 가입 (이메일 + 비밀번호), 그 외 = OAuth.
     * @PrePersist 에서 null이면 EMAIL 로 기본값 부여 — 기존 데이터 호환.
     */
    @Enumerated(EnumType.STRING)
    private AuthProvider provider;

    private String nickName;

    private String birth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @OneToOne(fetch = FetchType.LAZY)
    private ProfilePhoto profilePhoto;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    Set<Role> roles = new HashSet<>();

    private String phoneNumber;

    /**
     * 강사 자기소개. 현재는 강의 상세(LectureCreatorInfo)에서 읽기만 한다 — 이 값을 쓰던 레거시
     * 강사정보 입력(/sign/instructor/info)이 제거되어 신규로는 채워지지 않는다. lecture 도메인
     * 재설계 시 함께 정리 예정.
     */
    @Lob
    private String selfIntroduction;

    private Boolean isCertified;

    private Boolean isDeleted;

    @OneToMany(mappedBy = "instructor", fetch = FetchType.LAZY)
    private List<Lecture> lectureList;

    @OneToMany(mappedBy = "instructor", fetch = FetchType.LAZY)
    private List<InstructorCertificate> instructorCertificates;

    @OneToMany(mappedBy = "account", fetch = FetchType.LAZY)
    private List<LectureMark> lectureMarks;

    @OneToMany(mappedBy = "writer", fetch = FetchType.LAZY)
    private List<Review> reviews;

    @PrePersist
    public void prePersist() {
        this.isCertified = this.isCertified != null && this.isCertified;
        this.isDeleted = false;
        this.provider = this.provider == null ? AuthProvider.EMAIL : this.provider;
    }
}
