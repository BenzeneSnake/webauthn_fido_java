package com.webauthn.app;

import com.webauthn.app.configuration.WebAuthProperties;
import com.webauthn.app.infrastructure.repository.RegistrationRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.RelyingPartyIdentity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AppApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppApplication.class, args);
    }

    @Bean
    public RelyingParty relyingParty(RegistrationRepository regisrationRepository, WebAuthProperties properties) {
        RelyingPartyIdentity rpIdentity = RelyingPartyIdentity.builder()
                .id(properties.getHostName())
                .name(properties.getDisplay())
                .build();

        return RelyingParty.builder()
                .identity(rpIdentity)
                .credentialRepository(regisrationRepository)
                .origins(properties.getOrigin())
                .build();
    }
}
