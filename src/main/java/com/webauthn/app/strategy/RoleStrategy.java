package com.webauthn.app.strategy;

import java.util.List;

/**
 * 角色指派策略介面
 * 定義如何為用戶分配 Keycloak 角色
 */
public interface RoleStrategy {

    /**
     * 取得用戶註冊時應分配的角色列表 (預設ROLE)
     *
     * @param username 用戶名稱
     * @return 角色名稱列表
     */
    List<String> getDefaultRoles(String username);

    /**
     * 取得用戶完成 WebAuthn 驗證後應分配的角色列表
     *
     * @param username 用戶名稱
     * @return 角色名稱列表
     */
    List<String> getVerifiedRoles(String username);

    /**
     * 根據用戶類型取得應分配的角色
     * 未來可擴展支援不同用戶類型（例如：普通用戶、管理員、VIP等）
     *
     * @param username 用戶名稱
     * @param userType 用戶類型
     * @return 角色名稱列表
     */
    default List<String> getRolesByUserType(String username, String userType) {
        return getDefaultRoles(username);
    }
}
