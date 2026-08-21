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

    /**
     * 표시용 아바타 URL — <b>없으면 null</b>. 모든 공개 응답의 {@code avatarUrl}/{@code profilePhotoUrl}
     * 은 이걸 거친다.
     *
     * <p><b>왜 기본 이미지를 null 로 접나</b>: 가입 시 모든 계정에 {@link #DEFAULT_IMAGE_URL} 이 붙는데
     * ({@code ProfilePhotoService.saveDefaultProfilePhoto}), 그 값은 URL 이 아니라 <b>레거시 맨 파일명</b>
     * 이다 — 업로드된 사진은 {@code S3Uploader.uploadPublic} 이 CDN base 를 붙인 <b>완전한 URL</b> 로
     * 저장되므로 클라이언트가 한 가지 규칙으로 둘 다 그릴 수 없다. 그래서 "사진 없음" 인 계정에까지
     * 렌더 불가능한 문자열이 내려가고 있었고, 계약(types.ts)은 이미 <b>"미설정이면 null → FE 기본 아바타"</b>
     * 라고 적혀 있었다. 여기서 접어 계약을 사실로 만든다.
     *
     * <p>덤으로 그 상수는 2021년 개발자의 <b>이메일이 박힌 파일명</b>이라, 접기 전까지는 모든 공개 응답에
     * 그 문자열이 실려 나갔다.
     *
     * <p>⚠️ <b>쓰기 쪽은 접지 않은 원본을 봐야 한다</b> — 사진 교체 시 옛 사진 삭제·탈퇴 익명화는 공유 기본
     * 이미지를 지우면 안 되므로 {@code getImageUrl()} 로 원본을 비교한다.
     */
    public String displayUrl() {
        return DEFAULT_IMAGE_URL.equals(imageUrl) ? null : imageUrl;
    }

    /** {@link #displayUrl()} 의 null-safe 진입점 — 사진 행 자체가 없는 계정(레거시)도 null. */
    public static String displayUrlOf(Account account) {
        ProfilePhoto photo = account == null ? null : account.getProfilePhoto();
        return photo == null ? null : photo.displayUrl();
    }
}
