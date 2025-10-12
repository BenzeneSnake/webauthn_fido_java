package com.webauthn.app.user;

import jakarta.persistence.*;

import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.UserIdentity;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String displayName;

    @Lob
    @Column(nullable = false, length = 64)
    private ByteArray handle;

    @Column
    private String keycloakUserId;

    /**
     * 註冊狀態：PENDING（暫存）、COMPLETED（已完成）
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RegistrationStatus registrationStatus = RegistrationStatus.PENDING;

    /**
     * 註冊時間
     */
    @Column
    private LocalDateTime registeredAt;

    /**
     * 完成註冊時間（finishauth 成功時間）
     */
    @Column
    private LocalDateTime completedAt;

    public AppUser(UserIdentity user) {
        this.handle = user.getId();
        this.username = user.getName();
        this.displayName = user.getDisplayName();
        this.registeredAt = LocalDateTime.now();
    }

    public void setKeycloakUserId(String keycloakUserId) {
        this.keycloakUserId = keycloakUserId;
    }

    public void setRegistrationStatus(RegistrationStatus status) {
        this.registrationStatus = status;
        if (status == RegistrationStatus.COMPLETED) {
            this.completedAt = LocalDateTime.now();
        }
    }

    public UserIdentity toUserIdentity() {
        return UserIdentity.builder()
                .name(getUsername())
                .displayName(getDisplayName())
                .id(getHandle())
                .build();
    }
}