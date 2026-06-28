package com.diving.pungdong.account;

import lombok.*;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
@Getter
@Setter
@EqualsAndHashCode(of = "id")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfilePhoto {

    /** 신규 가입자에게 부여되는 공유 기본 이미지 — 특정 개인의 사진이 아니므로 탈퇴 익명화 시 S3 에서 지우면 안 된다. */
    public static final String DEFAULT_IMAGE_URL = "vlvkcjswo71@gmail.com2021-06-07T18:08:34.039977.png";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String imageUrl;
}
