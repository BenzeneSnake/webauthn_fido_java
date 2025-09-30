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
public class FinishLoginResponse {
    @Schema(title = "登入是否成功", description = "true 表示登入成功，false 表示失敗")
    private boolean loginSuccess;

    @Schema(title = "響應訊息", description = "成功或失敗的詳細訊息")
    private String message;

    @Schema(title = "用戶名", description = "註冊的用戶名")
    private String username;

    public static FinishLoginResponse success(String username) {
        return FinishLoginResponse.builder()
                .loginSuccess(true)
                .message("登入成功")
                .username(username)
                .build();
    }

    public static FinishLoginResponse failure(String message) {
        return FinishLoginResponse.builder()
                .loginSuccess(false)
                .message(message)
                .build();
    }
}
