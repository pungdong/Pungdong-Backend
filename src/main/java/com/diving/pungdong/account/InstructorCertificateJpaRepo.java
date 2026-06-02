package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.InstructorCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstructorCertificateJpaRepo extends JpaRepository<InstructorCertificate, Long> {
    List<InstructorCertificate> findByInstructor(Account instructor);
}