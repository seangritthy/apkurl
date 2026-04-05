# Quick Start: APK URL Extractor on GitHub

## ✅ What's Ready

- ✓ Full Android app with VPN capture, URL extraction, and logging
- ✓ Built-in update checker (fetches latest from GitHub API)
- ✓ GitHub Actions workflow for auto-release on tag push
- ✓ Local git repo initialized with initial commit
- ✓ Debug APK builds successfully

## 🚀 Next Steps

### 1. Create Repository on GitHub

Go to https://github.com/new and fill in:
- **Repository name**: `apkurl`
- **Description**: `Android app to extract streaming URLs from selected apps`
- **Public**: Yes (required for update checking)
- Click **Create repository**

You'll see a screen like:
```
…or push an existing repository from the command line

git remote add origin https://github.com/YOUR_USERNAME/apkurl.git
git branch -M main
git push -u origin main
```

### 2. Push Code to GitHub

From PowerShell in the project directory:

```powershell
# Add the GitHub remote (replace YOUR_USERNAME)
git remote add origin https://github.com/YOUR_USERNAME/apkurl.git

# Rename branch to main
git branch -M main

# Push code
git push -u origin main
```

Before release, confirm update-check target in `app/build.gradle.kts`:

- `GITHUB_OWNER` = your GitHub username/org
- `GITHUB_REPO` = `apkurl`

When prompted, enter your GitHub username and a Personal Access Token (not your password).

### 3. Create First Release

Once code is on GitHub:

```powershell
# Create version tag
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will automatically:
1. Build the release APK
2. Create a GitHub release with the APK attached
3. Make it available for your app's update checker

### 4. Test Update Checker

Once the first release is created:
1. Open the app
2. Tap **Check for updates**
3. It should find `v1.0.0` and offer to download

## 📱 App Features

- **Pick app**: Select which app to monitor
- **Start capture**: Begin VPN-based packet capture
- **See logs**: View captured URLs in real-time
- **Check for updates**: Automatically fetch latest version from GitHub

## 🔧 Making Updates

To push a new version:

```powershell
# Make code changes, then:
git add .
git commit -m "Your changes here"
git push origin main

# Create new release tag
git tag v1.1.0
git push origin v1.1.0
```

## ⚠️ Important Notes

- The app requires **Android 7.0+** (API 24+)
- Update checking requires **public** GitHub repo
- VPN permission required at first launch
- App works best with apps that stream `.m3u8`, `.mpd`, or `.mp4` files

## 📝 Troubleshooting

**"Update check failed"?**
- Ensure repo is public
- Check internet connectivity
- Verify GitHub hasn't rate-limited your IP

**"VPN permission denied"?**
- Try restarting the device
- Ensure you have admin permissions

**"No apps found"?**
- Not all installed apps are launchable; tap to see complete list

---

Enjoy extracting! 🎉


