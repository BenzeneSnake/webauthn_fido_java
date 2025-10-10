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
import com.webauthn.app.service.RegistrationService;
import com.webauthn.app.user.AppUser;
import com.webauthn.app.utility.Utility;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final RelyingParty relyingParty;
    private final RegistrationService registrationService;
    private final KeycloakService keycloakService;
    private Map<String, PublicKeyCredentialCreationOptions> requestOptionMap = new HashMap<>();
    private Map<String, AssertionRequest> assertionRequestMap = new HashMap<>();

    AuthController(RegistrationService service, RelyingParty relyingPary, KeycloakService keycloakService) {
        this.relyingParty = relyingPary;
        this.registrationService = service;
        this.keycloakService = keycloakService;
    }

    @PostMapping("/register")
    @ResponseBody
    public RestResult<CredentialCreateResponse> newUserRegistration(
            @RequestBody RegisterRequest request
    ) {
        String username = request.getUsername();
        String display = request.getDisplay();

        AppUser existingUser = registrationService.getUserRepo().findByUsername(username);
        if (existingUser == null) {
            UserIdentity userIdentity = UserIdentity.builder()
                    .name(username)
                    .displayName(display)
                    .id(Utility.generateRandom(32))//隨機id防止跨站羧宗
                    .build();
            AppUser saveUser = new AppUser(userIdentity);
            // 先儲存到本地 DB
            registrationService.getUserRepo().save(saveUser);

            // 在 Keycloak 建立用戶，若失敗則本地DB用戶也不儲存
            String keycloakUserId = null;
            try {
                keycloakUserId = keycloakService.createUserWithRetry(username);
                log.info("新User創建在Keycloak: {} with userId: {}", username, keycloakUserId);

                // 嘗試指派預設角色
                try {
                    //TODO:view_entry_role是自訂role，作為串接keycloak練習用，之後可以改成依 使用者類型或策略自動分配角色
                    //TODO:註冊時可以帶一個 userType 或 groupType 欄位，後端根據 userType 決定是否 assign view_entry_role
                    keycloakService.assignRole(keycloakUserId, "view_entry_role");
                    log.info("指派預設角色給用戶 in Keycloak: {}", username);
                } catch (Exception roleException) {
                    log.error("指派預設角色 in Keycloak 失敗: {}", username, roleException);
                    // 角色指派失敗，rollback Keycloak 用戶
                    try {
                        //刪除用戶
                        keycloakService.deleteUser(keycloakUserId);
                        log.info("Rolled back Keycloak user after role assignment failure: {}", username);
                    } catch (Exception deleteException) {
                        log.error("刪除用戶失敗: {}", username, deleteException);
                    }
                    throw new RuntimeException("指派預設角色給用戶 in Keycloak 失敗", roleException);
                }

                log.info("User registration completed successfully: {}", username);
                return performAuthRegistration(saveUser);

            } catch (Exception keycloakException) {
                log.error("Keycloak 操作失敗， user: {}", username, keycloakException);
                // Keycloak 操作失敗，rollback 本地用戶
                try {
                    registrationService.getUserRepo().delete(saveUser);
                    log.info("Keycloak 操作失敗，本地DB刪除用戶，user: {}", username);
                } catch (Exception deleteException) {
                    log.error("本地DB刪除用戶失敗，user: {}", username, deleteException);
                }
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Failed to create user in authentication system. Please try again later.",
                    keycloakException
                );
            }
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
        AppUser existingUser = registrationService.getUserRepo().findByHandle(user.getHandle());
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

//            try {
            return new RestResult<>(CredentialCreateResponse.from(registration));
//                    return registration.toCredentialsCreateJson();
//            } catch (JsonProcessingException e) {
//                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error processing JSON.", e);
//            }
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User " + user.getUsername() + " does not exist. Please register.");
        }
    }

    @PostMapping("/finishauth")
    @ResponseBody
    public RestResult<FinishRegistrationResponse> finishRegisration(
            @RequestBody FinishRegisrationRequest finishRegisrationRequest
    ) {

        try {
            String username = finishRegisrationRequest.getUsername();
            String credname = finishRegisrationRequest.getCredname();
            AppUser user = registrationService.getUserRepo().findByUsername(username);

            PublicKeyCredentialCreationOptions requestOptions = this.requestOptionMap.get(username);
            if (requestOptions != null) {
                PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc
                        = finishRegisrationRequest.getCredential();
                if (pkc == null) {
                    return new RestResult<>(RestStatus.VALID.CODE, RestStatus.VALID.MESSAGE, FinishRegistrationResponse.failure("認證憑證為空"));
                }
                byte[] attestationBytes = pkc.getResponse().getAttestationObject().getBytes();

                byte[] clientDataBytes = pkc.getResponse().getClientDataJSON().getBytes();

//                System.out.println("attestationObject (Base64): " + Base64.getEncoder().encodeToString(attestationBytes));//TODO: FOR TEST
//                System.out.println("clientDataJSON (Base64): " + Base64.getEncoder().encodeToString(clientDataBytes));//TODO: FOR TEST
                FinishRegistrationOptions options = FinishRegistrationOptions.builder()
                        .request(requestOptions)
                        .response(pkc)
                        .build();
                RegistrationResult result = relyingParty.finishRegistration(options);
                Authenticator savedAuth = new Authenticator(result, pkc.getResponse(), user, credname);
                registrationService.getAuthRepository().save(savedAuth);

                // 清理快取
                this.requestOptionMap.remove(username);

                return new RestResult<>(FinishRegistrationResponse.success(username));
            } else {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cached request expired. Try to register again!");
            }
        } catch (RegistrationFailedException e) {
            return new RestResult<>(FinishRegistrationResponse.failure("註冊失敗: " + e.getMessage()));
        } catch (Exception e) {
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
            //登入完成，回傳簽章驗證資料
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc;
            pkc = PublicKeyCredential.parseAssertionResponseJson(finishLoginRequest.getCredential());
            AssertionRequest request = this.assertionRequestMap.get(finishLoginRequest.getUsername());
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(pkc)
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
}
