package com.diving.pungdong.global.config;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.AwsRegionProvider;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.google.auth.oauth2.AwsCredentials;
import com.google.auth.oauth2.AwsSecurityCredentials;
import com.google.auth.oauth2.AwsSecurityCredentialsSupplier;
import com.google.auth.oauth2.ExternalAccountSupplierContext;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * FCM 발송 자격증명 구성. {@code firebase.enabled=true} 일 때만 활성화.
 *
 * <p>자격증명 선택 (우선순위):
 * <ol>
 *   <li><b>WIF</b> — {@code firebase.wif.audience} 설정 시. AWS task role → GCP SA 가장(impersonate),
 *       <b>비공개키 파일 없음</b>. prod/staging 기조.</li>
 *   <li><b>service account JSON</b> — {@code firebase.credentials.path} 설정 시. 파일 키. 로컬/임시용.</li>
 *   <li><b>ADC</b> — 그 외(EC2 IMDS / {@code GOOGLE_APPLICATION_CREDENTIALS}).</li>
 * </ol>
 *
 * <p>WIF 가 Fargate 에서 한 줄 코드가 필요한 이유: google-auth 1.23.0 의 내장 AWS 공급기는
 * env 변수 / EC2 IMDS 만 읽어 Fargate 의 컨테이너 자격 엔드포인트({@code AWS_CONTAINER_CREDENTIALS_*})
 * 를 못 본다. 그래서 AWS SDK 의 {@link DefaultAWSCredentialsProviderChain}(컨테이너 엔드포인트 +
 * 자동 회전 처리)으로 task role 자격을 가져오는 {@link AwsSdkSecurityCredentialsSupplier} 를 끼운다.
 * 자세한 정책/결정은 docs/features/push.md.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true")
public class FirebaseConfig {

    /** FCM 발송 OAuth scope. SA 가장 토큰 발급 시 명시 필요. */
    private static final List<String> FCM_SCOPES =
            Collections.singletonList("https://www.googleapis.com/auth/firebase.messaging");
    private static final String AWS_SUBJECT_TOKEN_TYPE = "urn:ietf:params:aws:token-type:aws4_request";
    private static final String GCP_STS_TOKEN_URL = "https://sts.googleapis.com/v1/token";
    /** {region} 은 supplier 의 getRegion() 으로 치환됨. WIF 내장 source 가 없으니 명시 override. */
    private static final String REGIONAL_CRED_VERIFICATION_URL =
            "https://sts.{region}.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15";

    @Value("${firebase.credentials.path:}")
    private String credentialsPath;

    @Value("${firebase.wif.audience:}")
    private String wifAudience;
    @Value("${firebase.wif.service-account-email:}")
    private String wifServiceAccountEmail;
    @Value("${firebase.wif.aws-region:}")
    private String wifAwsRegion;

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(loadCredentials())
                .build();
        return FirebaseApp.initializeApp(options);
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    private GoogleCredentials loadCredentials() throws IOException {
        if (wifAudience != null && !wifAudience.isBlank()) {
            return workloadIdentityCredentials();
        }
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try (FileInputStream stream = new FileInputStream(credentialsPath)) {
                log.info("Firebase Admin SDK initialised from service account key {}", credentialsPath);
                return GoogleCredentials.fromStream(stream).createScoped(FCM_SCOPES);
            }
        }
        log.info("Firebase Admin SDK initialised from Application Default Credentials (ADC)");
        return GoogleCredentials.getApplicationDefault();
    }

    /** AWS Workload Identity Federation 자격증명 (키리스). */
    private GoogleCredentials workloadIdentityCredentials() {
        String impersonationUrl =
                "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/"
                        + wifServiceAccountEmail + ":generateAccessToken";
        log.info("Firebase Admin SDK initialised via Workload Identity Federation (keyless), SA={}",
                wifServiceAccountEmail);
        return AwsCredentials.newBuilder()
                .setAudience(wifAudience)
                .setSubjectTokenType(AWS_SUBJECT_TOKEN_TYPE)
                .setTokenUrl(GCP_STS_TOKEN_URL)
                .setServiceAccountImpersonationUrl(impersonationUrl)
                .setRegionalCredentialVerificationUrlOverride(REGIONAL_CRED_VERIFICATION_URL)
                .setAwsSecurityCredentialsSupplier(new AwsSdkSecurityCredentialsSupplier(wifAwsRegion))
                .setScopes(FCM_SCOPES)
                .build();
    }

    /**
     * AWS SDK v1 기본 자격 체인 기반 supplier — Fargate 컨테이너 엔드포인트 + 자동 회전 대응.
     * {@code getCredentials()} 는 매 호출 시 체인에서 최신 자격을 가져와 회전을 반영한다.
     */
    static final class AwsSdkSecurityCredentialsSupplier implements AwsSecurityCredentialsSupplier {
        private final AWSCredentialsProvider provider;
        private final AwsRegionProvider regionProvider;
        private final String configuredRegion;

        AwsSdkSecurityCredentialsSupplier(String configuredRegion) {
            this(DefaultAWSCredentialsProviderChain.getInstance(),
                    new DefaultAwsRegionProviderChain(), configuredRegion);
        }

        AwsSdkSecurityCredentialsSupplier(AWSCredentialsProvider provider,
                                          AwsRegionProvider regionProvider,
                                          String configuredRegion) {
            this.provider = provider;
            this.regionProvider = regionProvider;
            this.configuredRegion = configuredRegion;
        }

        @Override
        public String getRegion(ExternalAccountSupplierContext context) {
            if (configuredRegion != null && !configuredRegion.isBlank()) {
                return configuredRegion;
            }
            return regionProvider.getRegion();
        }

        @Override
        public AwsSecurityCredentials getCredentials(ExternalAccountSupplierContext context) {
            AWSCredentials c = provider.getCredentials();
            String sessionToken = (c instanceof AWSSessionCredentials)
                    ? ((AWSSessionCredentials) c).getSessionToken()
                    : null;
            return new AwsSecurityCredentials(c.getAWSAccessKeyId(), c.getAWSSecretKey(), sessionToken);
        }
    }
}
