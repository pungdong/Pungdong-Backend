package com.diving.pungdong.consent;

import lombok.*;

import javax.persistence.*;
import java.time.OffsetDateTime;

/**
 * 약관 버전 1건의 <b>불변 박제(snapshot)</b>. 어떤 사용자가 {@code (termKey, version)} 에
 * 처음 동의하는 순간, 그 시점 Sanity 전문을 그대로 freeze 해 1행 적재한다. 이후 같은 버전에
 * 동의하는 사용자는 모두 이 행을 {@link Consent} 가 FK 로 <b>참조</b>만 한다 (유저별 전문 복사 X).
 *
 * <p><b>왜 BE DB 에 박제하나</b> — 약관 authoring/수정은 Sanity(편집 UI)에서 자유롭게 하되
 * (의미가 바뀌면 {@code version} bump), 법적 분쟁 시 "그 사용자가 본 정확한 전문"은 우리 DB 의
 * 이 불변 행이 증빙한다. Sanity revision 이력은 retention 한계가 있어 장기 증빙으로 부적합.
 *
 * <p>이 행은 <b>append-only</b> — 한 번 생성되면 수정하지 않는다. 약관 개정은 새 {@code version}
 * = 새 행. {@code (termKey, version)} 은 UNIQUE.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_agreement_term_key_version", columnNames = {"term_key", "version"}))
public class AgreementTermArchive {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sanity term 의 식별 키 (예: privacy_collect). 버전 간 동일. */
    @Column(name = "term_key", nullable = false)
    private String termKey;

    /** 약관 버전 (예: v1). 의미가 바뀌면 Sanity 에서 bump → 새 박제 행. */
    @Column(nullable = false)
    private String version;

    /** 박제 시점의 제목. */
    private String title;

    /** 박제 시점의 본문 전문 (Sanity Portable Text 를 JSON 문자열로 그대로 보존). */
    @Lob
    private String body;

    /** 박제 시점에 이 약관이 필수 동의였는지. */
    private boolean required;

    /** 박제(freeze)된 시각. */
    private OffsetDateTime archivedAt;
}
