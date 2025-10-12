package com.webauthn.app.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 預設角色指派策略實作
 * 從 keycloak.yml 讀取角色配置
 */
@Component
@ConfigurationProperties(prefix = "keycloak.roles")
public class DefaultRoleStrategy implements RoleStrategy {

    private List<String> defaultRoles = new ArrayList<>();
    private List<String> verifiedRoles = new ArrayList<>();

    @Override
    public List<String> getDefaultRoles(String username) {
        return new ArrayList<>(defaultRoles);
    }

    @Override
    public List<String> getVerifiedRoles(String username) {
        return new ArrayList<>(verifiedRoles);
    }

    @Override
    public List<String> getRolesByUserType(String username, String userType) {
        // 未來可擴展：根據 userType 返回不同角色
        // 例如：if ("admin".equals(userType)) return adminRoles;
        return getDefaultRoles(username);
    }

    public List<String> getDefault() {
        return defaultRoles;
    }

    public void setDefault(List<String> defaultRoles) {
        this.defaultRoles = defaultRoles;
    }

    public List<String> getVerified() {
        return verifiedRoles;
    }

    public void setVerified(List<String> verifiedRoles) {
        this.verifiedRoles = verifiedRoles;
    }
}
