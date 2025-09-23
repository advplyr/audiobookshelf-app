# Play Store Deployment Setup

This document explains how to set up automated Play Store publishing using GitHub Actions.

## Overview

The project now includes a GitHub workflow (`deploy-playstore.yml`) that automatically publishes releases to the Google Play Store when you:
- Push a version tag (e.g., `v1.0.0`)
- Manually trigger the workflow with a specific release track

## Prerequisites

### 1. Google Play Console Setup

1. **Create a Google Cloud Project** (if not already done)
2. **Enable Google Play Developer API**
   - Go to Google Cloud Console → APIs & Services → Library
   - Search for "Google Play Developer API" and enable it

3. **Create a Service Account**
   - Go to Google Cloud Console → IAM & Admin → Service Accounts
   - Click "Create Service Account"
   - Name: `play-store-publisher`
   - Grant roles: `Service Account User`
   - Download the JSON key file

4. **Link Service Account to Play Console**
   - Go to Google Play Console → Setup → API access
   - Link the Google Cloud project
   - Grant permissions to the service account:
     - View app information and download bulk reports
     - Manage store presence
     - Manage production releases
     - Manage testing track releases

### 2. App Signing Setup

1. **Generate Upload Keystore**
   ```bash
   keytool -genkey -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```

2. **Convert Keystore to Base64**
   ```bash
   base64 -i upload-keystore.jks | tr -d '\n' | pbcopy
   ```

3. **Store the Base64 string** - you'll need this for GitHub secrets

## GitHub Secrets Configuration

Go to your GitHub repository → Settings → Secrets and variables → Actions

Add the following secrets:

### Required Secrets

| Secret Name | Description | Example |
|-------------|-------------|---------|
| `UPLOAD_KEYSTORE_BASE64` | Base64 encoded upload keystore file | `MIIEvgIBADANBg...` |
| `UPLOAD_STORE_PASSWORD` | Keystore password | `your_keystore_password` |
| `UPLOAD_KEY_ALIAS` | Key alias used when generating keystore | `upload` |
| `UPLOAD_KEY_PASSWORD` | Key password (often same as store password) | `your_key_password` |
| `GOOGLE_PLAY_SERVICE_ACCOUNT` | Complete JSON content of service account key | `{"type": "service_account",...}` |

### Setting Up Secrets

1. **UPLOAD_KEYSTORE_BASE64**
   - Paste the base64 string from step 2 above

2. **UPLOAD_STORE_PASSWORD**
   - The password you used when creating the keystore

3. **UPLOAD_KEY_ALIAS**
   - The alias you used (usually "upload")

4. **UPLOAD_KEY_PASSWORD**
   - The key password (often same as store password)

5. **GOOGLE_PLAY_SERVICE_ACCOUNT**
   - Copy the entire contents of the service account JSON file

## Usage

### Automatic Deployment (Tags)

Push a version tag to automatically deploy to the internal testing track:

```bash
git tag v1.0.0
git push origin v1.0.0
```

### Manual Deployment

1. Go to GitHub → Actions → "Deploy to Play Store"
2. Click "Run workflow"
3. Select the release track:
   - **internal**: Internal testing (up to 100 testers)
   - **alpha**: Closed testing (specific testers)
   - **beta**: Open testing (anyone can join)
   - **production**: Public release

## Release Tracks

- **Internal**: Fast uploads, for quick testing with internal team
- **Alpha**: Closed testing with specific testers
- **Beta**: Open testing, users can opt-in
- **Production**: Public release on Play Store

## App Bundle vs APK

The workflow builds an **App Bundle (AAB)** which is required by Google Play Store since August 2021. App Bundles are more efficient than APKs as Google Play generates optimized APKs for each device configuration.

## Troubleshooting

### Common Issues

1. **"Package not found"**
   - Ensure the app has been manually uploaded to Play Console at least once
   - Verify the `packageName` matches exactly (`com.tomesonic.app`)

2. **"Service account permissions"**
   - Check that the service account has proper permissions in Play Console
   - Verify the service account JSON is complete and valid

3. **"Keystore issues"**
   - Ensure base64 encoding is correct (no line breaks)
   - Verify keystore passwords match

4. **"Version code conflicts"**
   - Each release must have a unique, incrementing `versionCode`
   - Update `versionCode` in `android/app/build.gradle` before releasing

### Version Management

Before creating a release:

1. Update `versionCode` in `android/app/build.gradle`
2. Update `versionName` in `android/app/build.gradle`
3. Commit changes
4. Create and push tag

Example:
```gradle
versionCode 116
versionName "0.12.1"
```

## Security Notes

- Never commit keystore files to the repository
- Use GitHub secrets for all sensitive information
- Regularly rotate service account keys
- Limit service account permissions to minimum required

## Testing

Before setting up production releases:

1. Test with **internal** track first
2. Verify the app installs and works correctly
3. Test the release process with a few versions
4. Only then proceed to alpha/beta/production tracks