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
import com.webauthn.app.user.AppUser;
import com.webauthn.app.utility.Utility;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final RelyingParty relyingParty;
    private final RegistrationService registrationService;
    private Map<String, PublicKeyCredentialCreationOptions> requestOptionMap = new HashMap<>();
    private Map<String, AssertionRequest> assertionRequestMap = new HashMap<>();

    AuthController(RegistrationService service, RelyingParty relyingPary) {
        this.relyingParty = relyingPary;
        this.registrationService = service;
    }

    @GetMapping("/")
    public String welcome() {
        return "index";
    }

    @GetMapping("/register")
    public String registerUser(Model model) {
        return "register";
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
            registrationService.getUserRepo().save(saveUser);
            return performAuthRegistration(saveUser);
        } else {
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

    @GetMapping("/login")
    public String loginPage() {
        return "login";
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
