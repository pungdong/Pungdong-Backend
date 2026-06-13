package com.diving.pungdong.address.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;

/**
 * 좌표 변환 요청 — 도로명주소 검색({@code GET /address-search}) 결과에서 사용자가 고른 항목의 키 5개를
 * 그대로 넘긴다. juso 좌표제공 API 가 요구하는 필드와 1:1.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class GeocodeRequest {
    @NotBlank
    private String admCd;        // 행정구역코드
    @NotBlank
    private String rnMgtSn;      // 도로명코드
    @NotBlank
    private String udrtYn;       // 지하여부(0:지상, 1:지하)
    @NotBlank
    private String buldMnnm;     // 건물본번
    private String buldSlno;     // 건물부번(없으면 0)
}
