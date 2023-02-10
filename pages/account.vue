<template>
  <div class="w-full h-full p-4">
    <ui-text-input-with-label :value="serverConnConfigName" label="Connection Config Name" disabled class="my-2" />

    <ui-text-input-with-label :value="username" label="Username" disabled class="my-2" />

    <ui-btn color="primary flex items-center justify-between gap-2 ml-auto text-base mt-8" @click="logout">Logout<span class="material-icons" style="font-size: 1.1rem">logout</span></ui-btn>

    <div class="flex justify-end items-center m-4 gap-3 right-0 bottom-0 absolute">
      <p class="text-sm font-book text-yellow-400 text-right">Report bugs, request features, provide feedback, and contribute on <a class="underline" href="https://github.com/advplyr/audiobookshelf-app" target="_blank">GitHub</a></p>
      <a href="https://github.com/advplyr/audiobookshelf-app" target="_blank" class="text-white hover:text-gray-200 hover:scale-150 hover:rotate-6 transform duration-500">
        <svg xmlns="http://www.w3.org/2000/svg" fill="currentColor" width="24" height="24" viewBox="0 0 24 24">
          <path
            d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"
          />
        </svg>
      </a>
    </div>
  </div>
</template>

<script>
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
    serverConnectionConfig() {
      return this.$store.state.user.serverConnectionConfig || {}
    },
    serverConnConfigName() {
      return this.serverConnectionConfig.name
    },
    serverAddress() {
      return this.serverConnectionConfig.address
    }
  },
  methods: {
    async logout() {
      await this.$hapticsImpact()
      if (this.user) {
        await this.$axios.$post('/logout').catch((error) => {
          console.error(error)
        })
      }

      this.$socket.logout()
      await this.$db.logout()
      this.$localStore.removeLastLibraryId()
      this.$store.commit('user/logout')
      this.$router.push('/connect')
    }
  },
  mounted() {}
}
</script>
