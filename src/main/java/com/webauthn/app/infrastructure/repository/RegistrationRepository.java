package com.webauthn.app.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.webauthn.app.authenticator.Authenticator;
import com.webauthn.app.authenticator.AuthenticatorRepository;
import com.webauthn.app.user.AppUser;
import com.webauthn.app.user.UserRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import lombok.Getter;

@Repository
@Getter
public class RegistrationRepository implements CredentialRepository {
    @Autowired
    private UserRepository userRepo;
    @Autowired
    private AuthenticatorRepository authRepository;

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        AppUser user = userRepo.findByUsername(username);
        List<Authenticator> auth = authRepository.findAllByUser(user);
        return auth.stream()
                .map(
                        credential ->
                                PublicKeyCredentialDescriptor.builder()
                                        .id(credential.getCredentialId())
                                        .build())
                .collect(Collectors.toSet());
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        AppUser user = userRepo.findByUsername(username);
        //1.不透明的位元組陣列，不會洩露用戶名等敏感資訊
        //2.認證器(如 YubiKey)內部使用這個 handle 來識別用戶，而非用戶名
        return Optional.of(user.getHandle());
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        AppUser user = userRepo.findByHandle(userHandle);
        return Optional.of(user.getUsername());
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        Optional<Authenticator> auth = authRepository.findByCredentialId(credentialId);
        return auth.map(
                credential ->
                        RegisteredCredential.builder()
                                .credentialId(credential.getCredentialId())
                                .userHandle(credential.getUser().getHandle())
                                .publicKeyCose(credential.getPublicKey())
                                .signatureCount(credential.getCount())
                                .build()
        );
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        List<Authenticator> auth = authRepository.findAllByCredentialId(credentialId);
        return auth.stream()
                .map(
                        credential ->
                                RegisteredCredential.builder()
                                        .credentialId(credential.getCredentialId())
                                        .userHandle(credential.getUser().getHandle())
                                        .publicKeyCose(credential.getPublicKey())
                                        .signatureCount(credential.getCount())
                                        .build())
                .collect(Collectors.toSet());
    }
}