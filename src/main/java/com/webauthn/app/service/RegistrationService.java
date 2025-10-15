package com.webauthn.app.service;

import com.webauthn.app.authenticator.Authenticator;
import com.webauthn.app.common.api.RestResult;
import com.webauthn.app.exception.AppRegistrationException;
import com.webauthn.app.infrastructure.cache.WebAuthnRequestCache;
import com.webauthn.app.infrastructure.repository.RegistrationRepository;
import com.webauthn.app.rq.FinishRegisrationRequest;
import com.webauthn.app.rs.FinishRegistrationResponse;
import com.webauthn.app.strategy.RoleStrategy;
import com.webauthn.app.user.AppUser;
import com.webauthn.app.user.RegistrationStatus;
import com.webauthn.app.web.AuthController;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RegistrationService {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final RelyingParty relyingParty;
    private final RegistrationRepository registrationRepository;
    private final WebAuthnRequestCache webAuthnRequestCache;
    private final KeycloakService keycloakService;
    private final RoleStrategy roleStrategy;


    @Autowired
    public RegistrationService(RelyingParty relyingPary,RegistrationRepository registrationRepository,WebAuthnRequestCache webAuthnRequestCache, KeycloakService keycloakService,RoleStrategy roleStrategy){
        this.relyingParty = relyingPary;
        this.registrationRepository = registrationRepository;
        this.webAuthnRequestCache = webAuthnRequestCache;
        this.keycloakService = keycloakService;
        this.roleStrategy = roleStrategy;
    }

    /**
     * 完成註冊
     * 1.取得前端user，判斷是否存在
     * 2.取得傳給瀏覽器存在Cache的PublicKeyCredentialCreationOptions
     * 3.驗證 WebAuthn 並儲存 Authenticator
     * 4.建立 Keycloak user，指派角色
     * 5.更新 user 狀態
     * 6.清理Cache
     * @param request
     * @return
     */
    public FinishRegistrationResponse completeRegistration(FinishRegisrationRequest request) throws RegistrationFailedException {
        // 1.取得前端user，判斷是否存在
        String username = request.getUsername();
        AppUser user = registrationRepository.getUserRepo().findByUsername(username);
        if (user == null) {
            throw new AppRegistrationException("用戶不存在");
        }

        // 2.取得傳給瀏覽器存在Cache的PublicKeyCredentialCreationOptions
        PublicKeyCredentialCreationOptions requestOptions =webAuthnRequestCache.get(username);
        if (requestOptions == null) {
            throw new AppRegistrationException("cache 失敗，Try to register again!");
        }

        Authenticator savedAuth = null;
        String keycloakUserId = null;

        try {
            // 3.驗證 WebAuthn 並儲存 Authenticator
            savedAuth = verifyWebAuthnAndSaveAuthenticator(request, user, requestOptions);

            // 4.建立 Keycloak user 並指派角色
            keycloakUserId = createKeycloakUserAndAssignRoles(username);

            // 5.更新 user 狀態
            user.setKeycloakUserId(keycloakUserId);//儲存 for rollback角色指派失敗
            user.setRegistrationStatus(RegistrationStatus.COMPLETED);
            registrationRepository.getUserRepo().save(user);

            return FinishRegistrationResponse.success(username);
        }catch (Exception e) {
            rollbackAfterFailure(savedAuth,keycloakUserId);
            throw e;  // 交由 Controller 處理
        }finally {
            // 6.清理快取
            webAuthnRequestCache.remove(username);
        }
    }

    /**
     * 驗證 WebAuthn 並儲存 Authenticator
     * @param request
     * @param user
     * @param requestOptions
     * @return
     * @throws RegistrationFailedException
     */
    private Authenticator verifyWebAuthnAndSaveAuthenticator(FinishRegisrationRequest request, AppUser user,
                                                             PublicKeyCredentialCreationOptions requestOptions) throws RegistrationFailedException {
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc
                = request.getCredential();
        if (pkc == null) {
            throw new AppRegistrationException("認證憑證為空");
        }

        //FIDO2: 驗證時: 伺服器使用公鑰，去驗證此簽章是否有效。
        FinishRegistrationOptions options = FinishRegistrationOptions.builder()
                .request(requestOptions)
                .response(pkc)
                .build();
        RegistrationResult result = relyingParty.finishRegistration(options);
        log.info("Stage 2: 完成認證 WebAuthn verification successful for user: {}", request.getUsername());

        // WebAuthn 驗證成功，儲存 Authenticator
        Authenticator auth = new Authenticator(result, pkc.getResponse(), user, request.getCredname());
        registrationRepository.getAuthRepository().save(auth);
    return auth;
    }

    /**
     * 建立 Keycloak user，指派角色
     * @param username
     * @return
     */
    private String createKeycloakUserAndAssignRoles(String username) {
        String keycloakUserId = keycloakService.createUserWithRetry(username);

        List<String> defaultRoles = roleStrategy.getDefaultRoles(username);
        if (defaultRoles != null && !defaultRoles.isEmpty()) {
            keycloakService.assignRoles(keycloakUserId, defaultRoles);
        } else {
            log.warn("沒有預設的roles for user: {}", username);
        }

        return keycloakUserId;
    }

    /**
     * DB建立用戶跟Keycloak新增角色、指派用戶給角色，須保持交易一致性，若沒有則
     * @param auth
     * @param keycloakUserId
     */
    private void rollbackAfterFailure(Authenticator auth, String keycloakUserId) {
        if (auth != null) {
            try {
                registrationRepository.getAuthRepository().delete(auth);
                log.info("Rolled back Authenticator");
            } catch (Exception e) {
                log.error("Failed to delete Authenticator during rollback", e);
            }
        }
        if (keycloakUserId != null) {
            try {
                keycloakService.deleteUser(keycloakUserId);
                log.info("Rolled back Keycloak user: {}", keycloakUserId);
            } catch (Exception e) {
                log.error("Failed to delete Keycloak user during rollback", e);
            }
        }
    }
}
