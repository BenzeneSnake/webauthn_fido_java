package com.webauthn.app.rs;

import lombok.Data;

import java.util.List;

@Data
public class CredentialGetResponse {

    private PublicKeyCredentialRequestOptions publicKey;

    @Data
    public static class PublicKeyCredentialRequestOptions {
        private String challenge;
        private Long timeout;
        private String rpId;
        private List<PublicKeyCredentialDescriptor> allowCredentials;
        private String userVerification;
        private Object extensions;
        private List<String> hints;
    }

    @Data
    public static class PublicKeyCredentialDescriptor {
        private String type;
        private String id;
        private List<String> transports;

        public List<String> getTransports() {
            return transports == null ? List.of() : transports; // null 轉空陣列
        }
    }
}