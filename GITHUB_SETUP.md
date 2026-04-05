# GitHub Setup Instructions for APK URL Extractor

## Step 1: Create a new GitHub repository

1. Go to [github.com](https://github.com) and log in.
2. Click the **+** icon (top right) → **New repository**.
3. Set repository name to: **apkurl**
4. Set description: `Android app to extract streaming URLs from selected apps`
5. Choose **Public** (so update check can fetch releases).
6. **Do NOT** initialize with README (we have one already).
7. Click **Create repository**.

## Step 2: Initialize git and push code

From PowerShell in `C:\Users\seang\AndroidStudioProjects\apkurl`:

```powershell
# Initialize git repo locally
git init

# Add all files
git add .

# Initial commit
git commit -m "Initial commit: APK URL Extractor app with update checking"

# Add remote (replace USERNAME with your GitHub username)
git remote add origin https://github.com/USERNAME/apkurl.git

# Push to GitHub
git branch -M main
git push -u origin main
```

## Step 3: Create a release

Once code is pushed:

```powershell
# Create a version tag
git tag v1.0.0
git push origin v1.0.0
```

The GitHub Actions workflow (`.github/workflows/release.yml`) will automatically:
1. Build the APK
2. Create a release with the APK attached

## Step 4: Verify in the app

Once the first release is created, the app's **Check for updates** button will work and point users to your GitHub releases page.

## Notes

- Update checking looks for releases at: `https://api.github.com/repos/USERNAME/apkurl/releases/latest`
- Make sure your GitHub repository is **public** for the update API to be accessible.
- GitHub Actions is free for public repositories.

