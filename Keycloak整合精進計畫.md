# Keycloak 整合精進計畫

## 📌 目標
優化 WebAuthn + Keycloak 整合流程，提升系統可靠性、安全性與可維護性

---

## 🎯 現有流程分析

### 當前架構
```
User註冊 → 本地DB儲存 → Keycloak API建立User → Keycloak API指派角色
```

### 現有實作位置
- **註冊入口**: `AuthController.java:47-68` (`/api/register`)
- **Keycloak服務**: `KeycloakService.java`
- **配置檔**: `keycloak.yml`

---

## ⚠️ 識別出的問題

### 1. 交易一致性問題（Critical）
**問題描述**：
- 本地DB儲存成功，但Keycloak API失敗 → 用戶無法取得權限
- Keycloak建立用戶成功，但角色指派失敗 → 用戶無法使用功能
- 無rollback機制，導致數據不一致

**影響範圍**：
- 用戶體驗：註冊成功但無法登入
- 數據完整性：兩個系統狀態不同步
- 運維成本：需手動修正不一致數據

### 2. Token管理問題（Critical）
**問題描述**：
- `KeycloakService.java:42` 只檢查token是否為null
- 沒有檢查過期時間
- Token過期後API呼叫會失敗

**影響範圍**：
- 間歇性註冊失敗
- 錯誤訊息不明確

### 3. 角色管理策略問題（Important）
**問題描述**：
- 角色名稱hard-code在程式碼中（未來可能出現）
- 無法彈性調整不同用戶的角色配置
- 缺乏依據業務邏輯動態指派角色的機制

### 4. 錯誤處理與重試（Important）
**問題描述**：
- Keycloak暫時性故障會直接失敗
- 無重試機制
- 無失敗補償流程

### 5. 可觀測性不足（Nice to have）
**問題描述**：
- 缺乏監控指標
- 難以追蹤Keycloak整合的成功率
- 無法掌握API回應時間

### 6. QR Code跨裝置認證失敗
**問題描述**：
- Origin配置僅允許 `http://localhost:4200`
- 手機掃QR Code時使用不同origin
- Request快取可能因耗時過長而超時

---

## 🚀 改進計畫

### Phase 1: 基礎穩定性（P0 - 必須完成）

#### ✅ Task 1.1: 實作交易一致性處理
**目標**: 確保本地DB與Keycloak數據一致

**實作內容**:
1. 在 `AuthController.newUserRegistration()` 加入錯誤處理
2. Keycloak操作失敗時rollback本地用戶
3. 加入健康檢查，確認Keycloak可用才允許註冊

**程式位置**: `AuthController.java:47-68`

**預期成果**:
- 註冊失敗時，本地DB與Keycloak保持一致
- 用戶得到明確錯誤訊息

---

#### ✅ Task 1.2: Token過期時間管理
**目標**: 避免使用過期token導致API失敗

**實作內容**:
1. 在 `KeycloakService` 加入 `tokenExpiry` 欄位
2. 取得token時記錄過期時間
3. `getAdminToken()` 檢查過期時間，提前30秒更新

**程式位置**: `KeycloakService.java:36-63`

**預期成果**:
- 不再因token過期導致API呼叫失敗
- 減少不必要的token請求

---

#### ✅ Task 1.3: 基本重試機制
**目標**: 處理Keycloak暫時性故障

**實作內容**:
1. 在 `KeycloakService` 加入 `createUserWithRetry()` 方法
2. 設定重試次數（建議3次）
3. 使用指數退避策略（1s, 2s, 4s）

**程式位置**: 新增於 `KeycloakService.java`

**預期成果**:
- 暫時性網路問題不會導致註冊失敗
- 提升整體註冊成功率

---

### Phase 2: 彈性與配置化（P1 - 重要）

#### ✅ Task 2.1: 角色配置化
**目標**: 角色管理更彈性，不hard-code

**實作內容**:
1. 在 `keycloak.yml` 定義角色配置
2. 建立 `RoleStrategy` 介面
3. 實作 `DefaultRoleStrategy`

**新增檔案**:
- `config/RoleStrategy.java` (interface)
- `config/DefaultRoleStrategy.java` (implementation)

**配置範例**:
```yaml
keycloak:
  roles:
    default: ["authenticated-user"]
    verified: ["verified-user", "webauthn-enabled"]
```

**預期成果**:
- 角色可透過配置調整，無需改程式碼
- 支援依用戶狀態動態指派角色

---

#### ✅ Task 2.2: 增強錯誤處理與日誌
**目標**: 提升問題排查效率

**實作內容**:
1. 統一異常處理
2. 加入結構化日誌
3. 記錄關鍵操作（建立用戶、指派角色）

**程式位置**: `KeycloakService.java` 各方法

**預期成果**:
- 錯誤訊息更明確
- 方便追蹤問題

---

### Phase 3: 進階優化（P2 - 建議）

#### ✅ Task 3.1: 非同步處理 + 補償機制
**目標**: 提升用戶體驗，不阻塞註冊流程

**實作內容**:
1. 建立 `UserSyncService`
2. 註冊時非同步同步到Keycloak
3. 失敗時加入補償佇列
4. 實作定時重試任務

**新增檔案**:
- `service/UserSyncService.java`

