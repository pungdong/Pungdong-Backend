package com.diving.pungdong.instructorapplication;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ApplicationCertificateJpaRepo extends JpaRepository<ApplicationCertificate, Long> {

    /**
     * 여러 계정의 <b>한 종목</b> 승인 신청에 달린 단체 코드 — {@code [accountId, organizationCode]} 쌍.
     * 강사 둘러보기 카드의 단체 칩용. 카드마다 {@code application.getCertificates()} 를 건드리면
     * 페이지 크기만큼 쿼리가 나간다({@code default_batch_fetch_size} 에 기대지 않고 명시적으로 한 번).
     *
     * <p>중복 제거는 하지 않는다 — 한 강사가 같은 단체 자격증을 여러 장 올릴 수 있어서, 호출부가
     * {@code distinct} 로 모은다(정렬을 호출부가 정할 수 있게).
     */
    @Query("select ac.application.account.id, ac.organizationCode from ApplicationCertificate ac "
            + "where ac.application.account.id in :accountIds "
            + "and ac.application.disciplineCode = :disciplineCode "
            + "and ac.application.status = com.diving.pungdong.instructorapplication.InstructorApplicationStatus.APPROVED "
            + "and ac.organizationCode is not null")
    List<Object[]> findOrganizationCodesByAccountIds(@Param("accountIds") Collection<Long> accountIds,
                                                     @Param("disciplineCode") String disciplineCode);
}
