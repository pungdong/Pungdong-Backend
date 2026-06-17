package com.diving.pungdong.availability.dto;

import lombok.*;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 일정(점유) 원자 추가 — V2 "일정 추가" 시트. 한 트랜잭션에서 ① 그 시간대를 덮도록 coverage(예약가능시간)
 * 확장+머지 ② 그 (위치,시간) 일정을 찾거나 생성 ③ {@code count}&gt;0 이면 점유(hold) 기록. (FE 의 create+hold
 * 2-call 폐기.) {@code instructor} 는 바디가 아니라 컨트롤러가 현재 계정으로 주입.
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class SessionCreateRequest {

    @NotNull
    private LocalDate date;
    @NotNull
    private LocalTime startTime;
    @NotNull
    private LocalTime endTime;

    /** 위치 토큰(선택) — "CUSTOM:&lt;pk&gt;"/"OFFICIAL:&lt;sanityId&gt;". 위치 없는 점유면 비움. */
    private String venueRefId;
    /** 세션 라벨(선택) — "1부"/"오후". */
    private String sessionLabel;

    /** 함께 기록할 점유 인원(선택). 0/null = 빈 일정만 생성, 1~N = 그만큼 점유(hold) 추가. */
    private Integer count;
    /** 외부예약 메모(선택). 있으면 외부예약, 없으면 ± 빠른조정. */
    private String memo;

    /** 이 일정 정원 override(선택). 비우면 계정 기본값을 따른다. 주면 1 이상. */
    private Integer capacity;
}
