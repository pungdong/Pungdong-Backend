package com.diving.pungdong.ota;

/**
 * 어드민 기기 목록 필터. <b>각 값은 {@code OtaBundleStats} 의 동명 카운트와 정확히 같은 술어를 쓴다</b> —
 * 목록의 숫자를 눌러 상세로 들어갔을 때 다른 수가 나오면 사용자가 바로 알아차린다(use-case 테스트 A7 이 잠근다).
 */
public enum OtaDeviceState {

    /** 그 번들과 어떤 식으로든 엮인 기기 전부(실행 중 / 받음 / 서버롤백 / 크래시롤백). 첫 진입의 기본값. */
    ALL,

    /** 최근 {@code activeWindowDays} 안에 그 번들로 부팅하거나 앱을 포그라운드로 되돌린 걸 본 기기. */
    ACTIVE,

    /**
     * 마지막 보고 시점에 그 번들을 실행 중이던 기기(윈도우 무관).
     * ★ 누적이 아니다 — {@code otaBundleId} 는 부팅마다 덮어쓰는 현재 상태라 다른 번들로 넘어가면 빠진다.
     */
    INSTALLED,

    /** 그 번들을 받은 기기 전부(총 배포 도달량). 설치까지 끝낸 기기도 포함한다. */
    DOWNLOADED,

    /** "받았는데 아직 안 켠" — 에픽이 지목한 핵심 필터. {@link #DOWNLOADED} 의 부분집합. */
    DOWNLOADED_NOT_INSTALLED,

    /** 어드민 disable/rollout 인하로 그 번들에서 정상 복귀한 기기. */
    SERVER_ROLLED_BACK,

    /** 그 번들이 첫 렌더 전 크래시를 내 자동 롤백된 기기. 🚨 */
    CRASH_ROLLED_BACK
}
