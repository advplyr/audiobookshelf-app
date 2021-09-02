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
    },
    parseSemver(ver) {
      if (!ver) return null
      var groups = ver.match(/^v((([0-9]+)\.([0-9]+)\.([0-9]+)(?:-([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?)(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?)$/)
      if (groups && groups.length > 6) {
        var total = Number(groups[3]) * 100 + Number(groups[4]) * 10 + Number(groups[5])
        if (isNaN(total)) {
          console.warn('Invalid version total', groups[3], groups[4], groups[5])
          return null
        }
        return {
          total,
          version: groups[2],
          major: Number(groups[3]),
          minor: Number(groups[4]),
          patch: Number(groups[5]),
          preRelease: groups[6] || null
        }
      } else {
        console.warn('Invalid semver string', ver)
      }
      return null
    },
    checkForUpdate() {
      if (!this.$config.version) {
        return
      }
      var currVerObj = this.parseSemver(this.$config.version)
      if (!currVerObj) {
        console.error('Invalid version', this.$config.version)
        return
      }
      console.log('Check for update, your version:', currVerObj.version)
      this.$store.commit('setCurrentVersion', currVerObj)

      var largestVer = null
      this.$axios.$get(`https://api.github.com/repos/advplyr/audiobookshelf-app/tags`).then((tags) => {
        if (tags && tags.length) {
          tags.forEach((tag) => {
            var verObj = this.parseSemver(tag.name)
            if (verObj) {
              if (!largestVer || largestVer.total < verObj.total) {
                largestVer = verObj
              }
            }
          })
        }
      })
      if (!largestVer) {
        console.error('No valid version tags to compare with')
        return
      }
      this.$store.commit('setLatestVersion', largestVer)
      if (largestVer.total > currVerObj.total) {
        console.log('Has Update!', largestVer.version)
        this.$store.commit('setHasUpdate', true)
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

    // var checkForUpdateFlag = localStorage.getItem('checkForUpdate')
    // if (!checkForUpdateFlag || checkForUpdateFlag !== '1') {
    //   this.checkForUpdate()
    // }
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