**AppUser新增欄位**:
```java
private Boolean keycloakSynced = false;
private Integer syncRetryCount = 0;
private LocalDateTime lastSyncAttempt;
```

**預期成果**:
- 註冊回應更快速
- Keycloak暫時故障不影響用戶註冊
- 自動補償機制確保最終一致性

---

#### ✅ Task 3.2: 健康檢查端點
**目標**: 監控Keycloak連線狀態

**實作內容**:
1. 在 `KeycloakService` 實作 `isHealthy()`
2. 建立 `/actuator/health/keycloak` 端點
3. 整合Spring Boot Actuator

**預期成果**:
- 可透過API查詢Keycloak連線狀態
- 註冊前可檢查服務可用性

---

### Phase 4: 可觀測性（P3 - 優化）

#### ✅ Task 4.1: 監控指標
**目標**: 掌握系統運作狀況

**實作內容**:
1. 整合Micrometer
2. 記錄關鍵指標：
   - `keycloak.user.create.success` (計數)
   - `keycloak.user.create.failure` (計數)
   - `keycloak.user.create.duration` (耗時)
   - `keycloak.role.assign.success` (計數)

**預期成果**:
- 可視化Keycloak整合成功率
- 追蹤API回應時間
- 及早發現問題

---

### Phase 5: WebAuthn 跨裝置改進

#### ✅ Task 5.1: Origin 多來源支援
**目標**: 支援手機掃QR Code跨裝置認證

**實作內容**:
1. 修改 `application.yml` origin配置支援多個來源
2. 加入局域網IP支援
3. 考慮動態origin驗證

**配置範例**:
```yaml
authn:
  hostname: localhost
  origin:
    - http://localhost:4200
    - http://192.168.1.100:4200
    - http://[your-local-ip]:4200
```

**預期成果**:
- 手機掃QR Code可正常完成認證
- 支援跨裝置WebAuthn流程

---

#### ✅ Task 5.2: Request快取優化
**目標**: 避免跨裝置認證超時

**實作內容**:
1. 使用Redis取代內存Map（選配）
2. 加入快取過期時間配置
3. 實作快取清理機制

**程式位置**: `AuthController.java:39, 95, 142`

**預期成果**:
- 延長快取有效時間
- 支援分散式部署

---

## 📊 實作優先級總覽

| Phase | 任務 | 優先級 | 難度 | 預估時間 | 狀態 |
|-------|------|--------|------|---------|------|
| 1 | 交易一致性處理 | P0 | 中 | 2-3h | ✅ Done |
| 1 | Token過期管理 | P0 | 低 | 1h | ✅ Done |
| 1 | 基本重試機制 | P0 | 中 | 2h | ✅ Done |
| 2 | 角色配置化 | P1 | 低 | 2h | ✅ Done |
| 2 | 錯誤處理與日誌 | P1 | 低 | 1-2h | ✅ Done |
| 3 | 非同步處理 | P2 | 高 | 4-6h | ⏳ Pending |
| 3 | 健康檢查端點 | P2 | 中 | 1-2h | ⏳ Pending |
| 4 | 監控指標 | P3 | 中 | 2-3h | ⏳ Pending |
| 5 | Origin多來源 | P1 | 低 | 1h | ⏳ Pending |
| 5 | Request快取優化 | P2 | 高 | 3-4h | ⏳ Pending |

---

## 🔄 建議執行順序

### Week 1: 穩定性優先
1. Task 1.2: Token過期管理（最簡單，快速見效）
2. Task 1.1: 交易一致性處理（最重要）
3. Task 1.3: 基本重試機制

### Week 2: 彈性提升
4. Task 2.1: 角色配置化
5. Task 2.2: 錯誤處理與日誌
6. Task 5.1: Origin多來源支援（解決QR Code問題）

### Week 3+: 進階優化（選擇性）
7. Task 3.2: 健康檢查
8. Task 3.1: 非同步處理（需評估是否真的需要）
9. Task 4.1: 監控指標（production環境再考慮）

---

## 📝 開發注意事項

### 測試策略
每完成一個Task，需測試：
1. **正常流程**: 註冊 → Keycloak建立 → 角色指派 → 成功
2. **Keycloak無法連線**: 本地DB無資料，用戶收到錯誤
3. **Keycloak建立成功，角色指派失敗**: 需rollback Keycloak用戶
4. **Token過期**: 自動更新token並重試

### 回歸測試
- 確保原有WebAuthn流程不受影響
- 測試註冊、登入完整流程
- 驗證手機QR Code跨裝置認證

### 配置管理
- 敏感資訊（client-secret）考慮使用環境變數
- 不同環境（dev/staging/prod）使用不同配置檔

---

## 🎓 學習資源

### Keycloak相關
- [Keycloak Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/index.html)
- [Keycloak Role管理](https://www.keycloak.org/docs/latest/server_admin/#roles)

### WebAuthn相關
- [Yubico WebAuthn Library](https://developers.yubico.com/java-webauthn-server/)
- [WebAuthn Spec - Cross-platform Authenticators](https://www.w3.org/TR/webauthn-2/#sctn-authenticator-attachment-modality)

### Spring相關
- [Spring Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Spring Retry](https://github.com/spring-projects/spring-retry)
---

*文檔建立時間: 2025-10-06*
*最後更新: 2025-10-06*
