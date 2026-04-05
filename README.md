# APK URL Extractor

Android app to monitor one selected app's outbound traffic via `VpnService` and record potential streaming URLs. **Features built-in update checking from GitHub releases.**

## What it captures

- Plain HTTP request URLs (including `Host + path` reconstruction)
- Direct URLs in payload containing `.m3u8`, `.mpd`, `.mp4`, or `.m4s`
- TLS SNI host hints (saved as `https://host`) when host looks stream/media-related

## Important limitations

- HTTPS request paths are encrypted, so full URL extraction is often not possible.
- This implementation reads traffic from a VPN tunnel for the selected app but does **not** forward packets back to the internet.
- Because of that, network behavior in the selected app may be degraded while capture is running.

## Usage

1. Open the app.
2. Tap **Pick app to monitor**.
3. Tap **Start capture** and accept VPN permission.
4. Tap **Open selected app** and reproduce playback behavior.
5. Return to this app to see captured records.
6. Tap **Stop capture** when done.
7. (Optional) Tap **Check for updates** to download the latest version from GitHub.

## Build

```powershell
Set-Location "C:\Users\seang\AndroidStudioProjects\apkurl"
.\gradlew.bat :app:assembleDebug
```

## Build Release APK

```powershell
Set-Location "C:\Users\seang\AndroidStudioProjects\apkurl"
.\gradlew.bat :app:assembleRelease
```

## Test

```powershell
Set-Location "C:\Users\seang\AndroidStudioProjects\apkurl"
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

## Release & Update Checking

This project uses GitHub Actions to automatically build and release APKs when you push a git tag. The app also includes built-in update checking that fetches the latest release from GitHub API.

Update checks are configured through `BuildConfig` in `app/build.gradle.kts`:

- `GITHUB_OWNER` (default: `seang`)
- `GITHUB_REPO` (default: `apkurl`)

Set these to your real GitHub owner/repo before publishing.

### How to release:

1. Ensure all changes are committed.
2. Create and push a git tag.

```powershell
git tag v1.1.0
git push origin v1.1.0
```

3. GitHub Actions workflow automatically builds and creates a release with the APK.
4. Users can tap **Check for updates** in the app to download the new version.

## Safety and legal note

Use this only for apps and traffic you are authorized to inspect. Respect local laws, terms of service, and user privacy.

