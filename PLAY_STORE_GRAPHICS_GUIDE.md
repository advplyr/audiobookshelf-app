# TomeSonic Play Store Graphics Guide

## ✅ **Feature Graphic Created**

Your Play Store feature graphic has been generated and is ready for upload:

**File:** `tomesonic-feature-graphic.png`
**Dimensions:** 1024 x 500 pixels
**Format:** PNG
**Location:** Root directory of your project

## 🎨 **What's Included in the Feature Graphic**

### Visual Elements:
- **Gradient Background:** Purple to blue gradient matching Material You theming
- **TomeSonic Branding:** Large, prominent app name with shadow effects
- **Tagline:** "Your AudiobookShelf companion"
- **Key Features Listed:**
  - 📱 Stream & Download
  - 🚗 Android Auto Ready
  - 📺 ChromeCast Support
  - 📊 Progress Sync
  - 🎨 Material You Design
  - 📚 Library Management

### Visual Design:
- **Phone Mockup:** Shows the app icon and play button
- **Material You Badge:** Highlights the design system
- **Professional Layout:** Clean, modern design that stands out
- **Floating Icons:** Subtle visual elements for interest

## 📱 **Additional Graphics You'll Need**

### **App Icon (Required)**
- **Size:** 512 x 512 pixels
- **Format:** PNG (no transparency)
- **Note:** You already have `play_store_512.png` in your android folder!

### **Screenshots (Required - 2-8 images)**
Create screenshots showing:

1. **Home/Library Screen**
   - Size: Phone screenshots (various Android screen sizes)
   - Show: Book library with Material You theming

2. **Now Playing Screen**
   - Show: Playback controls, progress, chapter navigation

3. **Android Auto Interface**
   - Show: Car mode interface (if possible to capture)

4. **Statistics Screen**
   - Show: Reading progress, listening time, achievements

5. **ChromeCast Interface**
   - Show: Casting controls and device selection

6. **Download Management**
   - Show: Downloaded books, storage management

7. **Settings Screen**
   - Show: Theme options, playback settings

### **Screenshot Specifications:**
- **Minimum:** 320px on short side
- **Maximum:** 3840px on long side
- **Aspect Ratio:** Between 16:9 and 19.5:9
- **Format:** JPEG or 24-bit PNG (no alpha)

## 🛠 **How to Create Screenshots**

### **Method 1: Android Studio Emulator**
```bash
# Start emulator and take screenshots
npx cap run android
# Use emulator's screenshot tool
```

### **Method 2: Physical Device**
```bash
# Enable Developer Options and USB Debugging
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png
```

### **Method 3: Screen Recording**
- Record app usage and extract frames
- Use OBS Studio or similar tools

## 📋 **Upload Checklist**

### **Required Graphics:**
- ✅ Feature Graphic (1024x500) - **COMPLETED**
- ✅ App Icon (512x512) - **Available as play_store_512.png**
- ⏳ Screenshots (2-8 images) - **TODO**

### **Optional Graphics:**
- **Promotional Video:** 30 seconds to 2 minutes
- **TV Banner:** 1280x720 (for Android TV)

## 🎯 **Next Steps**

1. **Use the Generated Feature Graphic:**
   ```
   File: tomesonic-feature-graphic.png
   Ready to upload to Google Play Console
   ```

2. **Verify App Icon:**
   ```
   File: android/play_store_512.png
   Should be your final app icon design
   ```

3. **Create Screenshots:**
   - Build and run your app
   - Navigate to key screens
   - Take high-quality screenshots
   - Ensure they show your best features

4. **Upload to Play Console:**
   - Go to Store listing → Graphics
   - Upload feature graphic
   - Upload app icon
   - Add screenshots with descriptions

## 💡 **Pro Tips**

### **For Screenshots:**
- Use different device orientations
- Show diverse content (different books/authors)
- Highlight unique features (Android Auto, ChromeCast)
- Use device frames for professional look
- Add captions explaining key features

### **For Feature Graphic:**
- The generated graphic is optimized for visibility
- Emphasizes key differentiators (Material You, AudiobookShelf)
- Uses professional gradient and typography
- Includes visual phone mockup for context

### **Testing:**
- Preview how graphics look on different screen sizes
- Test on actual Play Store (internal testing first)
- Get feedback from beta testers
- Iterate based on user response

Your feature graphic is now ready to help TomeSonic stand out in the Play Store! 🚀