package com.diving.pungdong.ota;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 단일 행({@code id = 1}) 정책 저장소. 행이 없을 수 있고, <b>없는 것이 정상 상태</b>다 —
 * 그때의 폴백(minVersion {@code "0.0.0"})이 안전 기본값이라 시드를 넣지 않는다.
 */
public interface AppPolicyJpaRepo extends JpaRepository<AppPolicy, Long> {
}
