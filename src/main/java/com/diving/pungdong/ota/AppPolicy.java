package com.diving.pungdong.ota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 앱 최소버전 게이트 정책 — <b>항상 1행</b>({@code id = 1}).
 *
 * <p>OTA 는 JS 만 바꾸므로 네이티브가 바뀐 릴리스 이후엔 스토어 업데이트를 강제할 수단이 없다. 앱이 부팅 시
 * 이 정책을 읽어 {@code getAppVersion() < minVersion} 이면 차단 화면을 띄운다. semver 비교는 앱이 한다 —
 * BE 는 문자열 저장 + 형식 검증만.
 *
 * <p><b>id 를 AUTO_INCREMENT 로 두지 않는 이유</b>: "정확히 1행"을 PK 고정으로 구조적으로 보장한다.
 * 두 행이 생겨 어느 쪽이 진짜인지 모르는 상태가 원천 봉쇄된다.
 *
 * <p>⚠️ <b>fail-safe 방향이 {@code SiteSettings} 와 반대다.</b> 런칭 게이트는 "사고 시 잠그는" 쪽이
 * 안전하지만, 앱 정책은 <b>"사고 시 여는" 쪽이 안전하다</b> — 게이트가 사용자를 앱 밖에 가두면 안 된다.
 * 그래서 행이 없으면 {@code minVersion "0.0.0"}(전 버전 통과)으로 응답한다({@link AppPolicyService}).
 */
@Entity
@Table(name = "app_policy")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppPolicy {

    /** 이 테이블의 유일한 행. */
    public static final long SINGLETON_ID = 1L;

    /** 정책 미설정 시 응답에 쓰는 폴백 — 전 버전 통과. 게이트는 사용자를 가두지 않는다. */
    public static final String PASS_ALL_MIN_VERSION = "0.0.0";

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "ios_min_version", nullable = false, length = 20)
    private String iosMinVersion;

    @Column(name = "ios_latest_version", length = 20)
    private String iosLatestVersion;

    @Column(name = "ios_store_url", length = 500)
    private String iosStoreUrl;

    @Column(name = "android_min_version", nullable = false, length = 20)
    private String androidMinVersion;

    @Column(name = "android_latest_version", length = 20)
    private String androidLatestVersion;

    @Column(name = "android_store_url", length = 500)
    private String androidStoreUrl;

    /** 차단 화면 안내 문구(한국어). FE 가 그대로 렌더한다. */
    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "updated_by_account_id")
    private Long updatedByAccountId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
