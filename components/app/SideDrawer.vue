<template>
  <div class="fixed left-0 right-0 layout-wrapper w-full overflow-hidden pointer-events-none safe-area-root" style="z-index: 2147483648; top: min(var(--safe-area-inset-top, env(safe-area-inset-top, constant(safe-area-inset-top, 0px))), 64px)">
    <!-- Material 3 Scrim (no opacity transition to avoid flash) -->
    <div class="absolute top-0 left-0 w-full h-full bg-black" :class="show ? 'bg-opacity-32 pointer-events-auto' : 'bg-opacity-0 pointer-events-none'" @click="clickBackground" />

    <!-- left-edge hit target removed: open-swipe detected via document-level capture listeners -->

    <!-- Material 3 Navigation Drawer -->
    <div class="absolute top-0 left-0 w-80 h-full bg-surface-dynamic shadow-elevation-1 transform transition-transform duration-300 ease-emphasized pointer-events-auto" :class="show ? '' : '-translate-x-80'" @click.stop>
      <!-- Header Section -->
      <div class="px-6 py-6 border-b border-outline-variant">
        <p v-if="user" class="text-title-medium text-on-surface">
          <span class="text-primary font-medium">{{ username }}</span>
        </p>
        <p v-else class="text-title-medium text-on-surface">{{ $strings.HeaderMenu }}</p>
      </div>

      <!-- Navigation Items -->
      <div class="w-full overflow-y-auto flex-1 py-2">
        <template v-for="item in navItems">
          <button v-if="item.action" :key="item.text" class="w-full state-layer flex items-center py-4 px-6 text-on-surface-variant hover:bg-on-surface/8 transition-colors duration-200 ease-standard" @click="clickAction(item.action)">
            <span class="material-symbols text-2xl mr-3 text-on-surface-variant" :class="item.iconOutlined ? '' : 'fill'">{{ item.icon }}</span>
            <p class="text-body-large">{{ item.text }}</p>
          </button>
          <nuxt-link v-else :to="item.to" :key="item.text" class="w-full state-layer flex items-center py-4 px-6 transition-colors duration-200 ease-standard" :class="currentRoutePath.startsWith(item.to) ? 'bg-primary-container text-on-primary-container' : 'text-on-surface-variant hover:bg-on-surface/8'">
            <span class="material-symbols text-2xl mr-3" :class="[item.iconOutlined ? '' : 'fill', currentRoutePath.startsWith(item.to) ? 'text-on-primary-container' : 'text-on-surface-variant']">{{ item.icon }}</span>
            <p class="text-body-large">{{ item.text }}</p>
          </nuxt-link>
        </template>
      </div>

      <!-- Footer Section -->
      <div class="border-t border-outline-variant px-6 py-4 drawer-footer">
        <div v-if="serverConnectionConfig" class="mb-3 text-center">
          <p class="text-body-small text-on-surface-variant break-all">{{ serverConnectionConfig.address }}</p>
          <p class="text-body-small text-on-surface-variant">v{{ serverSettings.version }}</p>
        </div>
        <div class="flex items-center justify-between">
          <p class="text-body-small text-on-surface-variant">v{{ $config.version }}</p>
          <ui-btn v-if="user" variant="text" color="error" small @click="disconnect">
            {{ $strings.ButtonDisconnect }}
            <span class="material-symbols text-sm ml-1 text-on-surface-variant">cloud_off</span>
          </ui-btn>
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
        if (newVal) {
          this.registerListener()
          // align drawer with appbar and debug runtime safe-area values when drawer opens
          if (this.alignWithAppbar) this.alignWithAppbar()
          // apply expressive shift to main content
          if (this.applyExpressiveShift) this.applyExpressiveShift()
        } else {
          this.removeListener()
          // reset expressive shift when drawer closes
          if (this.resetExpressiveShift) this.resetExpressiveShift()
        }
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

      if (this.$platform !== 'ios') {
        items.push({
          icon: 'bug_report',
          iconOutlined: true,
          text: this.$strings.ButtonLogs,
          to: '/logs'
        })
      }

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

      if (this.$route.name !== 'bookshelf') {
        this.$router.replace('/bookshelf')
      } else {
        location.reload()
      }
    },
    touchstart(e) {
      this.touchEvent = new TouchEvent(e)
    },
    touchend(e) {
      if (!this.touchEvent) return
      this.touchEvent.setEndEvent(e)
      // Close drawer on swipe left
      if (this.touchEvent.isSwipeLeft()) {
        this.show = false
      }
      this.touchEvent = null
    },
    registerListener() {
      document.addEventListener('touchstart', this.touchstart)
      document.addEventListener('touchend', this.touchend)
      // non-passive touchmove so we can prevent default horizontal panning while swiping inside drawer
      document.addEventListener('touchmove', this.inDrawerTouchMove, { passive: false })
    },
    removeListener() {
      document.removeEventListener('touchstart', this.touchstart)
      document.removeEventListener('touchend', this.touchend)
      document.removeEventListener('touchmove', this.inDrawerTouchMove)
    },
    // Global open-swipe listeners to open the drawer from anywhere
    openTouchStart(e) {
      this._openTouchStart = e
      const t = e.changedTouches && e.changedTouches[0]
      if (t) {
        // Only capture if touch starts in left 1/8 of screen
        if (t.clientX < window.innerWidth * 0.125) {
          this._openStartX = t.clientX
          this._openStartY = t.clientY
        } else {
          this._openStartX = null
          this._openStartY = null
        }
      }
    },
    openTouchMove(e) {
      try {
        if (!this._openTouchStart || this._openStartX == null) return
        const start = this._openTouchStart.changedTouches && this._openTouchStart.changedTouches[0]
        const move = e.changedTouches && e.changedTouches[0]
        if (!start || !move) return
        const dx = move.clientX - start.clientX
        const dy = move.clientY - start.clientY
        // if horizontal movement dominates, prevent default to stop content panning
        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 8) {
          e.preventDefault()
        }
      } catch (err) {}
    },
    openTouchEnd(e) {
      try {
        if (!this._openTouchStart || this._openStartX == null) return
        const t = e.changedTouches && e.changedTouches[0]
        const endX = t ? t.clientX : null
        const endY = t ? t.clientY : null
        if (this._openStartX != null && endX != null) {
          const dx = endX - this._openStartX
          const dy = endY - this._openStartY
          // require horizontal dominance and min distance
          if (Math.abs(dx) > Math.abs(dy) && dx > 50) {
            this.show = true
          }
        }
      } catch (err) {
        // ignore
      } finally {
        this._openTouchStart = null
        this._openStartX = null
        this._openStartY = null
      }
    },
    registerOpenListener(capture) {
      // capture param ensures handler runs before other elements' handlers
      const cap = !!capture
      document.addEventListener('touchstart', this.openTouchStart, cap ? { capture: true, passive: true } : { passive: true })
      document.addEventListener('touchmove', this.openTouchMove, cap ? { capture: true, passive: false } : { passive: false })
      document.addEventListener('touchend', this.openTouchEnd, cap ? { capture: true, passive: true } : { passive: true })
    },
    removeOpenListener(capture) {
      const cap = !!capture
      document.removeEventListener('touchstart', this.openTouchStart, cap ? true : false)
      document.removeEventListener('touchmove', this.openTouchMove, cap ? true : false)
      document.removeEventListener('touchend', this.openTouchEnd, cap ? true : false)
    },
    inDrawerTouchMove(e) {
      try {
        if (!this.touchEvent) return
        const start = this.touchEvent.startEvent.changedTouches[0]
        const move = e.changedTouches && e.changedTouches[0]
        if (!start || !move) return
        const dx = move.clientX - start.clientX
        const dy = move.clientY - start.clientY
        if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 8) {
          // prevent browser from panning the main content while swiping to close
          e.preventDefault()
        }
      } catch (err) {}
    },
    // left-edge handlers removed; open-swipe handled by document capture listeners
    alignWithAppbar() {
      try {
        const root = this.$el && this.$el.closest ? this.$el.closest('.safe-area-root') : document.querySelector('.safe-area-root')
        const appbar = document.querySelector('#appbar') || document.querySelector('app-appbar') || document.querySelector('.bg-surface-container')
        if (!root) return
        let topValue = null
        if (appbar && appbar.getBoundingClientRect) {
          const rect = appbar.getBoundingClientRect()
          // Use the bottom of the appbar (distance from viewport top) so drawer starts aligned with appbar content
          topValue = Math.round(rect.bottom)
        }
        if (topValue === null || isNaN(topValue)) {
          // fallback to CSS var (capped)
          const varTop = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-top') || ''
          const parsed = parseFloat(varTop) || 0
          topValue = Math.min(parsed || 0, 64)
        }
        // cap to 64px to avoid extreme values
        topValue = Math.min(topValue, 64)
        root.style.top = `${topValue}px`
      } catch (e) {
        // ignore alignment errors
      }
    },
    applyExpressiveShift() {
      try {
        const content = document.querySelector('#content')
        if (!content) return
        // Following Material 3 expressive patterns: translate X and slightly scale down
        // Include box-shadow in the transition so dimming animates smoothly
        content.style.transition = 'transform 320ms cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 240ms ease'
        content.style.transform = 'translateX(12rem) scale(0.98)'
        // Animate dim: set initial zero inset shadow, force layout, then apply target shadow
        content.style.boxShadow = 'inset 0 0 0 0 rgba(0,0,0,0)'
        // small timeout to ensure the browser sees the initial state before transition
        setTimeout(() => {
          try {
            content.style.boxShadow = 'inset 0 0 0 9999px rgba(0,0,0,0.04)'
          } catch (e) {}
        }, 16)
      } catch (e) {
        // ignore animation errors
      }
    },
    resetExpressiveShift() {
      try {
        const content = document.querySelector('#content')
        if (!content) return
        // Animate transform and dim back to normal
        content.style.transition = 'transform 220ms cubic-bezier(0.2, 0.8, 0.2, 1), box-shadow 200ms ease'
        content.style.transform = ''
        // Transition box-shadow back to zero inset so the dim fades out
        content.style.boxShadow = 'inset 0 0 0 0 rgba(0,0,0,0)'
        // Clear inline styles after the transition completes to avoid blocking other styles
        setTimeout(() => {
          try {
            content.style.transition = ''
            content.style.boxShadow = ''
          } catch (e) {}
        }, 260)
      } catch (e) {
        // ignore animation errors
      }
    }
  },
  mounted() {
    // register open listener in capture phase so it runs before other handlers
    this.$nextTick(() => {
      this.registerOpenListener(true)
    })
    try {
      const content = document.querySelector('#content')
      if (content) {
        // store previous value to restore later
        this._prevContentTouchAction = content.style.touchAction || ''
        content.style.touchAction = 'pan-y'
      }
    } catch (e) {}
  },
  beforeDestroy() {
    this.removeOpenListener(true)
    this.resetExpressiveShift()
    try {
      const content = document.querySelector('#content')
      if (content) {
        content.style.touchAction = this._prevContentTouchAction || ''
      }
    } catch (e) {}
    this.show = false
  }
}
</script>

