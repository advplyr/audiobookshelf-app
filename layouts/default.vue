<template>
  <div class="w-full layout-wrapper" :class="{ 'full-height': isPlayerOpen && !isInBookshelfContext }">
    <app-appbar />
    <div id="content" class="relative transition-all duration-300" :class="[isPlayerOpen ? 'playerOpen' : '', isInBookshelfContext ? 'has-bottom-nav' : '', isPlayerOpen && !isInBookshelfContext ? 'content-scrollable' : 'overflow-hidden']" :style="contentStyle">
      <transition
        name="page-transition"
        mode="out-in"
        @enter="onPageEnter"
        @leave="onPageLeave"
        enter-active-class="transition-all duration-500 ease-emphasized-decelerate"
        leave-active-class="transition-all duration-300 ease-emphasized-accelerate"
        enter-from-class="opacity-0 transform scale-90 translate-y-8"
        enter-to-class="opacity-100 transform scale-100 translate-y-0"
        leave-from-class="opacity-100 transform scale-100 translate-y-0"
        leave-to-class="opacity-0 transform scale-95 translate-y-4"
      >
        <Nuxt :key="currentLang" />
      </transition>
    </div>
    <app-audio-player-container ref="streamContainer" />
    <home-bookshelf-nav-bar v-if="isInBookshelfContext" />
    <modals-libraries-modal />
    <modals-playlists-add-create-modal />
    <modals-select-local-folder-modal />
    <modals-rssfeeds-rss-feed-modal />
    <app-side-drawer :key="currentLang" />
    <readers-reader />
  </div>
</template>

<script>
import { CapacitorHttp } from '@capacitor/core'
import { AbsLogger } from '@/plugins/capacitor'
import HomeBookshelfNavBar from '~/components/home/BookshelfNavBar.vue'

