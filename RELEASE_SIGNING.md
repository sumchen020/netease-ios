# v1.4.0 固定正式签名配置（首次发布前必须完成）

正式版本 `1.4.0`、`versionCode=7`、包名 `me.naptila.neteaseios`。从此版本开始应始终使用**同一套开发者保管的 Release 密钥**。不要用 GitHub Actions 每次自动生成的 Debug 签名发布正式版。

## 1. 在可信电脑上生成并备份 Release 密钥

确保安装了 Java JDK，并在**自己的电脑**终端执行：

```bash
keytool -genkeypair -v -storetype PKCS12 -keystore netease-ios-release.p12 -alias netease-ios-release -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=netease-ios, O=Independent Developer"
```

按提示设置密钥库密码（PKCS12 建议密钥密码与库密码相同）。请将 `netease-ios-release.p12` 和密码分别安全备份至少两份，**不要提交到 GitHub 或在聊天中发送私钥、密码**。如果将来丢失私钥，已安装的正式版通常无法使用新签名直接覆盖升级。

通过以下命令查看证书指纹：

```bash
keytool -list -v -storetype PKCS12 -keystore netease-ios-release.p12 -alias netease-ios-release
```

记录证书 `SHA256`（带冒号或不带冒号均可），不是 APK 文件的 SHA-256。

## 2. GitHub Actions 设置五个 Repository Secrets

打开 GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 将本地 `.p12` 文件整体转换成 Base64 后的文本 |
| `SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `SIGNING_KEY_ALIAS` | `netease-ios-release` |
| `SIGNING_KEY_PASSWORD` | 私钥密码 |
| `SIGNING_CERT_SHA256` | 上一步记录的**签名证书 SHA-256 指纹** |

在 Windows PowerShell 中生成 Base64 文本：

```powershell
[Convert]::ToBase64String([System.IO.File]::ReadAllBytes((Resolve-Path .\netease-ios-release.p12)))
```

Base64 **不是加密**，不可将其公开上传仓库、Issue 或分享链接；仅粘贴至对应 GitHub Secret。

## 3. 正式发布步骤

合并发布准备 PR 后，打开 GitHub 仓库 → Actions → **Release APK** → Run workflow：
- Branch 选择 `main`
- `release_tag` 填写 `v1.4.0`
- 确认五个 Secrets 已经配置，再执行 workflow

该工作流按以下顺序执行：检查 tag 和主分支提交 → 确认签名材料存在 → 构建 Release → 核对包名、versionName、versionCode、APK Signature Scheme v2 和固定证书指纹 → 生成校验文件 → 通过后发布 GitHub Release。

**任何凭据缺失、包名或签名不一致均会终止发布。** 后续版本应修改 `app/build.gradle.kts` 的 versionName 和 versionCode，并使用**同一套密钥**发布。
