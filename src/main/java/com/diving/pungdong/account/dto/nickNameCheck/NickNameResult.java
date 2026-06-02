package com.diving.pungdong.account.dto.nickNameCheck;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class NickNameResult {
    private Boolean exists;
}
