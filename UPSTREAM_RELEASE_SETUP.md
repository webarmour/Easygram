# Automatic upstream releases

The `Upstream merge and stable release` workflow checks `DrKLO/Telegram:master` daily and can also be started with **Actions → Upstream merge and stable release → Run workflow**. When upstream has new commits, it merges them into `easygram-dev`, increments `APP_VERSION_CODE` past both the branch value and existing stable release tags, builds the signed `afatRelease` APK, pushes the merge/version commit, and publishes one stable GitHub Release. A merge conflict or build/signature error stops the run before publishing. The release uses the tag form expected by Easygram's OTA updater.

Before enabling it, add these repository Actions secrets under **Settings → Secrets and variables → Actions**:

- `EASYGRAM_RELEASE_KEYSTORE_BASE64`: base64 of the same stable release keystore used for existing installs.
- `EASYGRAM_RELEASE_STORE_PASSWORD`
- `EASYGRAM_RELEASE_KEY_ALIAS`
- `EASYGRAM_RELEASE_KEY_PASSWORD`
- `TELEGRAM_API_ID`
- `TELEGRAM_API_HASH`

Generate the first value locally with `base64 < TMessagesProj/config/your-stable.keystore` (use the actual keystore configured by `EASYGRAM_RELEASE_KEYSTORE` in local.properties). Do not commit keystores or credentials. The workflow intentionally fails before merge if a required secret is missing. Verify Actions has permission to write repository contents and releases, then run it manually once and review the workflow log and generated APK/release.