<style scoped>
/* Material 3 Navigation Drawer Styles */
.state-layer {
  position: relative;
  overflow: hidden;
}

.state-layer::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: transparent;
  transition: background-color var(--md-sys-motion-duration-short2) var(--md-sys-motion-easing-standard);
  pointer-events: none;
}

.state-layer:hover::before {
  background-color: rgba(var(--md-sys-color-on-surface), var(--md-sys-state-hover-opacity));
}

.state-layer:focus::before {
  background-color: rgba(var(--md-sys-color-on-surface), var(--md-sys-state-focus-opacity));
}

.state-layer:active::before {
  background-color: rgba(var(--md-sys-color-on-surface), var(--md-sys-state-pressed-opacity));
}

/* Active navigation item styling */
.bg-secondary-container .state-layer:hover::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), var(--md-sys-state-hover-opacity));
}

/* Custom opacity for scrim */
.bg-opacity-32 {
  opacity: 0.32;
}

/* Drawer width */
.w-80 {
  width: 20rem;
}

.translate-x-80 {
  transform: translateX(20rem);
}

/* Respect device safe area insets (iOS notch / status bar, home indicator) */
.safe-area-top {
  /* padding-top should include the system safe area inset on iOS and similar devices */
  /* Legacy iOS constant() fallback for older Safari/WebViews */
  padding-top: constant(safe-area-inset-top, 0px);
  /* Modern browsers/webviews use env() and should override the constant() when supported */
  padding-top: env(safe-area-inset-top, 0px);
}

.safe-area-bottom {
  padding-bottom: constant(safe-area-inset-bottom, 0px);
  padding-bottom: env(safe-area-inset-bottom, 0px);
}

/* Apply bottom safe area to footer section inside the drawer so buttons aren't under the home indicator */
.drawer-footer {
  /* ensure footer includes safe area at bottom */
  padding-bottom: calc(var(--drawer-footer-padding, 1rem) + constant(safe-area-inset-bottom, 0px));
  padding-bottom: calc(var(--drawer-footer-padding, 1rem) + env(safe-area-inset-bottom, 0px));
}

/* Shift the entire fixed wrapper down by the top safe area so nothing renders under the status bar */
.safe-area-root {
  top: min(var(--safe-area-inset-top, env(safe-area-inset-top, constant(safe-area-inset-top, 0px))), 64px);
  bottom: 0;
}

/* Invisible left edge hit target that captures swipes to open the drawer */
.left-edge-swipe {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: 25vw;
  z-index: 90; /* above content but below drawer which is z-index 80 on wrapper; ensure it captures touches */
  background: transparent;
  pointer-events: auto;
}
</style>
