package com.diving.pungdong.ota;

/**
 * 앱이 보고하는 OTA 이벤트. 대문자인 이유는 이 레포의 모든 도메인 enum 이 {@code @Enumerated(STRING)} +
 * types.ts union literal 대문자이기 때문이다(이슈 #278 원문의 소문자는 관례 위반).
 *
 * <p>{@code INSTALLED} 가 없는 이유: 부팅 upsert 의 {@code otaBundleId} 로 서버가 파생한다.
 */
public enum OtaEventType {

    /**
     * 번들 다운로드 <b>완료</b>. 부팅 경로는 {@code onProgress} 의 {@code progress === 1}, 포그라운드
     * 재체크 경로는 {@code checkForUpdate()} 반환값의 {@code id}.
     *
     * <p>🚨 이슈 #278 이 제안한 {@code onUpdateProcessCompleted} 매핑은 <b>틀렸다</b> — 비강제 경로에서
     * 그 콜백은 다운로드 <i>시작</i> 시점에 즉시 불려서 실패한 다운로드까지 집계된다.
     */
    DOWNLOADED,

    /**
     * 어드민이 disable(또는 rollout 인하)해서 이전 번들로 <b>정상 복귀</b>했을 때.
     * {@code onUpdateProcessCompleted({status:'ROLLBACK'})}.
     *
     * <p>원인이 정상 운영 동작이라 <b>알람 대상이 아니다.</b> {@code CRASH_ROLLBACK} 과 합치면
     * disable 을 누른 직후 숫자가 치솟아 "번들이 터졌다"처럼 보이고, 운영 루프가 자기 자신을 트리거한다.
     */
    SERVER_ROLLBACK,

    /**
     * 첫 렌더 전 크래시로 네이티브가 <b>자동 롤백</b>했을 때. {@code onNotifyAppReady} 의
     * {@code crashedBundleId}. 🚨 알람 대상.
     *
     * <p>롤백이 일어난 <b>다음 부팅</b>에 보고된다 — 사용자가 앱을 안 켜면 안 온다. 그래서 이 카운트는
     * 항상 하한(下限)이고, 시각 컬럼 이름이 {@code crashRollbackReportedAt}(크래시 시각이 아니라 보고 시각)이다.
     */
    CRASH_ROLLBACK
}
