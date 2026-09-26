# GKK Capacitor App — Setup Guide
### Signed Android APK + iOS IPA via GitHub Actions

---

## STEP 1 — Create Android Keystore (one-time, on your PC)

Run this command in your terminal (requires Java installed):

```bash
keytool -genkey -v \
  -keystore gkk-release.keystore \
  -alias gkk-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

You'll be asked for:
- **Keystore password** → save this (e.g. `MyStr0ngPass!`)
- **Key alias** → use `gkk-key`
- **Key password** → can be same as keystore password
- Name, org, city, country → fill anything (shown in cert, not public)

After creation, convert to Base64:

```bash
# Mac/Linux
base64 -i gkk-release.keystore | pbcopy   # copies to clipboard

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("gkk-release.keystore")) | Set-Clipboard
```

---

## STEP 2 — Add GitHub Secrets (Android)

Go to your repo → **Settings → Secrets and variables → Actions → New repository secret**

Add these 4 secrets:

| Secret Name | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The base64 string from above |
| `ANDROID_KEYSTORE_PASSWORD` | Your keystore password |
| `ANDROID_KEY_ALIAS` | `gkk-key` |
| `ANDROID_KEY_PASSWORD` | Your key password |

---

## STEP 3 — iOS Setup (App Store Connect API Key)

### 3a. Create API Key
1. Go to [appstoreconnect.apple.com](https://appstoreconnect.apple.com)
2. Users & Access → **Keys** tab
3. Click **+** → Name: `GKK CI` → Role: **App Manager**
4. Download the `.p8` file (you can only download it ONCE)
5. Note the **Key ID** and **Issuer ID** shown on the page

### 3b. Add GitHub Secrets (iOS)

| Secret Name | Value |
|---|---|
| `APP_STORE_CONNECT_API_KEY_ID` | Key ID from above (e.g. `ABC123DEFG`) |
| `APP_STORE_CONNECT_API_ISSUER_ID` | Issuer ID (UUID format) |
| `APP_STORE_CONNECT_API_KEY_CONTENT` | Contents of the `.p8` file (open in notepad, paste all text) |

---

## STEP 4 — Setup Fastlane Match (iOS certificates)

Fastlane Match stores your iOS certificates in a **private Git repo** (encrypted).

### 4a. Create a private repo
Create a new **private** GitHub repo, e.g. `your-org/gkk-ios-certs`

### 4b. Run match init (on your Mac)

```bash
gem install fastlane
cd ios/App
fastlane match init
# Choose: git
# Enter repo URL: https://github.com/your-org/gkk-ios-certs
```

### 4c. Generate certificates

```bash
fastlane match appstore --app_identifier com.gkk.app
```

This creates and stores all needed certs automatically.

### 4d. Add remaining GitHub Secrets

| Secret Name | Value |
|---|---|
| `MATCH_GIT_URL` | `https://github.com/your-org/gkk-ios-certs` |
| `MATCH_PASSWORD` | Password you set during `match init` |
| `MATCH_GIT_BASIC_AUTHORIZATION` | Base64 of `username:github_personal_access_token` |

To get `MATCH_GIT_BASIC_AUTHORIZATION`:
```bash
echo -n "your-github-username:your-pat-token" | base64
```

---

## STEP 5 — Copy Fastlane files

Copy the `ios-fastlane/` folder contents into `ios/App/fastlane/`:

```
ios/
  App/
    fastlane/
      Appfile    ← copy from ios-fastlane/Appfile
      Fastfile   ← copy from ios-fastlane/Fastfile
```

---

## STEP 6 — Push and trigger build

```bash
git add .
git commit -m "feat: add Capacitor + signed build workflow"
git push origin main
```

The workflow triggers automatically. Go to **Actions** tab in GitHub to watch it.

---

## Output Files

After build succeeds, go to **Actions → your run → Artifacts**:

| File | Use |
|---|---|
| `GKK-release-signed.apk` | Install directly on Android |
| `GKK-release-signed.aab` | Upload to Google Play Store |
| `GKK-release-signed.ipa` | Upload to App Store / TestFlight |

---

## Folder structure to commit

```
GKK-main/
├── .github/
│   └── workflows/
│       └── build-signed.yml    ← main CI workflow
├── ios-fastlane/
│   ├── Appfile
│   └── Fastfile
├── capacitor.config.ts
├── package.json
├── index.html
├── app.js
├── style.css
├── sw.js
└── manifest.json
```

---

## Quick Reference — All Required Secrets

| Secret | Platform |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Android |
| `ANDROID_KEYSTORE_PASSWORD` | Android |
| `ANDROID_KEY_ALIAS` | Android |
| `ANDROID_KEY_PASSWORD` | Android |
| `APP_STORE_CONNECT_API_KEY_ID` | iOS |
| `APP_STORE_CONNECT_API_ISSUER_ID` | iOS |
| `APP_STORE_CONNECT_API_KEY_CONTENT` | iOS |
| `MATCH_GIT_URL` | iOS |
| `MATCH_PASSWORD` | iOS |
| `MATCH_GIT_BASIC_AUTHORIZATION` | iOS |
