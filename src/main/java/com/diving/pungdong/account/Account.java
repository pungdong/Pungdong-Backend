package com.diving.pungdong.account;

import lombok.*;

import javax.persistence.*;
import javax.validation.constraints.Email;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Getter @Setter @EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor @AllArgsConstructor
public class Account {

    /** 강사 기본 수용 인원 기본값 — 신규 강사가 시작하는 값(이후 본인이 조정). 폴백 상수. */
    public static final int DEFAULT_CAPACITY = 4;

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
     * 강사 자기소개. 이 값을 쓰던 레거시 강사정보 입력(/sign/instructor/info)과 유일한 읽기처였던
     * 레거시 강의 상세(LectureCreatorInfo)가 모두 제거되어 <b>현재는 쓰이지 않는다</b>(컬럼만 잔존).
     * 강사 프로필/브랜딩 쪽에서 자기소개를 다시 쓰게 되면 그때 이 필드를 재사용할지, 컬럼째 드롭할지
     * 결정한다 — 드롭은 마이그레이션이 필요하므로 이번 레거시 청산 스윕 범위 밖.
     */
    @Lob
    private String selfIntroduction;

    private Boolean isCertified;

    private Boolean isDeleted;

    /**
     * 회원탈퇴(soft delete) 시각. {@link #isDeleted}=true 가 된 순간 기록.
     * 익명화 유예기간(grace)의 기준점 — 이 시각 + grace 가 지나면 PII 가 익명화된다.
     */
    private OffsetDateTime deletedAt;

    /**
     * PII 익명화 완료 시각. null = 아직 식별정보 보유(유예기간 내 — 복구 가능).
     * non-null = 익명화 완료(복구 불가). 익명화 잡의 멱등 가드로도 쓰인다.
     * 자세한 정책·보존 항목은 docs/features/account-deletion.md.
     */
    private OffsetDateTime anonymizedAt;

    /**
     * 강사가 한 일정에서 동시에 수용 가능한 기본 인원 — "내가 커버 가능한 인원"의 단일 출처(account 종속).
     * 가용시간(window)은 이 값을 <b>스냅샷이 아니라 라이브로 참조</b>한다: 개별 override 가 없는 일정의
     * 유효정원 = 이 값. 바꾸면 override 안 한 일정들이 즉시 따라간다(전파 로직 불필요 — 저장이 아니라 참조라서).
     * 학생 계정엔 의미 없으나 무해. null 이면 {@link #DEFAULT_CAPACITY} 폴백({@link #effectiveDefaultCapacity()}).
     */
    private Integer defaultCapacity;

    @OneToMany(mappedBy = "instructor", fetch = FetchType.LAZY)
    private List<InstructorCertificate> instructorCertificates;

    @PrePersist
    public void prePersist() {
        this.isCertified = this.isCertified != null && this.isCertified;
        this.isDeleted = false;
        this.provider = this.provider == null ? AuthProvider.EMAIL : this.provider;
        this.defaultCapacity = this.defaultCapacity == null ? DEFAULT_CAPACITY : this.defaultCapacity;
    }

    /** 기본 수용 인원(null 이면 {@link #DEFAULT_CAPACITY}). 기존 데이터(null)·신규 모두 안전하게 읽는다. */
    public int effectiveDefaultCapacity() {
        return defaultCapacity != null ? defaultCapacity : DEFAULT_CAPACITY;
    }
}
