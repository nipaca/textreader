# Signing Text Reader (Windows)

Text Reader is signed with **one permanent key**, so every new version installs as an update over
the old one. The key is made once on your computer and stored only in GitHub's hidden secrets —
**never** in the repository.

> Use a **new key just for Text Reader** — not the Smart Search key.

## 1. Make the key (once)

Open **PowerShell** in a folder you'll remember (for example `Documents`) and run:

```powershell
keytool -genkeypair -v -keystore textreader.jks -alias textreader -keyalg RSA -keysize 4096 -validity 36500
```

- It asks for a **keystore password** (twice). Choose a strong one and save it in your password manager.
- It asks for your name, organization and so on. Anything is fine (you can press Enter).
- At the end type `yes` to confirm.
- The key password is the same as the keystore password.

If PowerShell says `keytool` isn't recognized, use the full path to your JDK, for example:

```powershell
& "C:\Program Files\Java\jdk-17\bin\keytool.exe" -genkeypair -v -keystore textreader.jks -alias textreader -keyalg RSA -keysize 4096 -validity 36500
```

## 2. Copy the key as text

In the same PowerShell window:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\textreader.jks")) | Set-Clipboard
```

The key is now on your clipboard as one long line of text (no file is created).

## 3. Add the four secrets in GitHub

Your repo → **Settings → Secrets and variables → Actions → New repository secret**.
Add each of these (name exactly as shown):

| Name | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | paste (Ctrl+V) the long text from step 2 |
| `SIGNING_KEYSTORE_PASSWORD` | your keystore password |
| `SIGNING_KEY_ALIAS` | `textreader` |
| `SIGNING_KEY_PASSWORD` | the same password again |

After adding the first secret, clear your clipboard (copy any other word) so the key text isn't left there.

## 4. Keep the key safe

- Keep `textreader.jks` and its password in a safe place (for example your password manager, which can
  store files, plus a backup on a USB drive).
- **Never** upload `textreader.jks` to GitHub. (`.gitignore` already blocks `*.jks`.)
- If the key is lost, future builds can't update the installed app — you'd have to uninstall once and
  start over with a new key.

## 5. Build and install

1. Actions → **Build Text Reader** → **Run workflow**.
2. The first time you switch to this key, **uninstall the old Text Reader** on the phone once
   (it was signed with a temporary key), then install the new APK from Releases.
3. From then on, new versions install over the old one and keep your settings.

## Safety built into the build

- If any of the four secrets is missing, the build **stops right away** — it never releases an APK
  signed with a temporary key.
- The decoded key file is **deleted at the end of every build**, even if the build fails.
- Each release lists the **signing certificate SHA-256**. It should be the same on every release.
