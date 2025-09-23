<template>
  <div id="bookshelf-navbar" class="fixed bottom-0 left-0 right-0 w-full bg-surface-container shadow-elevation-3 z-80 border-t border-outline-variant border-opacity-20" :style="{ paddingBottom: 'clamp(0px, var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px)), 16px)', boxSizing: 'border-box', overflow: 'visible', zIndex: 2147483646 }">
    <!-- The inner navbar height includes the safe-area padding so the fixed bar
      visually grows on devices with a bottom inset without requiring the
      content area to subtract the inset separately. -->
    <div id="bookshelf-navbar-inner" class="w-full flex items-center justify-around px-2" :style="{ minHeight: `var(--bottom-nav-height)` }">
      <!-- allows padding to increase height without clipping -->
      <nuxt-link v-for="item in items" :key="item.to" :to="item.to" class="flex flex-col items-center justify-center py-1.5 px-3 min-w-16 transition-all duration-200 ease-standard" :style="{ minHeight: '3.25rem' }" :class="routeName === item.routeName ? 'text-on-surface' : 'text-on-surface-variant'">
        <!-- Icon with Material 3 active state pill -->
        <div class="relative flex items-center justify-center w-16 h-8 rounded-full transition-all duration-200 ease-standard" :class="routeName === item.routeName ? 'bg-secondary-container' : 'bg-transparent'">
          <span v-if="item.iconPack === 'abs-icons'" class="abs-icons transition-colors duration-200" :class="[`icon-${item.icon} text-lg`, routeName === item.routeName ? 'text-on-secondary-container' : 'text-on-surface-variant']"></span>
          <span v-else class="material-symbols text-lg transition-colors duration-200" :class="[item.iconClass || '', routeName === item.routeName ? 'text-on-secondary-container fill' : 'text-on-surface-variant']">{{ item.icon }}</span>
        </div>

        <!-- Label -->
        <p class="text-label-small font-medium leading-none mt-1 transition-colors duration-200" :class="routeName === item.routeName ? 'text-on-surface' : 'text-on-surface-variant'">
          {{ item.text }}
        </p>
      </nuxt-link>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      safeAreaObserver: null
    }
  },
  computed: {
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryIcon() {
      return this.currentLibrary?.icon || 'database'
    },
    userHasPlaylists() {
      return this.$store.state.libraries.numUserPlaylists
    },
    userIsAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    items() {
      let items = []
      if (this.isPodcast) {
        items = [
          {
            to: '/bookshelf',
            routeName: 'bookshelf',
            iconPack: 'abs-icons',
            icon: 'home',
            iconClass: 'text-xl',
            text: this.$strings.ButtonHome
          },
          {
            to: '/bookshelf/latest',
            routeName: 'bookshelf-latest',
            iconPack: 'abs-icons',
            icon: 'list',
            iconClass: 'text-xl',
            text: this.$strings.ButtonLatest
          },
          {
            to: '/bookshelf/library',
            routeName: 'bookshelf-library',
            iconPack: 'abs-icons',
            icon: this.currentLibraryIcon,
            iconClass: 'text-lg',
            text: this.$strings.ButtonLibrary
          }
        ]

        if (this.userIsAdminOrUp) {
          items.push({
            to: '/bookshelf/add-podcast',
            routeName: 'bookshelf-add-podcast',
            iconPack: 'material-symbols',
            icon: 'podcasts',
            iconClass: 'text-xl',
            text: this.$strings.ButtonAdd
          })
        }
      } else {
        items = [
          {
            to: '/bookshelf',
            routeName: 'bookshelf',
            iconPack: 'abs-icons',
            icon: 'home',
            iconClass: 'text-xl',
            text: this.$strings.ButtonHome
          },
          {
            to: '/bookshelf/library',
            routeName: 'bookshelf-library',
            iconPack: 'abs-icons',
            icon: this.currentLibraryIcon,
            iconClass: 'text-lg',
            text: this.$strings.ButtonLibrary
          },
          {
            to: '/bookshelf/series',
            routeName: 'bookshelf-series',
            iconPack: 'abs-icons',
            icon: 'columns',
            iconClass: 'text-lg pt-px',
            text: this.$strings.ButtonSeries
          },
          {
            to: '/bookshelf/collections-playlists',
            routeName: 'bookshelf-collections-playlists',
            iconPack: 'material-symbols',
            icon: 'collections_bookmark',
            iconClass: 'text-xl',
            text: this.$strings.ButtonCollections
          },
          {
            to: '/bookshelf/authors',
            routeName: 'bookshelf-authors',
            iconPack: 'abs-icons',
            icon: 'authors',
            iconClass: 'text-2xl',
            text: this.$strings.ButtonAuthors
          }
        ]
      }

      return items
    },
    routeName() {
      return this.$route.name
    },
    isPodcast() {
      return this.libraryMediaType == 'podcast'
    },
    libraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
    }
  },
  methods: {
    isSelected(item) {}
  },
  mounted() {
    // Set the CSS variable for bottom navigation height to ensure proper calculations
    const updateBottomNavHeight = () => {
      try {
        // Base height for the navigation bar (56px is Material 3 standard)
        const baseHeight = 56
        // Add safe area inset
        const safeInsetStr = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-bottom')?.replace('px', '') || '0'
        const safeInset = parseFloat(safeInsetStr) || 0
        const totalHeight = Math.min(baseHeight + safeInset, 80) // Cap at 80px

        // Always set the base height (the safe area padding is handled separately in the template)
        document.documentElement.style.setProperty('--bottom-nav-height', `${baseHeight}px`)
        console.log('[BookshelfNavBar] Set --bottom-nav-height to:', baseHeight, 'px with safe inset:', safeInset, 'px')
      } catch (e) {
        console.warn('[BookshelfNavBar] Error setting bottom nav height:', e)
        document.documentElement.style.setProperty('--bottom-nav-height', '56px')
      }
    }

    // Listen for safe area changes
    this.safeAreaObserver = new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        if (mutation.type === 'attributes' && mutation.attributeName === 'data-safe-area-ready') {
          updateBottomNavHeight()
        }
      }
    })
    this.safeAreaObserver.observe(document.documentElement, { attributes: true })

    updateBottomNavHeight()

    // Also periodically check for changes in the first few seconds after mount
    // This helps catch cases where the native code sets variables after component mount
    let checkCount = 0
    const periodicCheck = () => {
      if (checkCount < 60) {
        // Extended check for 6 seconds (60 * 100ms) to account for slower WebView init
        updateBottomNavHeight()
        checkCount++
        setTimeout(periodicCheck, 100)
      }
    }
    periodicCheck()

    window.addEventListener('resize', updateBottomNavHeight)
  },
  beforeDestroy() {
    if (this.safeAreaObserver) {
      this.safeAreaObserver.disconnect()
    }
    window.removeEventListener('resize', () => {})
  }
}
</script>

