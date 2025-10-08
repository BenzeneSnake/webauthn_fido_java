package com.webauthn.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class KeycloakService {
    private static final Logger log = LoggerFactory.getLogger(KeycloakService.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000; // 1秒

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
    private LocalDateTime tokenExpiry;

    /**
     * 取得 Admin Token（帶過期檢查）
     */
    public String getAdminToken() {
        // 檢查 token 是否存在且未過期
        if (adminToken != null && tokenExpiry != null && tokenExpiry.isAfter(LocalDateTime.now())) {
            return adminToken;
        }

        // Token 不存在或已過期，重新取得
        Map<String, String> formData = Map.of(
                "grant_type", "password",
                "client_id", clientId,
                "username", adminUsername,
                "password", adminPassword
        );

        //用 master realm 的 admin 帳號 去拿 token。
        JsonNode response = Objects.requireNonNull(webClient.post()
                        .uri(serverUrl + "/realms/master/protocol/openid-connect/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(formData.entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining("&")))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block());

        // 提取 access_token
        adminToken = response.get("access_token").asText();

        // 計算過期時間（提前30秒更新，避免剛好過期）
        int expiresIn = response.get("expires_in").asInt();
        tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn - 30);

        return adminToken;
    }

    /**
     * 處理Keycloak暫時性故障，使用指數退避重試機制
     * @param username 用戶名稱
     * @return Keycloak userId
     * @throws RuntimeException 當所有重試都失敗時
     */
    public String createUserWithRetry(String username) {
        // 先檢查用戶是否已存在
        if (userExists(username)) {
            log.info("User {} already exists in Keycloak, skipping creation", username);
            // 取得現有用戶的 userId
            return getUserId(username);
        }

        // 重試邏輯
        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                log.info("Creating user in Keycloak: {} (attempt {}/{})", username, attempt + 1, MAX_RETRY_ATTEMPTS);
                String userId = createUser(username);
                log.info("Successfully created user in Keycloak: {} with userId: {}", username, userId);
                return userId;
            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    long backoffTime = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 指數退避: 1s, 2s, 4s
                    log.warn("Failed to create user {} (attempt {}/{}): {}. Retrying in {}ms...",
                            username, attempt, MAX_RETRY_ATTEMPTS, e.getMessage(), backoffTime);

                    try {
                        Thread.sleep(backoffTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    log.error("Failed to create user {} after {} attempts", username, MAX_RETRY_ATTEMPTS, e);
                }
            }
        }

        // 所有重試都失敗
        throw new RuntimeException("Failed to create user in Keycloak after " + MAX_RETRY_ATTEMPTS + " attempts", lastException);
    }

    /**
     * 取得用戶的 userId（內部方法）
     */
    private String getUserId(String username) {
        String token = getAdminToken();
        List<JsonNode> users = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users?username=" + username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToFlux(JsonNode.class)
                .collectList()
                .block();

        if (users != null && !users.isEmpty()) {
            return users.get(0).get("id").asText();
        }
        throw new RuntimeException("User " + username + " not found in Keycloak");
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
