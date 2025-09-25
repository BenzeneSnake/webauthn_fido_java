package com.webauthn.app.rs;

import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CredentialCreateResponse {
    private PublicKeyCredentialCreationOptions publicKey;

    // 工廠方法：把原本的 registration 物件包成 CredentialCreateResponse
    public static CredentialCreateResponse from(PublicKeyCredentialCreationOptions registration) {
        return CredentialCreateResponse.builder()
                .publicKey(registration)
                .build();
    }
}
