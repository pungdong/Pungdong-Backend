package com.diving.pungdong.global.config;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.regions.AwsRegionProvider;
import com.diving.pungdong.global.config.FirebaseConfig.AwsSdkSecurityCredentialsSupplier;
import com.google.auth.oauth2.AwsSecurityCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIF supplier 의 자격 매핑 로직만 검증한다. 실제 AWS↔GCP 연합(STS 교환·SA 가장)은
 * 실 클라우드에서만 가능하므로 staging 실송신으로 수동 검증 — docs/features/push.md.
 *
 * 시나리오 코드: W* = WIF supplier.
 */
class FirebaseConfigTest {

    private static AWSCredentialsProvider providerOf(AWSCredentials creds) {
        return new AWSCredentialsProvider() {
            @Override
            public AWSCredentials getCredentials() {
                return creds;
            }

            @Override
            public void refresh() {
            }
        };
    }

    private static AwsRegionProvider regionOf(String region) {
        return new AwsRegionProvider() {
            @Override
            public String getRegion() {
                return region;
            }
        };
    }

    private static final AwsRegionProvider REGION_NPE = new AwsRegionProvider() {
        @Override
        public String getRegion() {
            throw new AssertionError("regionProvider 는 호출되면 안 됨 (configuredRegion 우선)");
        }
    };

    @Test
    @DisplayName("W1: 세션 자격(STS/task role)이면 sessionToken 까지 그대로 전달")
    void mapsSessionToken() {
        var supplier = new AwsSdkSecurityCredentialsSupplier(
                providerOf(new BasicSessionCredentials("AKID", "SECRET", "SESSION")),
                REGION_NPE, "ap-northeast-2");

        AwsSecurityCredentials out = supplier.getCredentials(null);

        assertThat(out.getAccessKeyId()).isEqualTo("AKID");
        assertThat(out.getSecretAccessKey()).isEqualTo("SECRET");
        assertThat(out.getSessionToken()).isEqualTo("SESSION");
    }

    @Test
    @DisplayName("W2: 세션 아닌 자격이면 sessionToken 은 null")
    void mapsBasicCredentialsWithoutSessionToken() {
        var supplier = new AwsSdkSecurityCredentialsSupplier(
                providerOf(new BasicAWSCredentials("AKID", "SECRET")),
                REGION_NPE, "ap-northeast-2");

        AwsSecurityCredentials out = supplier.getCredentials(null);

        assertThat(out.getSessionToken()).isNull();
    }

    @Test
    @DisplayName("W3: configuredRegion 이 있으면 그것을 쓰고 region 체인은 안 본다")
    void prefersConfiguredRegion() {
        var supplier = new AwsSdkSecurityCredentialsSupplier(
                providerOf(new BasicAWSCredentials("AKID", "SECRET")),
                REGION_NPE, "ap-northeast-2");

        assertThat(supplier.getRegion(null)).isEqualTo("ap-northeast-2");
    }

    @Test
    @DisplayName("W4: configuredRegion 이 비면 AWS region 체인으로 폴백")
    void fallsBackToRegionChainWhenUnset() {
        var supplier = new AwsSdkSecurityCredentialsSupplier(
                providerOf(new BasicAWSCredentials("AKID", "SECRET")),
                regionOf("us-east-1"), "");

        assertThat(supplier.getRegion(null)).isEqualTo("us-east-1");
    }
}
