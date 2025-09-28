package com.webauthn.app.rs;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinishRegistrationResponse {

    @Schema(title = "註冊是否成功", description = "true 表示註冊成功，false 表示失敗")
    private boolean registerSuccess;

    @Schema(title = "響應訊息", description = "成功或失敗的詳細訊息")
    private String message;

    @Schema(title = "用戶名", description = "註冊的用戶名")
    private String username;

    public static FinishRegistrationResponse success(String username) {
        return FinishRegistrationResponse.builder()
                .registerSuccess(true)
                .message("註冊成功")
                .username(username)
                .build();
    }

    public static FinishRegistrationResponse failure(String message) {
        return FinishRegistrationResponse.builder()
                .registerSuccess(false)
                .message(message)
                .build();
    }
}