export default {
  components: {
    HomeBookshelfNavBar
  },
  data() {
    return {
      inittingLibraries: false,
      hasMounted: false,
      disconnectTime: 0,
      timeLostFocus: 0,
      currentLang: null
    }
  },
  watch: {
    networkConnected: {
      handler(newVal, oldVal) {
        if (!this.hasMounted) {
          // watcher runs before mount, handling libraries/connection should be handled in mount
          return
        }
        if (newVal) {
          console.log(`[default] network connected changed ${oldVal} -> ${newVal}`)
          if (!this.user) {
            this.attemptConnection()
          } else if (!this.currentLibraryId) {
            this.initLibraries()
          } else {
            var timeSinceDisconnect = Date.now() - this.disconnectTime
            if (timeSinceDisconnect > 5000) {
              console.log('Time since disconnect was', timeSinceDisconnect, 'sync with server')
              setTimeout(() => {
                this.syncLocalSessions(false)
              }, 4000)
            }
          }
        } else {
          console.log(`[default] lost network connection`)
          this.disconnectTime = Date.now()
        }
      }
    }
  },
  computed: {
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    routeName() {
      return this.$route.name
    },
    isInBookshelfContext() {
      // Check if current route is bookshelf-related which has bottom navigation
      return this.$route && this.$route.name && this.$route.name.startsWith('bookshelf')
    },
    contentStyle() {
      // Padding is now handled by individual page content
      return {}
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    user() {
      return this.$store.state.user.user
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    currentLibraryName() {
      return this.$store.getters['libraries/getCurrentLibraryName']
    },
    attemptingConnection: {
      get() {
        return this.$store.state.attemptingConnection
      },
      set(val) {
        this.$store.commit('setAttemptingConnection', val)
      }
    }
  },
  methods: {
    onPageEnter(el) {
      // Optional: Add any additional page enter effects here
      if (this.$haptics) {
        this.$haptics.impactLight()
      }
    },
    onPageLeave(el) {
      // Optional: Add any additional page leave effects here
    },
    initialStream(stream) {
      if (this.$refs.streamContainer?.audioPlayerReady) {
        this.$refs.streamContainer.streamOpen(stream)
      }
    },
    async loadSavedSettings() {
      const userSavedServerSettings = await this.$localStore.getServerSettings()
      if (userSavedServerSettings) {
        this.$store.commit('setServerSettings', userSavedServerSettings)
      }

      await this.$store.dispatch('user/loadUserSettings')

      // Initialize dynamic colors if enabled
      if (this.$platform === 'android' && this.$store.state.user.settings.enableDynamicColors && this.$dynamicColor) {
        try {
          await this.$dynamicColor.loadSystemColors()
          console.log('Dynamic colors initialized on app startup')
        } catch (error) {
          console.warn('Failed to initialize dynamic colors:', error)
        }
      }
    },
    async attemptConnection() {
      console.warn('[default] attemptConnection')
      if (!this.networkConnected) {
        console.warn('[default] No network connection')
        AbsLogger.info({ tag: 'default', message: 'attemptConnection: No network connection' })
        return
      }
      if (this.attemptingConnection) {
        return
      }
      this.attemptingConnection = true

      const deviceData = await this.$db.getDeviceData()
      let serverConfig = null
      if (deviceData) {
        this.$store.commit('globals/setHapticFeedback', deviceData.deviceSettings?.hapticFeedback)

        if (deviceData.lastServerConnectionConfigId && deviceData.serverConnectionConfigs.length) {
          serverConfig = deviceData.serverConnectionConfigs.find((scc) => scc.id == deviceData.lastServerConnectionConfigId)
        }
      }

      if (!serverConfig) {
        // No last server config set
        this.attemptingConnection = false
        AbsLogger.info({ tag: 'default', message: 'attemptConnection: No last server config set' })
        return
      }

      AbsLogger.info({ tag: 'default', message: `attemptConnection: Got server config, attempt authorize (${serverConfig.name})` })

      const nativeHttpOptions = {
        headers: {
          Authorization: `Bearer ${serverConfig.token}`
        },
        connectTimeout: 6000,
        serverConnectionConfig: serverConfig
      }
      const authRes = await this.$nativeHttp.post(`${serverConfig.address}/api/authorize`, null, nativeHttpOptions).catch((error) => {
        AbsLogger.error({ tag: 'default', message: `attemptConnection: Server auth failed (${serverConfig.name})` })
        return false
      })

      if (!authRes) {
        this.attemptingConnection = false
        return
      }

      const { user, userDefaultLibraryId, serverSettings, ereaderDevices } = authRes
      this.$store.commit('setServerSettings', serverSettings)
      this.$store.commit('libraries/setEReaderDevices', ereaderDevices)

      if (this.$isValidVersion(serverSettings.version, '2.26.0')) {
        // Check if the server is using the new JWT auth and is still using an old token in the server config
        // If so, redirect to /connect and request to re-login
        if (serverConfig.token === user.token || user.isOldToken) {
          this.attemptingConnection = false
          AbsLogger.info({ tag: 'default', message: `attemptConnection: Server is using new JWT auth but config is still using an old token (server version: ${serverSettings.version}) (${serverConfig.name})` })
          // Clear last server config
          await this.$store.dispatch('user/logout')
          this.$router.push(`/connect?error=oldAuthToken&serverConnectionConfigId=${serverConfig.id}`)
          return
        }

        // Token may have been refreshed during the authorize call so refetch from store
        serverConfig.token = this.$store.getters['user/getToken'] || serverConfig.token
      }

      // Set library - Use last library if set and available fallback to default user library
      const lastLibraryId = await this.$localStore.getLastLibraryId()
      if (lastLibraryId && (!user.librariesAccessible.length || user.librariesAccessible.includes(lastLibraryId))) {
        this.$store.commit('libraries/setCurrentLibrary', lastLibraryId)
      } else if (userDefaultLibraryId) {
        this.$store.commit('libraries/setCurrentLibrary', userDefaultLibraryId)
      }
      serverConfig.version = serverSettings.version
      const serverConnectionConfig = await this.$db.setServerConnectionConfig(serverConfig)

      this.$store.commit('user/setUser', user)
      this.$store.commit('user/setAccessToken', serverConnectionConfig.token)
      this.$store.commit('user/setServerConnectionConfig', serverConnectionConfig)

      this.$socket.connect(serverConnectionConfig.address, serverConnectionConfig.token)

      AbsLogger.info({ tag: 'default', message: `attemptConnection: Successful connection to last saved server config (${serverConnectionConfig.name})` })
      await this.initLibraries()
      this.attemptingConnection = false
    },
    itemRemoved(libraryItem) {
      if (this.$route.name.startsWith('item')) {
        if (this.$route.params.id === libraryItem.id) {
          this.$router.replace(`/bookshelf`)
        }
      }
    },
    socketConnectionFailed(err) {
      this.$toast.error('Socket connection error: ' + err.message)
    },
    async initLibraries() {
      if (this.inittingLibraries) {
        return
      }
      this.inittingLibraries = true
      await this.$store.dispatch('libraries/load')

      AbsLogger.info({ tag: 'default', message: `initLibraries loading library ${this.currentLibraryName}` })
      await this.$store.dispatch('libraries/fetch', this.currentLibraryId)
      this.$eventBus.$emit('library-changed')
      this.inittingLibraries = false
    },
    async syncLocalSessions(isFirstSync) {
      if (!this.user) {
        console.log('[default] No need to sync local sessions - not connected to server')
        return
      }

      AbsLogger.info({ tag: 'default', message: 'Calling syncLocalSessions' })
      const response = await this.$db.syncLocalSessionsWithServer(isFirstSync)
      if (response?.error) {
        console.error('[default] Failed to sync local sessions', response.error)
      } else {
        console.log('[default] Successfully synced local sessions')
        // Reload local media progresses
        await this.$store.dispatch('globals/loadLocalMediaProgress')
      }
    },
    userUpdated(user) {
      if (this.user?.id == user.id) {
        this.$store.commit('user/setUser', user)
      }
    },
    async userMediaProgressUpdated(payload) {
      const prog = payload.data // MediaProgress
      await AbsLogger.info({ tag: 'default', message: `userMediaProgressUpdate: Received updated media progress for current user from socket event. Media item id ${payload.id}` })

      // Check if this media item is currently open in the player, paused, and this progress update is coming from a different session
      const isMediaOpenInPlayer = this.$store.getters['getIsMediaStreaming'](prog.libraryItemId, prog.episodeId)
      if (isMediaOpenInPlayer && this.$store.getters['getCurrentPlaybackSessionId'] !== payload.sessionId && !this.$store.state.playerIsPlaying) {
        await AbsLogger.info({ tag: 'default', message: `userMediaProgressUpdate: Item is currently open in player, paused and this progress update is coming from a different session. Updating playback time to ${payload.data.currentTime}` })
        this.$eventBus.$emit('playback-time-update', payload.data.currentTime)
      }

      // Get local media progress if exists
      const localProg = await this.$db.getLocalMediaProgressForServerItem({ libraryItemId: prog.libraryItemId, episodeId: prog.episodeId })

      let newLocalMediaProgress = null
      // Progress update is more recent then local progress
      if (localProg && localProg.lastUpdate < prog.lastUpdate) {
        if (localProg.currentTime == prog.currentTime && localProg.isFinished == prog.isFinished) {
          await AbsLogger.info({ tag: 'default', message: `userMediaProgressUpdate: server lastUpdate is more recent but progress is up-to-date (libraryItemId: ${prog.libraryItemId}${prog.episodeId ? ` episodeId: ${prog.episodeId}` : ''})` })
          return
        }

        // Server progress is more up-to-date
        await AbsLogger.info({ tag: 'default', message: `userMediaProgressUpdate: syncing progress from server with local item for "${prog.libraryItemId}" ${prog.episodeId ? `episode ${prog.episodeId}` : ''} | server lastUpdate=${prog.lastUpdate} > local lastUpdate=${localProg.lastUpdate}` })
        const payload = {
          localMediaProgressId: localProg.id,
          mediaProgress: prog
        }
        newLocalMediaProgress = await this.$db.syncServerMediaProgressWithLocalMediaProgress(payload)
      } else if (!localProg) {
        // Check if local library item exists
        //   local media progress may not exist yet if it hasn't been played
        const localLibraryItem = await this.$db.getLocalLibraryItemByLId(prog.libraryItemId)
        if (localLibraryItem) {
          if (prog.episodeId) {
            // If episode check if local episode exists
            const lliEpisodes = localLibraryItem.media.episodes || []
            const localEpisode = lliEpisodes.find((ep) => ep.serverEpisodeId === prog.episodeId)
            if (localEpisode) {
              // Add new local media progress
              const payload = {
                localLibraryItemId: localLibraryItem.id,
                localEpisodeId: localEpisode.id,
                mediaProgress: prog
              }
              newLocalMediaProgress = await this.$db.syncServerMediaProgressWithLocalMediaProgress(payload)
            }
          } else {
            // Add new local media progress
            const payload = {
              localLibraryItemId: localLibraryItem.id,
              mediaProgress: prog
            }
            newLocalMediaProgress = await this.$db.syncServerMediaProgressWithLocalMediaProgress(payload)
          }
        } else {
          console.log(`[default] userMediaProgressUpdate no local media progress or lli found for this server item ${prog?.id || prog?.libraryItemId || 'unknown'}`)
        }
      }

      if (newLocalMediaProgress?.id) {
        await AbsLogger.info({ tag: 'default', message: `userMediaProgressUpdate: local media progress updated for ${newLocalMediaProgress.id}` })
        this.$store.commit('globals/updateLocalMediaProgress', newLocalMediaProgress)
      }
    },
    async visibilityChanged() {
      if (document.visibilityState === 'visible') {
        const elapsedTimeOutOfFocus = Date.now() - this.timeLostFocus
        console.log(`✅ [default] device visibility: has focus (${elapsedTimeOutOfFocus}ms out of focus)`)
        // If device out of focus for more than 30s then reload local media progress
        if (elapsedTimeOutOfFocus > 30000) {
          console.log(`✅ [default] device visibility: reloading local media progress`)
          // Reload local media progresses
          await this.$store.dispatch('globals/loadLocalMediaProgress')
        }
        if (document.visibilityState === 'visible') {
          this.$eventBus.$emit('device-focus-update', true)
        }
      } else {
        console.log('⛔️ [default] device visibility: does NOT have focus')
        this.timeLostFocus = Date.now()
        this.$eventBus.$emit('device-focus-update', false)
      }
    },
    changeLanguage(code) {
      console.log('Changed lang', code)
      this.currentLang = code
      document.documentElement.lang = code
    }
  },
  async mounted() {
    this.$eventBus.$on('change-lang', this.changeLanguage)
    document.addEventListener('visibilitychange', this.visibilityChanged)

    this.$socket.on('user_updated', this.userUpdated)
    this.$socket.on('user_media_progress_updated', this.userMediaProgressUpdated)

    if (this.$store.state.isFirstLoad) {
      AbsLogger.info({ tag: 'default', message: `mounted: initializing first load (${this.$platform} v${this.$config.version})` })
      this.$store.commit('setIsFirstLoad', false)

      this.loadSavedSettings()

      const deviceData = await this.$db.getDeviceData()
      this.$store.commit('setDeviceData', deviceData)

      this.$setOrientationLock(this.$store.getters['getOrientationLockSetting'])

      await this.$store.dispatch('setupNetworkListener')

      if (this.$store.state.user.serverConnectionConfig) {
        AbsLogger.info({ tag: 'default', message: `mounted: Server connected, init libraries (${this.$store.getters['user/getServerConfigName']})` })
        await this.initLibraries()
      } else {
        AbsLogger.info({ tag: 'default', message: `mounted: Server not connected, attempt connection` })
        await this.attemptConnection()
      }

      await this.syncLocalSessions(true)

      this.hasMounted = true

      AbsLogger.info({ tag: 'default', message: 'mounted: fully initialized' })
      this.$eventBus.$emit('abs-ui-ready')
    }
  },
  beforeDestroy() {
    this.$eventBus.$off('change-lang', this.changeLanguage)
    document.removeEventListener('visibilitychange', this.visibilityChanged)
    this.$socket.off('user_updated', this.userUpdated)
    this.$socket.off('user_media_progress_updated', this.userMediaProgressUpdated)
  }
}
</script>

<style>
#content.playerOpen {
  padding-bottom: 0px; /* No padding - views fill to nav bar */
}

#content {
  padding-bottom: 0px; /* No padding - views fill to nav bar */
}

