package com.webauthn.app.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.webauthn.app.authenticator.Authenticator;
import com.webauthn.app.user.AppUser;
import com.webauthn.app.utility.Utility;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Controller
public class AuthController {

    private final RelyingParty relyingParty;
    private final RegistrationService service;
    private Map<String, PublicKeyCredentialCreationOptions> requestOptionMap = new HashMap<>();
    private Map<String, AssertionRequest> assertionRequestMap = new HashMap<>();

    AuthController(RegistrationService service, RelyingParty relyingPary) {
        this.relyingParty = relyingPary;
        this.service = service;
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
    public String newUserRegistration(
        @RequestParam String username,
        @RequestParam String display
    ) {
        AppUser existingUser = service.getUserRepo().findByUsername(username);
        if (existingUser == null) {
            UserIdentity userIdentity = UserIdentity.builder()
                .name(username)
                .displayName(display)
                .id(Utility.generateRandom(32))//隨機id防止跨站羧宗
                .build();
            AppUser saveUser = new AppUser(userIdentity);
            service.getUserRepo().save(saveUser);
            return newAuthRegistration(saveUser);
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username " + username + " already exists. Choose a new name.");
        }
    }

    @PostMapping("/registerauth")
    @ResponseBody
    public String newAuthRegistration(
        @RequestParam AppUser user
    ) {
        AppUser existingUser = service.getUserRepo().findByHandle(user.getHandle());
        if (existingUser != null) {
            UserIdentity userIdentity = user.toUserIdentity();

            //加 authenticatorSelection
//            AuthenticatorSelectionCriteria selection = AuthenticatorSelectionCriteria.builder()
//                    .authenticatorAttachment(AuthenticatorAttachment.CROSS_PLATFORM) // 外部裝置 (手機、YubiKey)
//                    .userVerification(UserVerificationRequirement.PREFERRED)       // 可以 PIN / 生物辨識
//                    .build();

            StartRegistrationOptions registrationOptions = StartRegistrationOptions.builder()
            .user(userIdentity)
//                    .authenticatorSelection(selection)  // 把設定加進來
            .build();
            PublicKeyCredentialCreationOptions registration = relyingParty.startRegistration(registrationOptions);
            this.requestOptionMap.put(user.getUsername(), registration);

            try {
                    return registration.toCredentialsCreateJson();
            } catch (JsonProcessingException e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error processing JSON.", e);
            }
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User " + user.getUsername() + " does not exist. Please register.");
        }
    }

    @PostMapping("/finishauth")
    @ResponseBody
    public ModelAndView finishRegisration(
        @RequestParam String credential,
        @RequestParam String username,
        @RequestParam String credname
    ) {
            try {
                AppUser user = service.getUserRepo().findByUsername(username);

                PublicKeyCredentialCreationOptions requestOptions = this.requestOptionMap.get(username);
                if (requestOptions != null) {
                    PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
                    PublicKeyCredential.parseRegistrationResponseJson(credential);
                    FinishRegistrationOptions options = FinishRegistrationOptions.builder()
                        .request(requestOptions)
                        .response(pkc)
                        .build();
                    RegistrationResult result = relyingParty.finishRegistration(options);
                    Authenticator savedAuth = new Authenticator(result, pkc.getResponse(), user, credname);
                    service.getAuthRepository().save(savedAuth);
                    return new ModelAndView("redirect:/login", HttpStatus.SEE_OTHER);
                } else {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cached request expired. Try to register again!");
                }
            } catch (RegistrationFailedException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Registration failed.", e);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to save credenital, please try again!", e);
            }
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    @ResponseBody
    public String startLogin(
        @RequestParam String username
    ) {
        AssertionRequest request = relyingParty.startAssertion(StartAssertionOptions.builder()
            .username(username)
            .build());
        try {
            this.assertionRequestMap.put(username, request);
            return request.toCredentialsGetJson();
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/welcome")
    public String finishLogin(
        @RequestParam String credential,
        @RequestParam String username,
        Model model
    ) {
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc;
            pkc = PublicKeyCredential.parseAssertionResponseJson(credential);
            AssertionRequest request = this.assertionRequestMap.get(username);
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                .request(request)
                .response(pkc)
                .build());
            if (result.isSuccess()) {
                model.addAttribute("username", username);
                return "welcome";
            } else {
                return "index";
            }
        } catch (IOException e) {
            throw new RuntimeException("Authentication failed", e);
        } catch (AssertionFailedException e) {
            throw new RuntimeException("Authentication failed", e);
        }

    }
}
