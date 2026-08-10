package com.diving.pungdong.branding;

/**
 * 공식 기록의 <b>프리다이빙 경기 세부종목</b>.
 *
 * <p>⚠️ {@link com.diving.pungdong.discipline.Discipline}(종목 — FREEDIVING·SCUBA·MERMAID)과
 * <b>완전히 다른 축</b>이다. 둘 다 "discipline" 이라 부르면 반드시 혼동되므로 이름을 분리했다
 * (컬럼도 {@code event_code}).
 *
 * <p>CWT 수직입수(Constant Weight) · FIM 줄타기(Free Immersion) · CNF 무핀(Constant No Fins) ·
 * DYN 수평잠영(Dynamic) · DNF 무핀 수평잠영 · STA 정지호흡(Static).
 */
public enum RecordEventCode {
    CWT, FIM, CNF, DYN, DNF, STA
}
