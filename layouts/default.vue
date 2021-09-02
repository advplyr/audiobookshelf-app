<template>
  <div class="w-full min-h-screen h-full bg-bg text-white">
    <app-appbar />
    <div id="content" class="overflow-hidden" :class="streaming ? 'streaming' : ''">
      <Nuxt />
    </div>
    <app-stream-container ref="streamContainer" />
  </div>
</template>

<script>
export default {
  middleware: 'authenticated',
  data() {
    return {}
  },
  // watch: {
  //   routeName(newVal, oldVal) {
  //     if (newVal === 'connect' && this.$server.connected) {
  //       this.$router.replace('/')
  //     }
  //   }
  // },
  computed: {
    streaming() {
      return this.$store.state.streamAudiobook
    },
    routeName() {
      return this.$route.name
    }
  },
  methods: {
    connected(isConnected) {
      if (this.$route.name === 'connect') {
        if (isConnected) {
          this.$router.push('/')
        }
      } else {
        if (!isConnected) {
          this.$router.push('/connect')
        }
      }
    },
    initialStream(stream) {
      if (this.$refs.streamContainer && this.$refs.streamContainer.audioPlayerReady) {
        this.$refs.streamContainer.streamOpen(stream)
      }
    }
  },
  mounted() {
    if (!this.$server) return console.error('No Server')

    this.$server.on('connected', this.connected)
    this.$server.on('initialStream', this.initialStream)

    if (!this.$server.connected) {
      this.$router.push('/connect')
    }
  }
}
</script>

<style>
#content {
  height: calc(100vh - 64px);
}
#content.streaming {
  height: calc(100vh - 204px);
}
</style>