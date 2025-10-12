package com.webauthn.app.user;

public enum RegistrationStatus {
    PENDING,    // 暫存狀態，尚未完成 WebAuthn 認證
    COMPLETED   // 已完成 WebAuthn 認證，Keycloak 用戶已建立
}
