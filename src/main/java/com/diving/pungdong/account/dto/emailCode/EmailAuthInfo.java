package com.diving.pungdong.account.dto.emailCode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailAuthInfo {
    private String email;
    private String code;
}
