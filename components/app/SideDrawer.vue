<template>
  <div class="fixed top-0 left-0 right-0 layout-wrapper w-full z-50 overflow-hidden pointer-events-none">
    <div class="absolute top-0 left-0 w-full h-full bg-black transition-opacity duration-200" :class="show ? 'bg-opacity-60 pointer-events-auto' : 'bg-opacity-0'" @click="clickBackground" />
    <div class="absolute top-0 right-0 w-64 h-full bg-primary transform transition-transform py-6 pointer-events-auto" :class="show ? '' : 'translate-x-64'" @click.stop>
      <div class="px-6 mb-4">
        <p v-if="user" class="text-base">
          Welcome,
          <strong>{{ username }}</strong>
        </p>
      </div>

      <div class="w-full overflow-y-auto">
        <template v-for="item in navItems">
          <nuxt-link :to="item.to" :key="item.text" class="w-full hover:bg-bg hover:bg-opacity-60 flex items-center py-3 px-6 text-gray-300" :class="currentRoutePath.startsWith(item.to) ? 'bg-bg bg-opacity-60' : ''">
            <span class="text-lg" :class="item.iconOutlined ? 'material-icons-outlined' : 'material-icons'">{{ item.icon }}</span>
            <p class="pl-4">{{ item.text }}</p>
          </nuxt-link>
        </template>
      </div>
      <div class="absolute bottom-0 left-0 w-full py-6 px-6 text-gray-300">
        <div v-if="serverConnectionConfig" class="mb-4 flex justify-center">
          <p class="text-xs">{{ serverConnectionConfig.address }}</p>
        </div>
        <div class="flex items-center">
          <p class="text-xs">{{ $config.version }}</p>
          <div class="flex-grow" />
          <div v-if="user" class="flex items-center" @click="logout">
            <p class="text-xs pr-2">Logout</p>
            <span class="material-icons text-sm">logout</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import TouchEvent from '@/objects/TouchEvent'

export default {
  data() {
    return {
      touchEvent: null
    }
  },
  watch: {
    $route: {
      handler() {
        this.show = false
      }
    },
    show: {
      handler(newVal) {
        if (newVal) this.registerListener()
        else this.removeListener()
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
    serverConnectionConfig() {
      return this.$store.state.user.serverConnectionConfig
    },
    username() {
      return this.user ? this.user.username : ''
    },
    navItems() {
      var items = [
        {
          icon: 'home',
          text: 'Home',
          to: '/bookshelf'
        }
      ]
      if (!this.serverConnectionConfig) {
        items = [
          {
            icon: 'cloud_off',
            text: 'Connect to Server',
            to: '/connect'
          }
        ].concat(items)
      } else {
        items.push({
          icon: 'person',
          text: 'Account',
          to: '/account'
        })
      }

      if (this.$platform !== 'ios') {
        items.push({
          icon: 'folder',
          iconOutlined: true,
          text: 'Local Media',
          to: '/localMedia/folders'
        })
        // items.push({
        //   icon: 'settings',
        //   text: 'Settings',
        //   to: '/settings'
        // })
      }
      return items
    },
    currentRoutePath() {
      return this.$route.path
    }
  },
  methods: {
    clickBackground() {
      this.show = false
    },
    async logout() {
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
    },
    touchstart(e) {
      this.touchEvent = new TouchEvent(e)
    },
    touchend(e) {
      if (!this.touchEvent) return
      this.touchEvent.setEndEvent(e)
      if (this.touchEvent.isSwipeRight()) {
        this.show = false
      }
      this.touchEvent = null
    },
    registerListener() {
      document.addEventListener('touchstart', this.touchstart)
      document.addEventListener('touchend', this.touchend)
    },
    removeListener() {
      document.removeEventListener('touchstart', this.touchstart)
      document.removeEventListener('touchend', this.touchend)
    }
  },
  mounted() {},
  beforeDestroy() {
    this.show = false
  }
}
</script>