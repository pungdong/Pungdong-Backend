package com.diving.pungdong.account.dto.read;

import com.diving.pungdong.account.Gender;
import com.diving.pungdong.account.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
@Builder
public class AccountBasicInfo {
    private Long id;

    private String email;

    private String nickName;

    private String birth;

    private Gender gender;

    private String phoneNumber;

    /**
     * 계정 보유 role 집합 — FE 가 강사/수강생 화면을 분기하는 권위 소스. role 은 additive(강사 승인 시
     * STUDENT 유지 + INSTRUCTOR 추가)이고 서버가 매 요청 DB 로 재계산하므로, JWT 클레임(발급 시점 고정)이
     * 아니라 이 값으로 판단해야 승인 직후에도 정확하다.
     */
    private Set<Role> roles;
}