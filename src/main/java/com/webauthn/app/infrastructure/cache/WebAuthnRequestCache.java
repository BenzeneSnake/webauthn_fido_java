package com.webauthn.app.infrastructure.cache;

import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebAuthnRequestCache {
    private final Map<String, PublicKeyCredentialCreationOptions> cache = new ConcurrentHashMap<>();

    public void put(String username, PublicKeyCredentialCreationOptions options) {
        cache.put(username, options);
    }

    public PublicKeyCredentialCreationOptions get(String username) {
        return cache.get(username);
    }

    public void remove(String username) {
        cache.remove(username);
    }
}
