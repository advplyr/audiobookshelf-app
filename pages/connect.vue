<template>
  <div class="w-full h-full">
    <div class="relative flex items-center justify-center min-h-screen sm:pt-0">
      <nuxt-link to="/" class="absolute top-2 left-2 z-20">
        <span class="material-icons text-4xl">arrow_back</span>
      </nuxt-link>
      <div class="absolute top-0 left-0 w-full p-6 flex items-center flex-col justify-center z-0 short:hidden">
        <img src="/Logo.png" class="h-20 w-20 mb-2" />
        <h1 class="text-2xl font-book">AudioBookshelf</h1>
      </div>
      <p class="hidden absolute short:block top-1.5 left-12 p-2 font-book text-xl">AudioBookshelf</p>

      <div class="max-w-sm mx-auto sm:px-6 lg:px-8 z-10">
        <div v-show="loggedIn" class="mt-8 bg-primary overflow-hidden shadow rounded-lg p-6 text-center">
          <p class="text-success text-xl mb-2">Login Success!</p>
          <p>Connecting socket..</p>
        </div>
        <div v-show="!loggedIn" class="mt-8 bg-primary overflow-hidden shadow rounded-lg p-6">
          <h2 class="text-xl leading-7 mb-4">Enter an <span class="font-book font-normal">AudioBookshelf</span><br />server address:</h2>
          <form v-show="!showAuth" @submit.prevent="submit" novalidate>
            <ui-text-input v-model="serverUrl" :disabled="processing || !networkConnected" placeholder="http://55.55.55.55:13378" type="url" class="w-60 sm:w-72 h-10" />
            <ui-btn :disabled="processing || !networkConnected" type="submit" :padding-x="3" class="h-10">{{ networkConnected ? 'Submit' : 'No Internet' }}</ui-btn>
          </form>
          <template v-if="showAuth">
            <div class="flex items-center">
              <p class="">{{ serverUrl }}</p>
              <div class="flex-grow" />
              <span class="material-icons" style="font-size: 1.1rem" @click="editServerUrl">edit</span>
            </div>
            <div class="w-full h-px bg-gray-200 my-2" />
            <form @submit.prevent="submitAuth" class="pt-3">
              <ui-text-input v-model="username" :disabled="processing" placeholder="username" class="w-full my-1 text-lg" />
              <ui-text-input v-model="password" type="password" :disabled="processing" placeholder="password" class="w-full my-1 text-lg" />

              <ui-btn :disabled="processing || !networkConnected" type="submit" class="mt-1 h-10">{{ networkConnected ? 'Submit' : 'No Internet' }}</ui-btn>
            </form>
          </template>

          <div v-show="error" class="w-full rounded-lg bg-red-600 bg-opacity-10 border border-error border-opacity-50 py-3 px-2 flex items-center mt-4">
            <span class="material-icons mr-2 text-error" style="font-size: 1.1rem">warning</span>
            <p class="text-error">{{ error }}</p>
          </div>
        </div>
      </div>
    </div>
    <div :class="processing ? 'opacity-100' : 'opacity-0 pointer-events-none'" class="fixed w-full h-full top-0 left-0 bg-black bg-opacity-75 flex items-center justify-center z-30 transition-opacity duration-500">
      <div>
        <div class="absolute top-0 left-0 w-full p-6 flex items-center flex-col justify-center z-0 short:hidden">
          <img src="/Logo.png" class="h-20 w-20 mb-2" />
        </div>
        <svg class="animate-spin w-16 h-16" viewBox="0 0 24 24">
          <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
        </svg>
      </div>
    </div>
    <div class="flex items-center justify-center pt-4 fixed bottom-4 left-0 right-0">
      <a href="https://github.com/advplyr/audiobookshelf-app" target="_blank" class="text-sm pr-2">Follow the project on Github</a>
      <a href="https://github.com/advplyr/audiobookshelf-app" target="_blank"
        ><svg class="w-8 h-8 text-gray-100" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" aria-hidden="true" role="img" width="32" height="32" preserveAspectRatio="xMidYMid meet" viewBox="0 0 24 24">
          <path
            d="M12 2.247a10 10 0 0 0-3.162 19.487c.5.088.687-.212.687-.475c0-.237-.012-1.025-.012-1.862c-2.513.462-3.163-.613-3.363-1.175a3.636 3.636 0 0 0-1.025-1.413c-.35-.187-.85-.65-.013-.662a2.001 2.001 0 0 1 1.538 1.025a2.137 2.137 0 0 0 2.912.825a2.104 2.104 0 0 1 .638-1.338c-2.225-.25-4.55-1.112-4.55-4.937a3.892 3.892 0 0 1 1.025-2.688a3.594 3.594 0 0 1 .1-2.65s.837-.262 2.75 1.025a9.427 9.427 0 0 1 5 0c1.912-1.3 2.75-1.025 2.75-1.025a3.593 3.593 0 0 1 .1 2.65a3.869 3.869 0 0 1 1.025 2.688c0 3.837-2.338 4.687-4.563 4.937a2.368 2.368 0 0 1 .675 1.85c0 1.338-.012 2.413-.012 2.75c0 .263.187.575.687.475A10.005 10.005 0 0 0 12 2.247z"
            fill="currentColor"
          /></svg
      ></a>
    </div>
  </div>
