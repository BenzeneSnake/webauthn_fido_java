package com.webauthn.app.rq;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class LoginRequest {
    @NotBlank(message = "username cannot be blank")
    private String username;
}