package com.webauthn.app.rs;

import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CredentialCreateResponse {
    private PublicKeyCredentialCreationOptions publicKey;

    /**
     * 資料庫用戶主鍵 ID (數字型態)，用於前端錯誤時刪除暫存用戶
     */
    private Long userId;

    // 工廠方法：把原本的 registration 物件包成 CredentialCreateResponse
    public static CredentialCreateResponse from(PublicKeyCredentialCreationOptions registration, Long userId) {
        return CredentialCreateResponse.builder()
                .publicKey(registration)
                .userId(userId)
                .build();
    }
}
