# Optional CDN updater

All implementation lives in `app/src/main/java/org/bayton/gmc/update/`. There are no new dependencies, account-client hooks, workers or services.

## Behaviour

- Settings > Updates > Check for updates opens the installed version, release notes, manual check/download/install controls and an automatic-check toggle. The full-width button sits below notification settings.
- Automatic checks run on app open, at most once per 24 hours, including failed attempts. They do not run daily while the app is closed. Manual checks bypass the interval.
- New releases produce one notification per version, silent by default with no vibration. Android notification permission and channel settings are respected. Downloads never start automatically.
- Checks use a dedicated HTTPS client with no account credentials or persistent response cache. CDN requests necessarily disclose ordinary connection metadata, such as IP address, to the CDN operator. No account identifiers are sent.
- Downloads are private cache files, bounded to 200 MiB. Manifest responses are bounded to 64 KiB. Redirects are rejected; serve the URLs directly from the approved CDN path.
- SHA-256, package ID, version code/name and current signer certificates are checked before installation. The hash and identity are checked again at handoff. Signing-key rotation is not supported by this first implementation. Android also validates the APK during installation.
- Android owns installation and asks for permission and confirmation. After granting permission to install from this source, returning automatically revalidates the APK and opens Android's install confirmation. Denial leaves the download ready for manual retry. Each permission round trip can trigger at most one handoff. Cancelling the installer leaves the download available to retry.
- Partial files are deleted on failure/cancellation. Completed cache files may survive installer handoff; files older than one day are removed on the next download. Android can evict cache files at any time, in which case download again.
- Rotation preserves screen state through the ViewModel. Process death requires a fresh check/download. No download resume is attempted.

## Publishing

The manifest is `https://cdn.bayton.org/download/gmc/latest.json` and contains `versionCode`, `versionName`, `apkUrl`, `sha256`, and optional `releaseNotes`.

1. Increase the app's numeric version code and update its version name.
2. Build using the established release signing certificate. Never distribute a development build containing local-only credentials.
3. Upload the versioned APK under `https://cdn.bayton.org/download/gmc/`.
4. Calculate `shasum -a 256 path/to.apk` and put the complete 64-character digest in the manifest.
5. Publish the manifest last with short cache freshness. Its version fields must match the APK metadata exactly.

## Removing the updater

1. Delete the `update/` source and test packages.
2. Remove `UpdateSettingsEntry(ctx)` and its Updates heading from `NotificationSettingsScreen.kt`.
3. Remove the `UpdateChecks.onAppOpen(this)` call and its otherwise-empty `onStart` override from `MainActivity.kt`.
4. Remove the updater's `REQUEST_INSTALL_PACKAGES` permission, activity and FileProvider blocks from `AndroidManifest.xml`.
5. Delete `res/xml/update_file_paths.xml` and this document, and remove the README link to this document.

No dependency, navigation graph, login, database or background-work migration is required. If distributing through a store later, review that store's updater/install-permission rules before retaining this feature.
