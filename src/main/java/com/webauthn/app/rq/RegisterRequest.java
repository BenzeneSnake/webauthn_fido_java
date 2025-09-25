package com.webauthn.app.rq;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RegisterRequest {
    @NotBlank(message = "username cannot be blank")
    private String username;

    @NotBlank(message = "display cannot be blank")
    private String display;
}
