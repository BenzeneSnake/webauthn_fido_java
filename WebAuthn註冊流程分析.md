# WebAuthn 註冊流程分析

## 概述

這個專案實作了 WebAuthn (Web Authentication) 的註冊功能，讓使用者可以使用生物辨識或硬體金鑰進行安全註冊。整個流程分為三個主要步驟：使用者註冊、認證器註冊、完成註冊。

## 架構組成

### 核心類別

1. **AuthController** - 控制器，處理所有 HTTP 請求
2. **RegistrationService** - 服務層，實作 CredentialRepository 介面
3. **AppUser** - 使用者實體
4. **Authenticator** - 認證器實體
5. **UserRepository** - 使用者資料存取層
6. **AuthenticatorRepository** - 認證器資料存取層

## 詳細流程分析

### 第一階段：使用者註冊 (`/register` POST)

**位置**: `AuthController.java:48-67`

```java

@PostMapping("/register")
public String newUserRegistration(
        @RequestParam String username,
        @RequestParam String display
)
```

**流程**:

1. 檢查使用者名稱是否已存在 (`service.getUserRepo().findByUsername(username)`)
2. 如果不存在，建立 `UserIdentity` 物件：
    - `name`: 使用者名稱
    - `displayName`: 顯示名稱
    - `id`: 隨機生成的 32 位元組 ID (`Utility.generateRandom(32)`)防止跨站追蹤，確保不同網站無法透過相同的使用者 ID
      來追蹤同一個使用者
3. 建立並儲存 `AppUser` 實體到資料庫
4. 自動呼叫 `newAuthRegistration()` 進入下一階段

**錯誤處理**: 如果使用者已存在，拋出 HTTP 409 CONFLICT

### 第二階段：認證器註冊 (`/registerauth` POST)

**位置**: `AuthController.java:69-99`

```java

@PostMapping("/registerauth")
public String newAuthRegistration(@RequestParam AppUser user)
```

**流程**:

1. 驗證使用者是否存在 (`service.getUserRepo().findByHandle(user.getHandle())`)
2. 將 `AppUser` 轉換為 `UserIdentity` (`user.toUserIdentity()`)
3. 建立註冊選項：
   ```java
   StartRegistrationOptions registrationOptions = StartRegistrationOptions.builder()
       .user(userIdentity)
       .build();
   ```
4. 透過 `RelyingParty` 開始註冊流程：
   ```java
   PublicKeyCredentialCreationOptions registration = relyingParty.startRegistration(registrationOptions);
   ```
5. 將註冊選項快取到 `requestOptionMap` (以 username 為 key)
6. 回傳 JSON 格式的憑證建立選項給前端

**備註**: 程式碼中有註解的 `AuthenticatorSelectionCriteria`，可用於指定認證器類型（如外部裝置、使用者驗證等）

### 第三階段：完成註冊 (`/finishauth` POST)

**位置**: `AuthController.java:101-131`

```java

@PostMapping("/finishauth")
public ModelAndView finishRegisration(
        @RequestParam String credential,
        @RequestParam String username,
        @RequestParam String credname
)
```

**流程**:

1. 根據 username 找到使用者和快取的註冊選項
2. 解析前端傳來的憑證回應：
   ```java
   PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc =
       PublicKeyCredential.parseRegistrationResponseJson(credential);
   ```
3. 建立完成註冊的選項：
   ```java
   FinishRegistrationOptions options = FinishRegistrationOptions.builder()
       .request(requestOptions)
       .response(pkc)
       .build();
   ```
4. 透過 `RelyingParty` 完成註冊驗證：
   ```java
   RegistrationResult result = relyingParty.finishRegistration(options);
   ```
5. 建立並儲存 `Authenticator` 實體：
   ```java
   Authenticator savedAuth = new Authenticator(result, pkc.getResponse(), user, credname);
   service.getAuthRepository().save(savedAuth);
   ```
6. 重定向到登入頁面

**錯誤處理**:

- 註冊選項過期：HTTP 500 INTERNAL_SERVER_ERROR
- 註冊驗證失敗：HTTP 502 BAD_GATEWAY
- 儲存失敗：HTTP 400 BAD_REQUEST

## 資料模型

### AppUser 實體 (`AppUser.java`)

```java

@Entity
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;        // 使用者名稱（唯一）

    @Column(nullable = false)
    private String displayName;     // 顯示名稱

    @Lob
    @Column(nullable = false, length = 64)
    private ByteArray handle;       // WebAuthn 使用者 ID（隨機生成）
}
```

**關鍵方法**:

- `toUserIdentity()`: 轉換為 WebAuthn 的 UserIdentity 物件

### Authenticator 實體 (`Authenticator.java`)

```java

@Entity
public class Authenticator {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private String name;            // 認證器名稱
    private ByteArray credentialId; // 憑證 ID
    private ByteArray publicKey;    // 公鑰
    private Long count;             // 簽章計數器
    private ByteArray aaguid;       // 認證器 GUID

    @ManyToOne
    private AppUser user;           // 關聯的使用者
}
```

**建構子**: 從 `RegistrationResult` 和 `AuthenticatorAttestationResponse` 提取資料

## 資料存取層

### UserRepository (`UserRepository.java`)

```java
public interface UserRepository extends CrudRepository<AppUser, Long> {
    AppUser findByUsername(String name);    // 根據使用者名稱查詢

    AppUser findByHandle(ByteArray handle); // 根據 WebAuthn handle 查詢
}
```

### AuthenticatorRepository (`AuthenticatorRepository.java`)

```java
public interface AuthenticatorRepository extends CrudRepository<Authenticator, Long> {
    Optional<Authenticator> findByCredentialId(ByteArray credentialId);      // 根據憑證 ID 查詢

    List<Authenticator> findAllByUser(AppUser user);                         // 查詢使用者的所有認證器

    List<Authenticator> findAllByCredentialId(ByteArray credentialId);       // 查詢憑證 ID 的所有認證器
}
```

## 服務層

### RegistrationService (`RegistrationService.java`)

實作Yubico的 `CredentialRepository` 介面，提供 WebAuthn 函式庫需要的憑證管理功能：

1. **getCredentialIdsForUsername()**: 取得使用者的所有憑證 ID
2. **getUserHandleForUsername()**: 根據使用者名稱取得 handle
3. **getUsernameForUserHandle()**: 根據 handle 取得使用者名稱
4. **lookup()**: 查詢特定憑證
5. **lookupAll()**: 查詢憑證 ID 的所有憑證

## 安全考量

1. **隨機 Handle**: 使用者 handle 使用 32 位元組隨機數，避免可預測性
2. **憑證驗證**: 透過 `RelyingParty.finishRegistration()` 進行完整的憑證驗證
3. **簽章計數器**: 儲存並追蹤簽章計數器，防止重放攻擊
4. **快取管理**: 註冊選項有暫存機制，防止過期請求

## 總結

這個 WebAuthn 註冊流程完整實作了現代化的無密碼認證系統，主要特點：

- **分階段處理**: 將註冊分為使用者建立、認證器註冊、完成驗證三個階段
- **標準化實作**: 使用 Yubico WebAuthn 函式庫，符合 W3C WebAuthn 標準
- **資料持久化**: 完整的 JPA 實體設計，支援多認證器
- **錯誤處理**: 完善的異常處理和 HTTP 狀態碼回應
- **安全設計**: 隨機 handle、憑證驗證、防重放攻擊等安全機制