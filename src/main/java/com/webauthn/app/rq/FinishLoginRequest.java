package com.webauthn.app.rq;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class FinishLoginRequest {

    @NotBlank
    private String credential;

    @NotBlank
    private String username;
}
