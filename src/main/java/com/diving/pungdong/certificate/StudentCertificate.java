package com.diving.pungdong.certificate;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.course.CertLevel;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 보유 자격증 1건 — 프로필 탭 "내 자격증" 이자, <b>강사 자격 검증의 단일 정본</b>.
 *
 * <p>2026-08-22 수렴: 예전엔 강사 신청이 별도 {@code ApplicationCertificate}(단체+이미지)를 들고 있어 강사가 같은
 * 자격증을 두 번 올렸다. 이제 신청은 이 행을 <b>id 로 참조</b>하고, 심사 결과는 {@link #verification} 에 붙는다.
 * 공개 인증마크(브랜딩·강의상세·강사 browse)는 {@code verification.status == VERIFIED} 인 행에서만 파생한다.
 *
 * <p><b>역할 게이트 없음</b> — 강사도 개인 자격으로 자격증을 보유한다. 수강생 레벨 자격증은 검수 대상이 아니라
 * 항상 {@code NONE} 이다(레벨 기준은 {@code CertLevel.isInstructorLevel}).
 *
 * <h3>표시명은 스냅샷이다</h3>
 * {@code organizationName}/{@code organizationFullName}/{@code certificationDisplayName} 은 등록 시점
 * Sanity 카탈로그에서 고른 값을 <b>박제</b>한 것이다({@code Enrollment.tuitionSnapshot} 과 같은 철학).
 * 자격증은 불변 credential 이라 카탈로그가 나중에 이름을 바꿔도 "내가 그때 딴 그 자격증"의 이름은
 * 그대로여야 한다. 또한 FE 의 카탈로그 소비가 <b>동기 순수함수</b>라(폼의 단체→종목 역인덱스·상세의
 * 풀네임 행) 조회 때마다 Sanity 를 비동기로 읽으면 로딩 상태가 리스트·상세로 번진다.
 * 검증되는 건 <b>코드</b>({@code disciplineCode}=discipline 테이블, {@code level}=enum)이고 표시명은
 * 아니다 — 위조해도 표시가 어긋날 뿐 권한·금액에 영향이 없다("사진이 진실").
 */
@Entity
@Table(name = "student_certificate", indexes = {
        @Index(name = "idx_student_certificate_owner", columnList = "account_id")
})
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class StudentCertificate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 보유자. 단방향 참조(account 는 이 패키지를 모른다). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account owner;

    /** 종목 코드(discipline.code) — 등록 시 {@code DisciplineService.getActiveByCode} 로 검증. */
    @Column(nullable = false, length = 50)
    private String disciplineCode;

    /** 자격 단체 코드(Sanity {@code certOrganization.code}). BE 는 카탈로그를 소유하지 않아 값 대조는 안 한다. */
    @Column(nullable = false, length = 50)
    private String organizationCode;

    /** 단체 짧은 표시명 스냅샷(Sanity {@code name}) — 카드 모노그램. {@code code} 와 다를 수 있다(SDI/TDI). */
    @Column(length = 200)
    private String organizationName;

    /** 단체 정식 명칭 스냅샷(Sanity {@code fullName}) — 상세 "자격 단체" 행. */
    @Column(length = 200)
    private String organizationFullName;

    /** 평탄화 레벨. course 패키지의 enum 을 재사용 — Sanity·types.ts 와 3자 계약이라 새로 만들지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CertLevel level;

    /** 단체 공식 자격증명 스냅샷(Sanity {@code displayName}) — 예 "AIDA 2". */
    @Column(length = 200)
    private String certificationDisplayName;

    /**
     * 자격증 번호 — 단체마다 형식이 달라 자유 텍스트(정규식 없음).
     *
     * <p>DB 는 nullable 이지만 <b>API 로는 필수</b>(DTO {@code @NotBlank}). null 은 강사 신청에서 옮겨온
     * <b>백필 행</b>뿐이다(옛 신청은 번호를 받지 않았다). 그 행의 null → 값 채우기는 기록 보완이지 식별필드 수정이
     * 아니라 재검수를 부르지 않는다(Rule A 예외).
     */
    @Column(length = 100)
    private String certificateNumber;

    /** 취득일 — <b>civil date</b>(시각·TZ 개념 없음). null 은 백필 행뿐(위와 같다). */
    private LocalDate acquiredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CertificateSource source;

    /** 외부 취득 시 발급 기관(선택). FE 등록 폼에 입력 자리가 아직 없어 신규 등록분은 비어 있다. */
    @Column(length = 100)
    private String issuer;

    /** 사진 저장 참조 — S3 는 객체 key, 로컬은 서빙 URL. 공개 URL 이 아니다(비공개 버킷 + presigned). */
    @Column(length = 500)
    private String photoFileKey;

    /* ── 풍덩 발급일 때만 채워지는 스냅샷 (강의 연결에서 서버가 파생) ── */

    /** 연결된 수강. 강의·강사 정보의 출처이자 "이미 연결됨" 판정용. */
    @Column(name = "enrollment_id")
    private Long enrollmentId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(length = 200)
    private String courseTitle;

    /** 마지막 정규 회차 날짜 — civil date. */
    private LocalDate courseCompletedAt;

    /** 발급 강사 표시명(nickName) 스냅샷. 강사가 닉네임을 바꿔도 발급 시점 이름이 남는다. */
    @Column(length = 100)
    private String instructorName;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    /** 검증 상태 묶음 — 항상 존재(NONE 포함). 전이는 아래 mark* 메서드로만. */
    @Embedded
    @Builder.Default
    private CertificateVerification verification = CertificateVerification.none();

    /* ── 변경 (PUT /certificates/{id}) ─────────────────────────────────
     * @Setter 를 통째로 열지 않는다 — 그러면 source·enrollmentId 처럼 **서버가 파생하는 값**까지
     * 아무 데서나 바뀔 수 있게 되고, "클라이언트는 무슨 자격증인지만 정한다"는 invariant 가 코드로
     * 강제되지 않는다. 대신 함께 바뀌어야 하는 묶음마다 메서드를 둔다.
     * owner·createdAt 은 어디서도 바뀌지 않는다(수정은 등록 시점을 지우지 않는다).
     */

    /**
     * 사용자가 직접 적는 값들의 <b>전면 교체</b>. 사진·강의 연결은 여기 없다 — 수명주기가 달라
     * ({@link #replacePhoto} 는 옛 객체 파기를 동반하고, 강의는 재검증이 필요하다) 별도 메서드다.
     */
    public void updateDetails(String disciplineCode, String organizationCode, String organizationName,
                              String organizationFullName, CertLevel level, String certificationDisplayName,
                              String certificateNumber, LocalDate acquiredAt, String issuer) {
        this.disciplineCode = disciplineCode;
        this.organizationCode = organizationCode;
        this.organizationName = organizationName;
        this.organizationFullName = organizationFullName;
        this.level = level;
        this.certificationDisplayName = certificationDisplayName;
        this.certificateNumber = certificateNumber;
        this.acquiredAt = acquiredAt;
        this.issuer = issuer;
    }

    /**
     * 사진 참조 교체. <b>호출 전에 소유 검증을 마쳐야 한다</b>
     * ({@code StudentCertificatePhotoStorage.isOwnedBy}) — 남의 key 를 붙이면 presigned 재발급으로
     * 남의 사진을 영구 열람하게 된다. 옛 객체 파기는 서비스가 커밋 이후에 한다.
     */
    public void replacePhoto(String photoFileKey) {
        this.photoFileKey = photoFileKey;
    }

    /**
     * 풍덩 발급으로 전환 + 강의 스냅샷 박제. {@code source} 가 PUNGDONG 이 되는 <b>유일한 경로</b>다 —
     * 강의 없이 "풍덩 발급"이 되는 모순 상태를 구조로 막는다.
     */
    public void linkCourse(Long enrollmentId, Long courseId, String courseTitle,
                           LocalDate courseCompletedAt, String instructorName) {
        this.source = CertificateSource.PUNGDONG;
        this.enrollmentId = enrollmentId;
        this.courseId = courseId;
        this.courseTitle = courseTitle;
        this.courseCompletedAt = courseCompletedAt;
        this.instructorName = instructorName;
    }

    /** 연결 해제 — 외부 취득으로 되돌리고 강의 스냅샷을 <b>전부</b> 비운다(부분 잔존 = 유령 강의). */
    public void unlinkCourse() {
        this.source = CertificateSource.EXTERNAL;
        this.enrollmentId = null;
        this.courseId = null;
        this.courseTitle = null;
        this.courseCompletedAt = null;
        this.instructorName = null;
    }

    /* ── 검증 상태 전이 — 호출처는 CertificateVerificationService 뿐 ───────── */

    public CertificateVerification getVerification() {
        return verification == null ? CertificateVerification.none() : verification;
    }

    void markPending(CertificateVerificationKind kind, OffsetDateTime now) {
        this.verification = CertificateVerification.pending(kind, now);
    }

    void markVerified(OffsetDateTime now) {
        this.verification = getVerification().verified(now);
    }

    void markRejected(String reason, OffsetDateTime now) {
        this.verification = getVerification().rejected(reason, now);
    }

    void clearVerification() {
        this.verification = CertificateVerification.none();
    }

    /** 강사 레벨({@code INSTRUCTOR} 이상)인가 — 검증 트랙의 대상 여부. */
    public boolean isInstructorLevel() {
        return level != null && level.isInstructorLevel();
    }
}
