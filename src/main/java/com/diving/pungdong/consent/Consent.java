package com.diving.pungdong.consent;

import com.diving.pungdong.account.Account;
import lombok.*;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 한 사용자의 <b>동의 이력 1건</b> — "이 계정이, 이 약관 버전에, 언제, 어느 화면에서 동의했다".
 *
 * <p>약관 전문은 여기 복사하지 않고 {@link AgreementTermArchive}(버전당 1행)를 FK 로 참조한다
 * → 유저 N명이 같은 버전에 동의해도 전문은 1번만 저장(효율) + 그 행은 불변(증빙).
 *
 * <p>동의 이력은 append-only. 약관이 개정돼도 기존 행은 옛 버전을 가리킨 채 보존된다
 * (그 사용자가 동의한 것은 그 버전이므로).
 */
@Entity
@Getter
@Builder
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Consent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Account account;

    /** 동의한 약관 버전(불변 박제). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AgreementTermArchive agreementTerm;

    /** 동의를 수집한 화면 (회원가입/본인확인/강사신청/결제). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentContext context;

    private LocalDateTime agreedAt;
}
