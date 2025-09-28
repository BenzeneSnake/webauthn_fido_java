package com.webauthn.app.rq;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class FinishRegisrationRequest {
    private String username;

    @Schema(title = "認證器的名稱", description = "在前端顯示時，用戶可以看到 \"使用我的手機登入\" 而不是看到一串無意義的 ID")
    private String credname;

    private PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential;

    @JsonCreator
    public FinishRegisrationRequest(@JsonProperty("username") String username,
                                    @JsonProperty("credname") String credname,
                                    @JsonProperty("credential") PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential) {
        this.username = username;
        this.credname = credname;
        this.credential = credential;
    }

}
