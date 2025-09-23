# Top-level justfile for repo convenience - delegates Android tasks to android/justfile
import 'android/justfile'
# Usage examples:
#   just android-info
#   just android-build
#   just android-install-debug
#   just android-dhu
#   just android-run
#   just android-logcat
#   just build-nuxt        # build Nuxt.js and sync Capacitor
#   just run-debug         # build Nuxt, sync, build and install debug APK
#   just run               # full workflow: build Nuxt, sync, build/install APK, start Android Auto
#   just build             # build the android app (assembleDebug)
#   just install-debug     # build and install debug APK onto connected device

ANDROID_DIR := "android"

android-info:
	@sh -lc 'cd "${ANDROID_DIR}" && just info'

android-build:
	@sh -lc 'cd "${ANDROID_DIR}" && just build'

android-assemble-debug:
	@sh -lc 'cd "${ANDROID_DIR}" && just assemble-debug'

android-install-debug:
	@sh -lc 'cd "${ANDROID_DIR}" && just install-debug'

android-uninstall-debug:
	@sh -lc 'cd "${ANDROID_DIR}" && just uninstall-debug'

android-adb-forward:
	@sh -lc 'cd "${ANDROID_DIR}" && just adb-forward'

android-stop-forward:
	@sh -lc 'cd "${ANDROID_DIR}" && just stop-forward'

android-dhu:
	@sh -lc 'cd "${ANDROID_DIR}" && just dhu'

android-run:
	@sh -lc 'cd "${ANDROID_DIR}" && just run'

android-run-with-check:
	@sh -lc 'cd "${ANDROID_DIR}" && just run-with-check'

android-adb-reverse:
	@sh -lc 'cd "${ANDROID_DIR}" && just adb-reverse'

android-adb-reverse-remove:
	@sh -lc 'cd "${ANDROID_DIR}" && just adb-reverse-remove'

android-logcat:
	@sh -lc 'cd "${ANDROID_DIR}" && just logcat'

android-logcat-full:
	@sh -lc 'cd "${ANDROID_DIR}" && just logcat-full'

android-start-web:
	@sh -lc 'cd "${ANDROID_DIR}" && just start-web'

# Convenience aggregate
android-all:
	@sh -lc 'cd "${ANDROID_DIR}" && just info && just build'