</template>

<script>
export default {
  layout: 'blank',
  data() {
    return {
      serverUrl: null,
      processing: false,
      showAuth: false,
      username: null,
      password: null,
      error: null,
      loggedIn: false
    }
  },
  computed: {
    networkConnected() {
      return this.$store.state.networkConnected
    }
  },
  methods: {
    async submit() {
      if (!this.networkConnected) {
        return
      }
      if (!this.serverUrl.startsWith('http')) {
        this.serverUrl = 'http://' + this.serverUrl
      }
      this.processing = true
      this.error = null
      var success = await this.$server.check(this.serverUrl)
      this.processing = false
      if (!success) {
        console.error('Server invalid')
        this.error = 'Invalid Server'
      } else {
        this.showAuth = true
      }
    },
    async submitAuth() {
      if (!this.networkConnected) {
        return
      }
      if (!this.username) {
        this.error = 'Invalid username'
        return
      }
      this.error = null

      this.processing = true
      var response = await this.$server.login(this.serverUrl, this.username, this.password)
      this.processing = false
      if (response.error) {
        console.error('Login failed')
        this.error = response.error
      } else {
        console.log('Login Success!')
        this.loggedIn = true
      }
    },
    editServerUrl() {
      this.error = null
      this.showAuth = false
    },
    redirect() {
      if (this.$route.query && this.$route.query.redirect) {
        this.$router.replace(this.$route.query.redirect)
      } else {
        this.$router.replace('/bookshelf')
      }
    },
    socketConnected() {
      console.log('Socket connected')
      this.redirect()
    },
    async init() {
      if (!this.$server) {
        console.error('Invalid server not initialized')
        return
      }
      if (this.$server.connected) {
        console.warn('Server already connected')
        return this.redirect()
      }
      this.$server.on('connected', this.socketConnected)

      var localServerUrl = await this.$localStore.getServerUrl()
      var localUserToken = await this.$localStore.getToken()

      if (!this.networkConnected) return

      if (localServerUrl) {
        this.serverUrl = localServerUrl
        if (localUserToken) {
          this.processing = true
          var success = await this.$server.connect(localServerUrl, localUserToken)

          if (!success && !this.$server.url) {
            this.processing = false
            this.serverUrl = null
            this.showAuth = false
          } else if (!success) {
            console.log('Server connect success')
            this.processing = false
          }
          this.showAuth = true
        } else {
          this.submit()
        }
      }
    }
  },
  mounted() {
    this.init()
  },
  beforeDestroy() {
    if (!this.$server) {
      console.error('Connected beforeDestroy: No Server')
      return
    }
    this.$server.off('connected', this.socketConnected)
  }
}
</script>