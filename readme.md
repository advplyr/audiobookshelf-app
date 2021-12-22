# Audiobookshelf Mobile App

AudioBookshelf is a self-hosted audiobook server for managing and playing your audiobooks.

Get the Android app on the [Google Play Store](https://play.google.com/store/apps/details?id=com.audiobookshelf.app)

[Go to the main project repo github.com/advplyr/audiobookshelf](https://github.com/advplyr/audiobookshelf)

[audiobookshelf.org](https://audiobookshelf.org)

**Currently in Beta** - **Requires an Audiobookshelf server to connect with**

<img alt="Screenshot1" src="https://github.com/advplyr/audiobookshelf-app/raw/master/screenshots/BookshelfViews.png" />


## Contributing

### Windows Environment Setup

Required Software

* [Git](https://git-scm.com/downloads)
* [Node.js](https://nodejs.org/en/)
* Code editor of choice([VSCode](https://code.visualstudio.com/download), etc)
* [Android Studio](https://developer.android.com/studio)

<details>
<summary>Install the applications with <a href=(https://docs.microsoft.com/en-us/windows/package-manager/winget/#production-recommended)>winget</a></summary>

<p>
Note: This requires a PowerShell prompt with winget installed.  You should be able to copy and paste the code block to install.  If you use an elevated PowerShell prompt, UAC will not pop up during the installs.

```PowerShell
winget install -e --id Git.Git; `
winget install -e --id Microsoft.VisualStudioCode; `
winget install -e --id  Google.AndroidStudio; `
winget install -e --id OpenJS.NodeJS --version 16.12.0; #v17 has issues with openssl
```

![](/screenshots/dev_setup_windows_winget.png)

</p>
</details>
<br>

Your Windows environment should now be set up and ready to proceed!

### Mac Environment Setup


### Start working on the project


Clone or fork the project from cmd or powershell and `cd` into the project directory.

Install the required node packages: 
```
npm install
```
<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_npm_install.png)
</details>
<br>
Generate static web app: 
```
npm run generate
```
<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_npm_run.png)
</details>
<br>

Copy web app into native android/ios folders:
```
npx cap sync
```
<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_cap_sync.png)
</details>
<br>

Open Android Studio:
```
npx cap open android
```
<details>
<summary>Expand for screenshot</summary>

![](/screenshots/dev_setup_cap_android.png)
</details>

Start coding!