package com.diving.pungdong.account.dto.profilePhoto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class ProfilePhotoInfo {
    private Long profilePhotoId;
    private String imageUrl;
}
