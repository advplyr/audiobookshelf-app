<template>
  <div class="fixed top-0 left-0 right-0 layout-wrapper w-full z-50 overflow-hidden pointer-events-none">
    <div class="absolute top-0 left-0 w-full h-full bg-black transition-opacity duration-200" :class="show ? 'bg-opacity-60 pointer-events-auto' : 'bg-opacity-0'" @click="clickBackground" />
    <div class="absolute top-0 right-0 w-64 h-full bg-bg transform transition-transform py-6 pointer-events-auto" :class="show ? '' : 'translate-x-64'" @click.stop>
      <div class="px-6 mb-4">
        <p v-if="user" class="text-base">
          Welcome,
          <strong>{{ username }}</strong>
        </p>
      </div>

      <div class="w-full overflow-y-auto">
        <template v-for="item in navItems">
          <button v-if="item.action" :key="item.text" :tabindex="show ? 0 : -1" class="w-full hover:bg-bg/60 flex items-center py-3 px-6 text-fg-muted" @click="clickAction(item.action)">
            <span class="material-symbols fill text-lg">{{ item.icon }}</span>
            <p class="pl-4">{{ item.text }}</p>
          </button>
          <nuxt-link v-else :to="item.to" :key="item.text" :tabindex="show ? 0 : -1" class="w-full hover:bg-bg/60 flex items-center py-3 px-6 text-fg" :class="currentRoutePath.startsWith(item.to) ? 'bg-bg-hover/50' : 'text-fg-muted'">
            <span class="material-symbols fill text-lg">{{ item.icon }}</span>
            <p class="pl-4">{{ item.text }}</p>
          </nuxt-link>
        </template>
      </div>
      <div class="absolute bottom-0 left-0 w-full py-6 px-6 text-fg">
        <div v-if="serverConnectionConfig" class="mb-4 flex justify-center">
          <p class="text-xs text-fg-muted" style="word-break: break-word">{{ serverConnectionConfig.address }} (v{{ serverSettings.version }})</p>
        </div>
        <div class="flex items-center">
          <p class="text-xs">{{ $config.version }}</p>
          <div class="flex-grow" />
          <div v-if="user" class="flex items-center" @click="disconnect">
            <p class="text-xs pr-2">{{ $strings.ButtonDisconnect }}</p>
            <i class="material-symbols text-sm -mb-0.5">cloud_off</i>
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
    serverSettings() {
      return this.$store.state.serverSettings || {}
    },
    username() {
      return this.user?.username || ''
    },
    userIsAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    navItems() {
      var items = [
        {
          icon: 'home',
          text: this.$strings.ButtonHome,
          to: '/bookshelf'
        }
      ]
      if (!this.serverConnectionConfig) {
        items = [
          {
            icon: 'cloud_off',
            text: this.$strings.ButtonConnectToServer,
            to: '/connect'
          }
        ].concat(items)
      } else {
        items.push({
          icon: 'person',
          text: this.$strings.HeaderAccount,
          to: '/account'
        })
        items.push({
          icon: 'equalizer',
          text: this.$strings.ButtonUserStats,
          to: '/stats'
        })
      }

      if (this.$platform !== 'ios') {
        items.push({
          icon: 'folder',
          iconOutlined: true,
          text: this.$strings.ButtonLocalMedia,
          to: '/localMedia/folders'
        })
      } else {
        items.push({
          icon: 'download',
          iconOutlined: false,
          text: this.$strings.HeaderDownloads,
          to: '/downloads'
        })
      }
      items.push({
        icon: 'settings',
        text: this.$strings.HeaderSettings,
        to: '/settings'
      })

      items.push({
        icon: 'bug_report',
        iconOutlined: true,
        text: this.$strings.ButtonLogs,
        to: '/logs'
      })

      if (this.serverConnectionConfig) {
        items.push({
          icon: 'language',
          text: this.$strings.ButtonGoToWebClient,
          action: 'openWebClient'
        })

        items.push({
          icon: 'login',
          text: this.$strings.ButtonSwitchServerUser,
          action: 'logout'
        })
      }

      return items
    },
    currentRoutePath() {
      return this.$route.path
    }
  },
  methods: {
    async clickAction(action) {
      await this.$hapticsImpact()
      if (action === 'logout') {
        await this.logout()
        this.$router.push('/connect')
      } else if (action === 'openWebClient') {
        this.show = false
        let path = `/library/${this.$store.state.libraries.currentLibraryId}`
        await this.$store.dispatch('user/openWebClient', path)
      }
    },
    clickBackground() {
      this.show = false
    },
    async logout() {
      await this.$store.dispatch('user/logout')
    },
    async disconnect() {
      await this.$hapticsImpact()
      await this.logout()

      // Redirect to home page
      if (this.$route.name !== 'bookshelf') {
        this.$router.replace('/bookshelf')
      }

      // If player is open and not playing locally, then close the player
      if (this.$store.getters['getIsPlayerOpen']) {
        this.$eventBus.$emit('close-stream')
      }

      // Close side drawer
      this.show = false
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
