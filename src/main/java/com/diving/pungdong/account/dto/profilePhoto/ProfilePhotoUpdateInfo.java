package com.diving.pungdong.account.dto.profilePhoto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class ProfilePhotoUpdateInfo {
    private Long profilePhotoId;
    private String url;
}
