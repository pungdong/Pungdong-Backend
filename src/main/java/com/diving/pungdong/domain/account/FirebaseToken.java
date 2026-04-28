package com.diving.pungdong.domain.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(name = "firebase_token", uniqueConstraints = {
        @UniqueConstraint(name = "uk_firebase_token_token", columnNames = "token")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FirebaseToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DeviceType deviceType;

    private LocalDateTime lastSeenAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    public void markSeenBy(Account account) {
        this.account = account;
        this.lastSeenAt = LocalDateTime.now();
    }
}
