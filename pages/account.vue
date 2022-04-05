<template>
  <div class="w-full h-full p-4">
    <div class="w-full max-w-xs mx-auto">
      <ui-text-input-with-label :value="serverUrl" label="Server Url" disabled class="my-4" />

      <ui-text-input-with-label :value="username" label="Username" disabled class="my-4" />

      <ui-btn color="primary flex items-center justify-between text-base w-full mt-8" @click="logout">Logout<span class="material-icons" style="font-size: 1.1rem">logout</span></ui-btn>
    </div>

    <div class="flex items-center pt-8">
      <div class="flex-grow" />
      <p class="pr-2 text-sm font-book text-yellow-400">Report bugs, request features, provide feedback, and contribute on <a class="underline" href="https://github.com/advplyr/audiobookshelf-app" target="_blank">github</a>.</p>
      <a href="https://github.com/advplyr/audiobookshelf-app" target="_blank" class="text-white hover:text-gray-200 hover:scale-150 hover:rotate-6 transform duration-500">
        <svg xmlns="http://www.w3.org/2000/svg" fill="currentColor" width="24" height="24" viewBox="0 0 24 24">
          <path
            d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"
          />
        </svg>
      </a>
    </div>

    <p class="font-mono pt-1 pb-4">{{ $config.version }}</p>

    <ui-btn v-if="isUpdateAvailable" class="w-full my-4" color="success" @click="clickUpdate">Update is available</ui-btn>

    <ui-btn v-if="!isUpdateAvailable || immediateUpdateAllowed" class="w-full my-4" color="primary" @click="openAppStore">Open app store</ui-btn>

    <!-- Used for testing API -->
    <ui-btn @click="testCall">Test Call</ui-btn>

    <p class="text-xs text-gray-400">UA: {{ updateAvailability }} | Avail: {{ availableVersion }} | Curr: {{ currentVersion }} | ImmedAllowed: {{ immediateUpdateAllowed }}</p>
  </div>
</template>

<script>
import { AppUpdate } from '@robingenz/capacitor-app-update'
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  asyncData({ redirect, store }) {
    if (!store.state.socketConnected) {
      return redirect('/connect')
    }
    return {}
  },
  data() {
    return {}
  },
  computed: {
    username() {
      if (!this.user) return ''
      return this.user.username
    },
    user() {
      return this.$store.state.user.user
    },
    serverUrl() {
      return this.$server.url
    },
    appUpdateInfo() {
      return this.$store.state.appUpdateInfo
    },
    availableVersion() {
      return this.appUpdateInfo ? this.appUpdateInfo.availableVersion : null
    },
    currentVersion() {
      return this.appUpdateInfo ? this.appUpdateInfo.currentVersion : null
    },
    immediateUpdateAllowed() {
      return this.appUpdateInfo ? !!this.appUpdateInfo.immediateUpdateAllowed : false
    },
    updateAvailability() {
      return this.appUpdateInfo ? this.appUpdateInfo.updateAvailability : null
    },
    isUpdateAvailable() {
      return this.updateAvailability === 2
    }
  },
  methods: {
    testCall() {
      // Used for testing API
      console.log('Making Test call')
      var libraryId = this.$store.state.libraries.currentLibraryId
      AbsAudioPlayer.getLibraryItems({ libraryId }).then((res) => {
        console.log('TEST CALL response', JSON.stringify(res))
      })
    },
    async logout() {
      await this.$axios.$post('/logout').catch((error) => {
        console.error(error)
      })
      this.$server.logout()
      this.$router.push('/connect')
    },
    openAppStore() {
      AppUpdate.openAppStore()
    },
    async clickUpdate() {
      if (this.immediateUpdateAllowed) {
        AppUpdate.performImmediateUpdate()
      } else {
        AppUpdate.openAppStore()
      }
    }
  },
  mounted() {}
}
</script>