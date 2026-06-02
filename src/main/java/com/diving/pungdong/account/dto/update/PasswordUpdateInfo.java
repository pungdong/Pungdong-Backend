package com.diving.pungdong.account.dto.update;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PasswordUpdateInfo {
    private String currentPassword;
    private String newPassword;
}
