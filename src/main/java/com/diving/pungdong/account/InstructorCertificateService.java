package com.diving.pungdong.account;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.account.InstructorCertificate;
import com.diving.pungdong.account.InstructorImgCategory;
import com.diving.pungdong.account.dto.instructor.certificate.InstructorCertificateInfo;
import com.diving.pungdong.account.InstructorCertificateJpaRepo;
import com.diving.pungdong.service.image.S3Uploader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class InstructorCertificateService {
    private final InstructorCertificateJpaRepo instructorCertificateJpaRepo;
    private final S3Uploader s3Uploader;

    public void uploadInstructorImages(String email,
                                       List<MultipartFile> files,
                                       Account updateAccount,
                                       String dirName,
                                       InstructorImgCategory instructorImgCategory) throws IOException {
        for (MultipartFile file : files) {
            String fileURL = s3Uploader.upload(file, dirName, email);
            InstructorCertificate image = InstructorCertificate.builder()
                    .fileURL(fileURL)
                    .instructor(updateAccount)
                    .build();

            instructorCertificateJpaRepo.save(image);
        }
    }

    public List<InstructorCertificate> findInstructorCertificates(Account account) {
        return instructorCertificateJpaRepo.findByInstructor(account);
    }

    public List<InstructorCertificateInfo> mapToInstructorCertificateInfos(List<InstructorCertificate> instructorCertificateList) {
        List<InstructorCertificateInfo> certificateInfos = new ArrayList<>();
        for (InstructorCertificate instructorCertificate : instructorCertificateList) {
            InstructorCertificateInfo certificateInfo = InstructorCertificateInfo.builder()
                    .id(instructorCertificate.getId())
                    .imageUrl(instructorCertificate.getFileURL())
                    .build();
            certificateInfos.add(certificateInfo);
        }

        return certificateInfos;
    }
}
