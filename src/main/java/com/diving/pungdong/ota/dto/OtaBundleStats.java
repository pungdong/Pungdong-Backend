package com.diving.pungdong.ota.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 번들 1건의 기기 카운트. <b>번들 메타(메시지·커밋·enabled·rollout·force)는 여기 없다</b> —
 * 그건 Cloudflare D1 이 유일한 출처이고 어드민이 in-process 로 읽어 합친다(BE 는 D1 을 읽지 않는다).
 *
 * <p>각 카운트는 {@code GET /admin/ota/bundles/{id}/devices?state=} 의 동명 필터와 <b>같은 술어</b>를 쓴다.
 */
@Getter
@Builder
public class OtaBundleStats {

    private final String bundleId;

    /**
     * 최근 {@code activeWindowDays} 안에 이 번들로 부팅하거나 앱을 포그라운드로 되돌린 기기 수.
     * ★ "지금 실행 중"이 아니다 — 그건 관측 불가다.
     */
    private final long active;

    /**
     * 마지막 보고 시점에 이 번들을 실행 중이던 기기 수(윈도우 무관).
     * ★ <b>누적이 아니다</b> — {@code otaBundleId} 는 부팅마다 덮어쓰는 현재 상태라, 기기가 다른 번들로
     * 넘어가면 여기서 빠진다. {@code active ⊆ installed}.
     */
    private final long installed;

    /** 총 배포 도달량. ⚠️ 설치까지 끝낸 기기도 <b>포함</b>한다. */
    private final long downloaded;

    /** "받았는데 아직 안 켠" — 에픽이 지목한 핵심 지표. {@code downloaded} 의 부분집합. */
    private final long downloadedNotInstalled;

    /** 어드민 disable/rollout 인하로 정상 복귀. 정상 운영 동작이라 알람 대상이 아니다. */
    private final long serverRolledBack;

    /**
     * 🚨 첫 렌더 크래시로 자동 롤백. <b>윈도우를 안 타고 사실상 단조 증가</b>한다(한 번 오르면 안 내려감) —
     * "이 번들이 위험했나"로는 맞지만 "지금 나아지고 있나"로는 틀리다. 그리고 <b>항상 하한(下限)</b>이다:
     * 기기가 다시 켜져야 보고되므로 "0 = 안전" 이 아니다.
     */
    private final long crashRolledBack;
}