/* Material 3 Expressive Page Transitions */
.page-transition-enter-active {
  transition: all 500ms cubic-bezier(0.05, 0.7, 0.1, 1) !important;
  z-index: 2;
}

.page-transition-leave-active {
  transition: all 300ms cubic-bezier(0.3, 0, 0.8, 0.15) !important;
  z-index: 1;
}

.page-transition-enter-from {
  opacity: 0;
  transform: scale(0.9) translateY(32px);
  filter: blur(4px);
}

.page-transition-enter-to {
  opacity: 1;
  transform: scale(1) translateY(0);
  filter: blur(0);
}

.page-transition-leave-from {
  opacity: 1;
  transform: scale(1) translateY(0);
  filter: blur(0);
}

.page-transition-leave-to {
  opacity: 0;
  transform: scale(0.95) translateY(16px);
  filter: blur(2px);
}

/* Prevent layout shift during transitions */
.page-transition-enter-active,
.page-transition-leave-active {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  width: 100%;
  background: rgb(var(--md-sys-color-surface));
}

/* Enhanced motion for bottom nav transitions */
.has-bottom-nav .page-transition-enter-from {
  transform: scale(0.92) translateY(24px);
}

.has-bottom-nav .page-transition-leave-to {
  transform: scale(0.96) translateY(12px);
}
</style>
