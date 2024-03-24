# Audiobookshelf Mobile App

Audiobookshelf is a self-hosted audiobook and podcast server.

### Android (beta)
Get the Android app on the [Google Play Store](https://play.google.com/store/apps/details?id=com.audiobookshelf.app)

### iOS (early beta)
**Beta is currently full. Apple has a hard limit of 10k beta testers. Updates will be posted in Discord/Matrix.**

Using Test Flight: https://testflight.apple.com/join/wiic7QIW ***(beta is full)***

---

[Go to the main project repo github.com/advplyr/audiobookshelf](https://github.com/advplyr/audiobookshelf) or the project site [audiobookshelf.org](https://audiobookshelf.org)

Join us on [discord](https://discord.gg/pJsjuNCKRq) or [Matrix](https://matrix.to/#/#audiobookshelf:matrix.org)

**Requires an Audiobookshelf server to connect with**

<img alt="Screenshot" src="https://github.com/advplyr/audiobookshelf-app/raw/master/screenshots/DeviceDemoScreens.png" />


## Contributing

This application is built using [NuxtJS](https://nuxtjs.org/) and [Capacitor](https://capacitorjs.com/) in order to run on both iOS and Android on the same code base.

Information on helping with translations of the apps [here](https://www.audiobookshelf.org/faq#how-do-i-help-with-translations).

### Windows Environment Setup for Android

Required Software:

* [Git](https://git-scm.com/downloads)
* [Node.js](https://nodejs.org/en/) (version 20)
* Code editor of choice([VSCode](https://code.visualstudio.com/download), etc)
* [Android Studio](https://developer.android.com/studio)
* [Android SDK](https://developer.android.com/studio)

<details>
<summary>Install the required software with <a href=(https://docs.microsoft.com/en-us/windows/package-manager/winget/#production-recommended)>winget</a></summary>

<p>
Note: This requires a PowerShell prompt with winget installed.  You should be able to copy and paste the code block to install.  If you use an elevated PowerShell prompt, UAC will not pop up during the installs.

```PowerShell
winget install -e --id Git.Git; `
winget install -e --id Microsoft.VisualStudioCode; `
winget install -e --id  Google.AndroidStudio; `
winget install -e --id OpenJS.NodeJS --version 20.11.0;
```

![](/screenshots/dev_setup_windows_winget.png)

</p>
</details>
<br>

Your Windows environment should now be set up and ready to proceed!

### Mac Environment Setup for Android

Required Software:

* [Android Studio](https://developer.android.com/studio)
* [Node.js](https://nodejs.org/en/) (version 20)
* [Cocoapods](https://guides.cocoapods.org/using/getting-started.html#installation)
* [Android SDK](https://developer.android.com/studio)

<details>
<summary>Install the required software with <a href=(https://brew.sh/)>homebrew</a></summary>

<p>

```zsh
brew install android-studio node cocoapods
```

</p>
</details>

### Start working on the Android app

Clone or fork the project from terminal or powershell and `cd` into the project directory.

Install the required node packages:
```shell
npm install
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_android_npm_install.png)
</details>
<br>

Generate static web app:
```shell
npm run generate
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_android_npm_run.png)
</details>
<br>

Copy web app into native android/ios folders:
```shell
npx cap sync
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_android_cap_sync.png)
</details>
<br>

Open Android Studio:

```shell
npx cap open android
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_cap_android.png)
</details>
<br>

Start coding!

### Mac Environment Setup for iOS

Required Software:

* [Xcode](https://developer.apple.com/xcode/)
* [Node.js](https://nodejs.org/en/)
* [Cocoapods](https://guides.cocoapods.org/using/getting-started.html#installation)

### Start working on the iOS app

Clone or fork the project in the terminal and `cd` into the project directory.

Install the required node packages:
```shell
npm install
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_npm_install.png)
</details>
<br>

Generate static web app:
```shell
npm run generate
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_npm_generate.png)
</details>
<br>

Copy web app into native android/ios folders:
```shell
npx cap sync
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_cap_sync.png)
</details>
<br>

Open Xcode:

```shell
npx cap open ios
```

<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_ios_cap_open.png)
</details>
<br>

Start coding!
