package com.diving.pungdong.identityverification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CI/DI(고유식별정보) 암호화 컬럼 컨버터 — AES-256/GCM. 실 본인확인기관이 반환하는 CI/DI 는
 * 평문 저장이 금지(고유식별정보)라, DB 에는 암호문만 남긴다.
 *
 * <p><b>키</b>: {@code pungdong.identity-verification.crypto-key} (env {@code IDENTITY_CRYPTO_KEY}).
 * 어떤 문자열이든 SHA-256 으로 32바이트 키로 유도한다(길이/형식 자유). prod 는 반드시 설정.
 * 미설정(로컬/테스트)이면 내장 dev 폴백 키로 동작하고 WARN 을 남긴다 — 이 경로엔 stub 의 mock
 * CI/DI(실데이터 아님)만 흐른다.
 *
 * <p><b>포맷</b>: {@code base64( 12B IV || ciphertext+tag )}. 복호화 실패(키 교체·레거시 평문 등)는
 * 원문을 그대로 돌려준다 — CI/DI 는 현재 어떤 소비자도 읽지 않아(read 경로 0) 관용적으로 처리해도
 * 안전하고, 마이그레이션(평문 stub 행 잔존)도 깨지지 않는다.
 *
 * <p>Hibernate 는 Spring Boot 의 {@code SpringBeanContainer} 를 통해 이 {@code @Component} 를
 * 스프링 빈으로 받아 {@code @Value} 주입이 동작한다. {@code autoApply=false} — {@code ci}/{@code di}
 * 필드에만 {@code @Convert} 로 명시 적용.
 */
@Slf4j
@Component
@Converter
public class CryptoStringConverter implements AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String DEV_FALLBACK_KEY = "pungdong-identity-dev-fallback-key-do-not-use-in-prod";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public CryptoStringConverter(@Value("${pungdong.identity-verification.crypto-key:}") String configuredKey) {
        String source = configuredKey;
        if (source == null || source.isBlank()) {
            log.warn("[identity-crypto] crypto-key 미설정 — dev 폴백 키 사용. prod 는 IDENTITY_CRYPTO_KEY 필수.");
            source = DEV_FALLBACK_KEY;
        }
        this.key = deriveKey(source);
    }

    private static SecretKeySpec deriveKey(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES"); // 32바이트 = AES-256
        } catch (Exception e) {
            throw new IllegalStateException("CI/DI 암호화 키 유도 실패", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("CI/DI 암호화 실패", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 키 교체·레거시 평문 등 복호화 불가 — CI/DI read 소비자가 없어 관용 처리(원문 반환).
            return dbData;
        }
    }
}
