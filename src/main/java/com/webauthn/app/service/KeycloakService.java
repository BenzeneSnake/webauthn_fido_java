package com.webauthn.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class KeycloakService {
    @Value("${keycloak.server-url}")
    private String serverUrl; // e.g., http://localhost:8180

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    private WebClient webClient = WebClient.create();
    private String adminToken;

    /**
     * 取得 Admin Token
     */
    public String getAdminToken() {
        if (adminToken != null) return adminToken; // 可加過期檢查
        Map<String, String> formData = Map.of(
                "grant_type", "password",
                "client_id", clientId,
                "username", adminUsername,
                "password", adminPassword
        );

        //用 master realm 的 admin 帳號 去拿 token。
        adminToken = Objects.requireNonNull(webClient.post()
                        .uri(serverUrl + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(formData.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining("&")))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block())
                .get("access_token").asText();

        return adminToken;
    }

    /**
     * 檢查 Keycloak 是否已有 user
     */
    public boolean userExists(String username) {
        String token = getAdminToken();
        List<JsonNode> users = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users?username=" + username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .collectList()
                .block();
        return users != null && !users.isEmpty();
    }

    /**
     * 建立 Keycloak user
     */
    public String createUser(String username) {
        String token = getAdminToken();
        Map<String, Object> payload = Map.of(
                "username", username,
                "enabled", true
        );
        webClient.post()
                .uri(serverUrl + "/admin/realms/" + realm + "/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block();

        // 取得 userId
        List<JsonNode> users = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users?username=" + username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .collectList()
                .block();

        return users.get(0).get("id").asText();
    }

    /**
     * 指派 Realm Role 給 user
     */
    public void assignRole(String userId, String roleName) {
        String token = getAdminToken();

        // 取得 roleId
        JsonNode role = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/roles/" + roleName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        Map<String, Object> rolePayload = Map.of(
                "id", role.get("id").asText(),
                "name", role.get("name").asText()
        );

        webClient.post()
                .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(List.of(rolePayload))
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
