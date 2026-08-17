package com.diving.pungdong.ota;

import com.diving.pungdong.account.Account;
import com.diving.pungdong.ota.dto.AppPolicyResponse;
import com.diving.pungdong.ota.dto.UpdateAppPolicyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 앱 최소버전 게이트 정책 — 단일 행({@code id = 1}) 조회/치환.
 *
 * <p>⚠️ <b>fail-safe 방향이 {@code SiteSettings} 와 반대다.</b> 런칭 게이트는 "사고 시 잠그는" 쪽이 안전하지만
 * 앱 정책은 <b>"사고 시 여는" 쪽이 안전하다</b> — 게이트가 사용자를 앱 밖에 가두면 안 된다. 그래서 행이 없으면
 * {@code minVersion "0.0.0"}(전 버전 통과)으로 응답하고, 시드 행도 넣지 않는다(시드를 넣으면 "행이 없는 경로"가
 * 프로덕션에서 한 번도 안 돌아 테스트만 통과한 죽은 코드가 된다).
 */
@Service
@RequiredArgsConstructor
public class AppPolicyService {

    private final AppPolicyJpaRepo appPolicyRepo;

    /** 앱·어드민 공통 조회. 행이 없으면 "전 버전 통과" 폴백. */
    @Transactional(readOnly = true)
    public AppPolicyResponse get() {
        return appPolicyRepo.findById(AppPolicy.SINGLETON_ID)
                .map(AppPolicyService::toResponse)
                .orElseGet(AppPolicyService::passAll);
    }

    /** 전체 치환(PATCH 아님 — 어드민 폼이 전 필드를 다시 보낸다). */
    @Transactional
    public AppPolicyResponse update(UpdateAppPolicyRequest request, Account admin) {
        AppPolicy policy = appPolicyRepo.findById(AppPolicy.SINGLETON_ID)
                .orElseGet(() -> AppPolicy.builder().id(AppPolicy.SINGLETON_ID).build());

        policy.setIosMinVersion(request.getIos().getMinVersion());
        policy.setIosLatestVersion(request.getIos().getLatestVersion());
        policy.setIosStoreUrl(request.getIos().getStoreUrl());
        policy.setAndroidMinVersion(request.getAndroid().getMinVersion());
        policy.setAndroidLatestVersion(request.getAndroid().getLatestVersion());
        policy.setAndroidStoreUrl(request.getAndroid().getStoreUrl());
        policy.setMessage(request.getMessage());
        policy.setUpdatedByAccountId(admin != null ? admin.getId() : null);
        policy.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        return toResponse(appPolicyRepo.save(policy));
    }

    private static AppPolicyResponse toResponse(AppPolicy policy) {
        return AppPolicyResponse.builder()
                .ios(AppPolicyResponse.Platform.builder()
                        .minVersion(policy.getIosMinVersion())
                        .latestVersion(policy.getIosLatestVersion())
                        .storeUrl(policy.getIosStoreUrl())
                        .build())
                .android(AppPolicyResponse.Platform.builder()
                        .minVersion(policy.getAndroidMinVersion())
                        .latestVersion(policy.getAndroidLatestVersion())
                        .storeUrl(policy.getAndroidStoreUrl())
                        .build())
                .message(policy.getMessage())
                .build();
    }

    /** 정책 미설정 폴백 — 전 버전 통과. 게이트는 사용자를 앱 밖에 가두지 않는다. */
    private static AppPolicyResponse passAll() {
        AppPolicyResponse.Platform open = AppPolicyResponse.Platform.builder()
                .minVersion(AppPolicy.PASS_ALL_MIN_VERSION)
                .build();
        return AppPolicyResponse.builder().ios(open).android(open).build();
    }
}