<style scoped>
/* Material 3 Bottom Navigation Bar Styles */
#bookshelf-navbar {
  background-color: rgb(var(--md-sys-color-surface));
}

.state-layer {
  position: relative;
  overflow: hidden;
  transition: all 300ms cubic-bezier(0.2, 0, 0, 1);
}

.state-layer::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  border-radius: inherit;
  background-color: transparent;
  transition: background-color 200ms cubic-bezier(0.2, 0, 0, 1);
  pointer-events: none;
  z-index: 1;
}

.state-layer:hover::before {
  background-color: rgba(var(--md-sys-color-on-surface-variant), 0.08);
}

.state-layer:focus::before {
  background-color: rgba(var(--md-sys-color-on-surface-variant), 0.12);
}

.state-layer:active::before {
  background-color: rgba(var(--md-sys-color-on-surface-variant), 0.16);
}

/* Active state specific styles */
.state-layer.bg-secondary-container::before {
  background-color: transparent;
}

.state-layer.bg-secondary-container:hover::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), 0.08);
}

.state-layer.bg-secondary-container:focus::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), 0.12);
}

.state-layer.bg-secondary-container:active::before {
  background-color: rgba(var(--md-sys-color-on-secondary-container), 0.16);
}

/* Scale animations */
.scale-105 {
  transform: scale(1.05);
}

.hover\:scale-102:hover {
  transform: scale(1.02);
}

/* Ensure content is above state layer */
.state-layer > * {
  position: relative;
  z-index: 2;
}

/* Material 3 expressive easing */
.ease-expressive {
  transition-timing-function: cubic-bezier(0.2, 0, 0, 1);
}

/* Icon fill for active state */
.material-symbols.fill {
  font-variation-settings: 'FILL' 1;
}

/* Active indicator styling */
.absolute {
  position: absolute;
}

/* Smooth transitions for all elements */
* {
  transition-property: color, background-color, transform, box-shadow;
  transition-duration: 300ms;
  transition-timing-function: cubic-bezier(0.2, 0, 0, 1);
}
</style>
