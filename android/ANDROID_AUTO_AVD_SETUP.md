# Android Auto Compatible AVD Setup

The Pixel 9a AVD with Google Play APIs isn't compatible with Android Auto. Here's how to create a proper Android Auto compatible AVD:

## Requirements for Android Auto AVD:
1. **System Image**: Use Google APIs (not Google Play) system image
2. **API Level**: API 28 (Android 9.0) or higher
3. **Device**: Use Automotive device profile OR regular phone with specific config
4. **RAM**: At least 2GB RAM
5. **Storage**: At least 2GB internal storage

## Step-by-Step Instructions:

### Option 1: Create Automotive AVD (Recommended)
1. Open Android Studio
2. Go to Tools > AVD Manager
3. Click "Create Virtual Device"
4. Select **"Automotive"** category
5. Choose **"Automotive (1024 x 768)"** device
6. Select system image:
   - **API 29 (Android 10.0) with Google APIs** (recommended)
   - OR **API 30+ with Google APIs**
7. Configure AVD:
   - Name: "Android_Auto_Test"
   - RAM: 2048 MB or higher
   - Internal Storage: 2048 MB or higher
   - Enable Hardware - Use Host GPU
8. Click "Finish"

### Option 2: Regular Phone with Android Auto Support
1. Open Android Studio
2. Go to Tools > AVD Manager
3. Click "Create Virtual Device"
4. Select **"Phone"** category
5. Choose **"Pixel 3"** or **"Pixel 4"** (these work better than newer Pixel models)
6. Select system image:
   - **API 29 (Android 10.0) with Google APIs** (NOT Google Play)
   - Ensure it says "Google APIs" not "Google Play"
7. Configure AVD:
   - Name: "Pixel_3_Android_Auto"
   - RAM: 2048 MB or higher
   - Internal Storage: 2048 MB or higher
   - Enable Hardware - Use Host GPU
8. Click "Finish"

## Important Notes:
- **Never use "Google Play" system images** for Android Auto testing
- **Use "Google APIs" system images** instead
- Pixel 9a and newer Pixel models have known compatibility issues with Android Auto emulation
- API 28+ is required for modern Android Auto features
- Make sure Google Play Services is updated in the AVD after creation

## After Creating the AVD:
1. Start the AVD
2. Open Google Play Store and update Google Play Services
3. Install Android Auto app from Play Store
4. Enable Developer Options in Android Auto app
5. Test your audiobookshelf app with Android Auto

## Testing Android Auto:
- Use the Android Auto Desktop Head Unit (DHU) for testing
- Download from: https://developer.android.com/training/cars/testing
- Connect via ADB: `adb forward tcp:5277 tcp:5277`
