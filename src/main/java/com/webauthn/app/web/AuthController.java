package com.webauthn.app.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn.app.authenticator.Authenticator;
import com.webauthn.app.common.api.RestResult;
import com.webauthn.app.common.api.RestStatus;
import com.webauthn.app.rq.FinishLoginRequest;
import com.webauthn.app.rq.FinishRegisrationRequest;
import com.webauthn.app.rq.LoginRequest;
import com.webauthn.app.rq.RegisterRequest;
import com.webauthn.app.rs.CredentialCreateResponse;
import com.webauthn.app.rs.CredentialGetResponse;
import com.webauthn.app.rs.FinishLoginResponse;
import com.webauthn.app.rs.FinishRegistrationResponse;
import com.webauthn.app.service.KeycloakService;
import com.webauthn.app.infrastructure.retry.repository.RegistrationRepository;
import com.webauthn.app.strategy.RoleStrategy;
import com.webauthn.app.user.AppUser;
import com.webauthn.app.user.RegistrationStatus;
import com.webauthn.app.utility.Utility;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final RelyingParty relyingParty;
    private final RegistrationRepository registrationRepository;
    private final KeycloakService keycloakService;
    private final RoleStrategy roleStrategy;
    private Map<String, PublicKeyCredentialCreationOptions> requestOptionMap = new HashMap<>();
    private Map<String, AssertionRequest> assertionRequestMap = new HashMap<>();

    AuthController(RegistrationRepository registrationRepository, RelyingParty relyingPary, KeycloakService keycloakService, RoleStrategy roleStrategy) {
        this.relyingParty = relyingPary;
        this.registrationRepository = registrationRepository;
        this.keycloakService = keycloakService;
        this.roleStrategy = roleStrategy;
    }

    /**
     * 階段一：暫存註冊
     * 只儲存到本地 DB，不建立 Keycloak 用戶
     */
    @PostMapping("/register")
    @ResponseBody
    public RestResult<CredentialCreateResponse> newUserRegistration(
            @RequestBody RegisterRequest request
    ) {
        String username = request.getUsername();
        String display = request.getDisplay();

        AppUser existingUser = registrationRepository.getUserRepo().findByUsername(username);
        if (existingUser == null) {
            log.info("Stage 1: 暫存註冊，Creating pending user in local DB: {}", username);

            UserIdentity userIdentity = UserIdentity.builder()
                    .name(username)
                    .displayName(display)
                    .id(Utility.generateRandom(32))//隨機id防止跨站攻擊
                    .build();

            AppUser saveUser = new AppUser(userIdentity);
            // 只儲存到本地 DB，狀態為 PENDING
            registrationRepository.getUserRepo().save(saveUser);

            log.info("成功暫存User: {} with userId: {}", username, saveUser.getId());

            // 返回 WebAuthn challenge (包含 userId)
            return performAuthRegistration(saveUser);

        } else {
            log.warn("User registration failed - username already exists: {}", username);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username " + username + " already exists. Choose a new name.");
        }
    }

    @PostMapping("/registerauth")
    @ResponseBody
    public RestResult<CredentialCreateResponse> newAuthRegistration(
            @RequestParam AppUser user
    ) {
        return performAuthRegistration(user);
    }

    // 內部方法，支持直接調用
    private RestResult<CredentialCreateResponse> performAuthRegistration(AppUser user) {
        AppUser existingUser = registrationRepository.getUserRepo().findByHandle(user.getHandle());
        if (existingUser != null) {
            UserIdentity userIdentity = user.toUserIdentity();

            //加 authenticatorSelection
            AuthenticatorSelectionCriteria selection = AuthenticatorSelectionCriteria.builder()
                    .authenticatorAttachment(AuthenticatorAttachment.CROSS_PLATFORM) // 外部裝置 (手機、YubiKey)
                    .userVerification(UserVerificationRequirement.PREFERRED)       // 可以 PIN / 生物辨識
                    .build();

            StartRegistrationOptions registrationOptions = StartRegistrationOptions.builder()
                    .user(userIdentity)
                    .authenticatorSelection(selection)  // 把設定加進來
                    .build();
            PublicKeyCredentialCreationOptions registration = relyingParty.startRegistration(registrationOptions);
            this.requestOptionMap.put(user.getUsername(), registration);

            // 返回 註冊選項 和 userId
            return new RestResult<>(CredentialCreateResponse.from(registration, user.getId()));
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User " + user.getUsername() + " does not exist. Please register.");
        }
    }

    /**
     * 階段二：完成認證後正式建立 Keycloak user
     * WebAuthn 驗證成功後，才建立 Keycloak 用戶並指派角色
     */
    @PostMapping("/finishauth")
    @ResponseBody
    public RestResult<FinishRegistrationResponse> finishRegisration(
            @RequestBody FinishRegisrationRequest finishRegisrationRequest
    ) {

        try {
            String username = finishRegisrationRequest.getUsername();
            String credname = finishRegisrationRequest.getCredname();
            AppUser user = registrationRepository.getUserRepo().findByUsername(username);

            if (user == null) {
                log.error("User not found for finishauth: {}", username);
                return new RestResult<>(FinishRegistrationResponse.failure("用戶不存在"));
            }

            PublicKeyCredentialCreationOptions requestOptions = this.requestOptionMap.get(username);
            if (requestOptions != null) {
                PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc
                        = finishRegisrationRequest.getCredential();
                if (pkc == null) {
                    return new RestResult<>(RestStatus.VALID.CODE, RestStatus.VALID.MESSAGE, FinishRegistrationResponse.failure("認證憑證為空"));
                }

                //FIDO2: 驗證時: 伺服器使用公鑰，去驗證此簽章是否有效。
                FinishRegistrationOptions options = FinishRegistrationOptions.builder()
                        .request(requestOptions)
                        .response(pkc)
                        .build();
                RegistrationResult result = relyingParty.finishRegistration(options);

                log.info("Stage 2: 完成認證 WebAuthn verification successful for user: {}", username);

                // WebAuthn 驗證成功，儲存 Authenticator
                Authenticator savedAuth = new Authenticator(result, pkc.getResponse(), user, credname);
                registrationRepository.getAuthRepository().save(savedAuth);

                // 清理快取
                this.requestOptionMap.remove(username);

                // 在 Keycloak 建立用戶並指派角色
                String keycloakUserId = null;
                try {
                    keycloakUserId = keycloakService.createUserWithRetry(username);
                    log.info("Successfully created user in Keycloak: {} with userId: {}", username, keycloakUserId);

                    // 儲存 keycloakUserId
                    user.setKeycloakUserId(keycloakUserId);

                    // 嘗試指派預設角色
                    try {
                        List<String> defaultRoles = roleStrategy.getDefaultRoles(username);
                        if (defaultRoles != null && !defaultRoles.isEmpty()) {
                            keycloakService.assignRoles(keycloakUserId, defaultRoles);
                            log.info("Successfully assigned default roles to user in Keycloak: {} - roles: {}", username, defaultRoles);
                        } else {
                            log.warn("No default roles configured for user: {}", username);
                        }
                    } catch (Exception roleException) {
                        log.error("Failed to assign default roles in Keycloak: {}", username, roleException);
                        // 角色指派失敗，rollback Keycloak 用戶
                        try {
                            keycloakService.deleteUser(keycloakUserId);
                            log.info("Rolled back Keycloak user after role assignment failure: {}", username);
                        } catch (Exception deleteException) {
                            log.error("Failed to delete Keycloak user during rollback: {}", username, deleteException);
                        }
                        // 刪除 Authenticator (因為 Keycloak 建立失敗)
                        registrationRepository.getAuthRepository().delete(savedAuth);
                        throw new RuntimeException("Failed to assign default roles in Keycloak", roleException);
                    }

                    // 更新註冊狀態為 COMPLETED
                    user.setRegistrationStatus(RegistrationStatus.COMPLETED);
                    registrationRepository.getUserRepo().save(user);

                    log.info("User registration fully completed: {}", username);

                    return new RestResult<>(FinishRegistrationResponse.success(username));

                } catch (Exception keycloakException) {
                    log.error("Failed to create user in Keycloak: {}", username, keycloakException);
                    // Keycloak 建立失敗，刪除 Authenticator
                    try {
                        registrationRepository.getAuthRepository().delete(savedAuth);
                        log.info("Rolled back Authenticator after Keycloak creation failure: {}", username);
                    } catch (Exception deleteException) {
                        log.error("Failed to delete Authenticator during rollback: {}", username, deleteException);
                    }
                    return new RestResult<>(FinishRegistrationResponse.failure("Keycloak 建立用戶失敗: " + keycloakException.getMessage()));
                }

            } else {
                log.error("Cached request expired for user: {}", username);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cached request expired. Try to register again!");
            }
        } catch (RegistrationFailedException e) {
            log.error("WebAuthn registration failed: {}", e.getMessage());
            return new RestResult<>(FinishRegistrationResponse.failure("WebAuthn 註冊失敗: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error during finishauth: {}", e.getMessage(), e);
            return new RestResult<>(RestStatus.UNKNOWN.CODE, RestStatus.UNKNOWN.MESSAGE, e.getMessage());
        }
    }

    @PostMapping("/login")
    @ResponseBody
    public RestResult<CredentialGetResponse> startLogin(
            @RequestBody LoginRequest loginRequest
    ) {
        String username = loginRequest.getUsername();
        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
                .username(username)
                .build());
        try {
            this.assertionRequestMap.put(username, request);
            String credentialsJson = request.toCredentialsGetJson();
            ObjectMapper objectMapper = new ObjectMapper();
            CredentialGetResponse credentialsObject = objectMapper.readValue(credentialsJson, CredentialGetResponse.class);
            return new RestResult<>(credentialsObject);
        } catch (JsonProcessingException e) {
            return new RestResult<>(RestStatus.UNKNOWN, e.getMessage());
        }
    }

    @PostMapping("/welcome")
    public RestResult<FinishLoginResponse> finishLogin(
            @RequestBody FinishLoginRequest finishLoginRequest
    ) {
        try {
            //FIDO2: 驗證時: 伺服器使用公鑰，去驗證此簽章是否有效。
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc;
            pkc = PublicKeyCredential.parseAssertionResponseJson(finishLoginRequest.getCredential());
            AssertionRequest request = this.assertionRequestMap.get(finishLoginRequest.getUsername());

            // library 會自動用先前註冊時存的公鑰 去驗證簽章是否正確。
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request) // 前端登入請求時的 challenge/credentialId 等資訊
                    .response(pkc) // 前端傳回的簽章 (AuthenticatorAssertionResponse)
                    .build());
            if (result.isSuccess()) {
                return new RestResult<>(FinishLoginResponse.success(finishLoginRequest.getUsername()));
            } else {
                return new RestResult<>(FinishLoginResponse.failure("Authentication failed"));
            }
        } catch (IOException e) {
            throw new RuntimeException("Authentication failed", e);
        } catch (AssertionFailedException e) {
            throw new RuntimeException("Authentication failed", e);
        }

    }

    /**
     * 取消註冊（刪除暫存或已完成的用戶）
     * - PENDING 用戶：只刪除本地 DB
     * - COMPLETED 用戶：同時刪除 Keycloak 和本地 DB
     *
     * 安全性考量：使用 userId 而非 username，避免用戶枚舉攻擊
     */
    @DeleteMapping("/user/{userId}")
    @ResponseBody
    public RestResult<String> deleteUser(@PathVariable Long userId) {
        log.info("User deletion requested for userId: {}", userId);

        AppUser user = registrationRepository.getUserRepo().findById(userId).orElse(null);
        if (user == null) {
            log.warn("User not found for deletion, userId: {}", userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        String username = user.getUsername();

        // 根據註冊狀態決定刪除策略
        if (user.getRegistrationStatus() == RegistrationStatus.PENDING) {
            // PENDING 用戶：只刪除本地 DB（Keycloak 根本沒建立）
            log.info("Deleting PENDING user (local DB only): {}", username);
            try {
                registrationRepository.getUserRepo().delete(user);
                log.info("Successfully deleted pending user from local DB: {}", username);
                return new RestResult<>(RestStatus.SUCCESS, "Pending user " + username + " deleted successfully");
            } catch (Exception dbException) {
                log.error("Failed to delete pending user from local DB: {}", username, dbException);
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to delete pending user from local database",
                        dbException
                );
            }
        } else {
            // COMPLETED 用戶：刪除 Keycloak + 本地 DB
            log.info("Deleting COMPLETED user (Keycloak + local DB): {}", username);

            // 先刪除 Keycloak 用戶
            try {
                if (user.getKeycloakUserId() != null) {
                    keycloakService.deleteUser(user.getKeycloakUserId());
                    log.info("Successfully deleted user from Keycloak: {}", username);
                } else {
                    // 如果沒有 keycloakUserId，嘗試用 username 查詢後刪除
                    log.warn("No keycloakUserId found for user {}, attempting to delete by username", username);
                    try {
                        keycloakService.deleteUserByUsername(username);
                    } catch (Exception e) {
                        log.warn("User {} not found in Keycloak, continuing with local deletion", username);
                    }
                }
            } catch (Exception keycloakException) {
                log.error("Failed to delete user from Keycloak: {}", username, keycloakException);
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to delete user from authentication system",
                        keycloakException
                );
            }

            // 刪除本地 DB 用戶
            try {
                registrationRepository.getUserRepo().delete(user);
                log.info("Successfully deleted user from local DB: {}", username);
            } catch (Exception dbException) {
                log.error("Failed to delete user from local DB: {}", username, dbException);
                // 如果本地刪除失敗，但 Keycloak 已刪除，需要記錄這個不一致狀態
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "User deleted from Keycloak but failed to delete from local database",
                        dbException
                );
            }

            return new RestResult<>(RestStatus.SUCCESS, "User " + username + " deleted successfully");
        }
    }
}
