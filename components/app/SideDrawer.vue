<template>
  <div class="fixed top-0 left-0 right-0 bottom-0 w-full h-full z-50 overflow-hidden pointer-events-none">
    <div class="absolute top-0 left-0 w-full h-full bg-black transition-opacity duration-200" :class="show ? 'bg-opacity-60 pointer-events-auto' : 'bg-opacity-0'" @click="clickBackground" />
    <div class="absolute top-0 right-0 w-64 h-full bg-primary transform transition-transform py-6 pointer-events-auto" :class="show ? '' : 'translate-x-64'" @click.stop>
      <div class="px-6 mb-4">
        <p v-if="socketConnected" class="text-base">
          Welcome, <strong>{{ username }}</strong>
        </p>
      </div>
      <div class="w-full overflow-y-auto">
        <template v-for="item in navItems">
          <nuxt-link :to="item.to" :key="item.text" class="w-full hover:bg-bg hover:bg-opacity-60 flex items-center py-3 px-6 text-gray-300">
            <span class="text-lg" :class="item.iconOutlined ? 'material-icons-outlined' : 'material-icons'">{{ item.icon }}</span>
            <p class="pl-4">{{ item.text }}</p>
          </nuxt-link>
        </template>
      </div>
      <div class="absolute bottom-0 left-0 w-full flex items-center py-6 px-6 text-gray-300">
        <p class="text-xs">{{ $config.version }}</p>
        <div class="flex-grow" />
        <div v-if="socketConnected" class="flex items-center" @click="logout">
          <p class="text-xs pr-2">Logout</p>
          <span class="material-icons text-sm">logout</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {}
  },
  watch: {
    $route: {
      handler() {
        this.show = false
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.showSideDrawer
      },
      set(val) {
        this.$store.commit('setShowSideDrawer', val)
      }
    },
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user ? this.user.username : ''
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    navItems() {
      var items = [
        {
          icon: 'home',
          text: 'Home',
          to: '/bookshelf'
        },
        {
          icon: 'person',
          text: 'Account',
          to: '/account'
        },
        {
          icon: 'folder',
          iconOutlined: true,
          text: 'Downloads',
          to: '/downloads'
        },
        {
          icon: 'settings',
          text: 'Settings',
          to: '/config'
        }
      ]
      if (!this.socketConnected) {
        items = [
          {
            icon: 'cloud_off',
            text: 'Connect to Server',
            to: '/connect'
          }
        ].concat(items)
      }
      return items
    }
  },
  methods: {
    clickBackground() {
      this.show = false
    },
    async logout() {
      await this.$axios.$post('/logout').catch((error) => {
        console.error(error)
      })
      this.$server.logout()
      this.$router.push('/connect')

      this.$store.commit('audiobooks/reset')
      this.$store.dispatch('audiobooks/useDownloaded')
    }
  },
  mounted() {},
  beforeDestroy() {
    this.show = false
  }
}
</script>