package com.webauthn.app.common.api;

import lombok.AllArgsConstructor;
import lombok.ToString;

@AllArgsConstructor
@ToString
public enum RestStatus {

    SUCCESS("200", "OK"),
    VALID("400", "參數檢驗錯誤"),
    FORBIDDEN("403", "Forbidden"),
    UNKNOWN("9999", "系統錯誤，請稍後再試");

    public final String CODE;
    public final String MESSAGE;
}
