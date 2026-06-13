package com.diving.pungdong.usecase;

import com.diving.pungdong.venue.sync.OfficialVenueCache;
import com.diving.pungdong.venue.sync.OfficialVenueReconciler;
import com.diving.pungdong.venue.sync.SanityVenueClient;
import com.diving.pungdong.venue.sync.SanityVenueClient.OfficialVenueDoc;
import com.diving.pungdong.venue.sync.SanityVenueClient.RevEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 공식 위치 캐시 reconcile use-case — read-side {@code _rev} 대조 분기를 {@code @MockBean}
 * {@link SanityVenueClient} 로 구동(주기는 안 돌리고 reconcile() 직접 호출). 실 H2/Redis(임베디드).
 *
 * <p>C1 초기적재 · C2 무변경=재fetch 안 함 · C3 _rev 변경=refetch · C4 삭제=evict · H1 heartbeat 기록.
 */
@SpringBootTest
@ActiveProfiles("test")
class VenueReconcileTest {

    @Autowired OfficialVenueReconciler reconciler;
    @Autowired OfficialVenueCache cache;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired ObjectMapper objectMapper;

    @MockBean SanityVenueClient sanityVenueClient;

    @BeforeEach
    void flushCache() {
        Set<String> keys = redisTemplate.keys("venue:official:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private OfficialVenueDoc doc(String id, String rev) {
        try {
            return new OfficialVenueDoc(id, rev, objectMapper.readTree(
                    "{\"_id\":\"" + id + "\",\"_rev\":\"" + rev + "\",\"name\":\"" + id
                            + "\",\"type\":\"OCEAN\",\"tickets\":[],\"closures\":[]}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("C1 첫 reconcile 은 전량 적재하고 heartbeat 를 찍는다")
    void c1_initial_load() {
        given(sanityVenueClient.fetchAll()).willReturn(List.of(doc("a", "r1"), doc("b", "r1")));

        reconciler.reconcile();

        verify(sanityVenueClient, times(1)).fetchAll();
        assertThat(cache.isLoaded()).isTrue();
        assertThat(cache.lastReconciledAt()).isNotNull();
        assertThat(cache.getAll()).hasSize(2);
    }

    @Test
    @DisplayName("C2 _rev 가 그대로면 refetch 하지 않는다(싼 revs 대조만)")
    void c2_no_change_no_refetch() {
        given(sanityVenueClient.fetchAll()).willReturn(List.of(doc("a", "r1")));
        given(sanityVenueClient.fetchRevs()).willReturn(List.of(new RevEntry("a", "r1")));

        reconciler.reconcile(); // 초기 적재 (fetchAll 1)
        reconciler.reconcile(); // 변경 없음 → fetchAll 추가 호출 없음

        verify(sanityVenueClient, times(1)).fetchAll();
        verify(sanityVenueClient, times(1)).fetchRevs();
    }

    @Test
    @DisplayName("C3 _rev 가 바뀌면 전량 refetch 해 캐시를 교체한다")
    void c3_rev_change_refetch() {
        given(sanityVenueClient.fetchAll()).willReturn(List.of(doc("a", "r1")));
        reconciler.reconcile(); // 초기 적재

        given(sanityVenueClient.fetchRevs()).willReturn(List.of(new RevEntry("a", "r2")));
        given(sanityVenueClient.fetchAll()).willReturn(List.of(doc("a", "r2")));
        reconciler.reconcile(); // 변경 감지 → refetch

        verify(sanityVenueClient, times(2)).fetchAll();
        assertThat(cache.cachedRevs()).containsEntry("a", "r2");
    }

    @Test
    @DisplayName("C4 위치가 삭제되면(revs 에서 사라지면) 캐시에서 evict 된다")
    void c4_deletion_evicts() {
        given(sanityVenueClient.fetchAll()).willReturn(List.of(doc("a", "r1"), doc("b", "r1")));
        reconciler.reconcile();
        assertThat(cache.getAll()).hasSize(2);

        given(sanityVenueClient.fetchRevs()).willReturn(List.of(new RevEntry("a", "r1")));
        given(sanityVenueClient.fetchAll()).willReturn(List.of(doc("a", "r1")));
        reconciler.reconcile();

        assertThat(cache.getAll()).hasSize(1);
        assertThat(cache.cachedRevs()).containsOnlyKeys("a");
    }
}
