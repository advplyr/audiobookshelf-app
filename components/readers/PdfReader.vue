<template>
  <div class="w-full h-full relative">
    <div class="h-full flex items-center justify-center">
      <div ref="scrollContainer" class="w-full h-full overflow-auto">
        <div v-if="loadedRatio > 0 && loadedRatio < 1" style="background-color: green; color: white; text-align: center" :style="{ width: loadedRatio * 100 + '%' }">{{ Math.floor(loadedRatio * 100) }}%</div>

        <div :style="spacerStyle">
          <div ref="pdfWrapper" style="position: absolute; top: 0; left: 0" :style="pdfWrapperStyle">
            <pdf v-if="pdfDocInitParams" ref="pdf" class="z-10 border border-black border-opacity-20 shadow-md bg-white" style="width: 100%" :src="pdfDocInitParams" :page="page" :rotate="rotate" @progress="loadedRatio = $event" @error="error" @num-pages="numPagesLoaded" @link-clicked="page = $event" @loaded="loadedEvt" />
          </div>
        </div>
      </div>
    </div>

    <div class="fixed left-0 h-8 w-full bg-bg px-4 flex items-center text-fg-muted" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <p class="text-xs">{{ Math.round(zoom * 100) }}%</p>
      <div class="flex-grow" />
      <p class="text-xs">{{ page }} / {{ numPages }}</p>
    </div>
  </div>
</template>

<script>
import pdf from '@teckel/vue-pdf'

export default {
  components: {
    pdf
  },
  props: {
    url: String,
    libraryItem: {
      type: Object,
      default: () => {}
    },
    isLocal: Boolean,
    keepProgress: Boolean
  },
  data() {
    return {
      rotate: 0,
      loadedRatio: 0,
      page: 1,
      numPages: 0,
      windowWidth: 0,
      windowHeight: 0,
      pdfDocInitParams: null,
      isRefreshing: false,

      renderScale: 2.5,

      zoom: 1,
      liveZoom: 1,

      isPinching: false,
      pinch: {
        enableTransform: false,
        startDistance: 0,
        startZoom: 1,
        baseContentX: 0,
        baseContentY: 0,
        lastCenterClientX: 0,
        lastCenterClientY: 0,
        wrapperStartLeft: 0,
        wrapperStartTop: 0,
        startLocalX: 0,
        startLocalY: 0,
        translateX: 0,
        translateY: 0,
        rafId: null
      },
      swipe: {
        startX: 0,
        startY: 0,
        startTime: 0
      },
      lastTapTime: 0,
      touchStartedOnLink: false,
      pendingZoomCommit: null,
      singleTapTimeoutId: null
    }
  },
  computed: {
    userToken() {
      return this.$store.getters['user/getToken']
    },
    localLibraryItem() {
      if (this.isLocal) return this.libraryItem
      return this.libraryItem.localLibraryItem || null
    },
    localLibraryItemId() {
      return this.localLibraryItem?.id
    },
    serverLibraryItemId() {
      if (!this.isLocal) return this.libraryItem.id
      // Check if local library item is connected to the current server
      if (!this.libraryItem.serverAddress || !this.libraryItem.libraryItemId) return null
      if (this.$store.getters['user/getServerAddress'] === this.libraryItem.serverAddress) {
        return this.libraryItem.libraryItemId
      }
      return null
    },
    pdfWidth() {
      if (this.windowWidth > this.windowHeight) {
        // Landscape
        return this.windowHeight * 0.6
      } else {
        // Portrait
        return this.windowWidth
      }
    },
    pdfHeight() {
      return this.pdfWidth * 1.667
    },
    minZoom() {
      return 1
    },
    maxZoom() {
      return 4
    },

    spacerStyle() {
      // Defines the scrollable content size at the current zoom.
      return {
        position: 'relative',
        width: this.pdfWidth * this.zoom + 'px',
        height: this.pdfHeight * this.zoom + 'px'
      }
    },

    pdfWrapperStyle() {
      // Renders the PDF at renderScale, then CSS-scales it to zoom.
      // During pinch we apply translate+scale for a smooth live zoom.

      const style = {
        width: this.pdfWidth * this.renderScale + 'px',
        transformOrigin: '0 0',
        willChange: 'transform'
      }

      if (this.isPinching && this.pinch.enableTransform) {
        const scale = this.liveZoom / this.renderScale
        style.transform = `translate(${this.pinch.translateX}px, ${this.pinch.translateY}px) scale(${scale})`
      } else {
        const scale = this.zoom / this.renderScale
        style.transform = `translate(0px, 0px) scale(${scale})`
      }

      return style
    },

    userItemProgress() {
      if (this.isLocal) return this.localItemProgress
      return this.serverItemProgress
    },
    localItemProgress() {
      return this.$store.getters['globals/getLocalMediaProgressById'](this.localLibraryItemId)
    },
    serverItemProgress() {
      return this.$store.getters['user/getUserMediaProgress'](this.serverLibraryItemId)
    },
    savedPage() {
      if (!this.keepProgress) return 0

      // Validate ebookLocation is a number
      if (!this.userItemProgress?.ebookLocation || isNaN(this.userItemProgress.ebookLocation)) return 0
      return Number(this.userItemProgress.ebookLocation)
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    ebookUrl() {
      const serverAddress = this.$store.getters['user/getServerAddress']
      return this.isLocal ? this.url : `${serverAddress}${this.url}`
    }
  },
  methods: {
    clamp(num, min, max) {
      return Math.max(min, Math.min(max, num))
    },
    getTouchDistance(touchA, touchB) {
      const dx = touchA.clientX - touchB.clientX
      const dy = touchA.clientY - touchB.clientY
      return Math.sqrt(dx * dx + dy * dy)
    },
    getTouchCenter(touchA, touchB) {
      return {
        clientX: (touchA.clientX + touchB.clientX) / 2,
        clientY: (touchA.clientY + touchB.clientY) / 2
      }
    },
    isLinkTarget(event) {
      const target = event && event.target
      if (!target) return false
      if (target.tagName === 'A') return true
      return typeof target.closest === 'function' && !!target.closest('a')
    },

    clearSingleTapTimeout() {
      if (!this.singleTapTimeoutId) return
      clearTimeout(this.singleTapTimeoutId)
      this.singleTapTimeoutId = null
    },
    resetZoom() {
      this.zoom = 1
      this.liveZoom = 1
      this.pendingZoomCommit = null
      this.pinch.enableTransform = false
      this.clearSingleTapTimeout()
      this.$nextTick(() => {
        const container = this.$refs.scrollContainer
        if (!container) return
        container.scrollLeft = 0
        container.scrollTop = 0
      })
    },

    applyPendingZoomCommit() {
      if (!this.pendingZoomCommit) return

      const container = this.$refs.scrollContainer
      if (!container) return

      const { committedZoom, baseX, baseY, centerClientX, centerClientY } = this.pendingZoomCommit

      const containerRect = container.getBoundingClientRect()
      const centerXInContainer = centerClientX - containerRect.left
      const centerYInContainer = centerClientY - containerRect.top

      const desiredLocalX = baseX * committedZoom
      const desiredLocalY = baseY * committedZoom

      let desiredScrollLeft = desiredLocalX - centerXInContainer
      let desiredScrollTop = desiredLocalY - centerYInContainer

      const maxScrollLeft = Math.max(0, container.scrollWidth - container.clientWidth)
      const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight)

      desiredScrollLeft = this.clamp(desiredScrollLeft, 0, maxScrollLeft)
      desiredScrollTop = this.clamp(desiredScrollTop, 0, maxScrollTop)

      container.scrollLeft = desiredScrollLeft
      container.scrollTop = desiredScrollTop

      this.pendingZoomCommit = null
    },

    finalizeZoomCommit() {
      this.applyPendingZoomCommit()
      this.pinch.translateX = 0
      this.pinch.translateY = 0
      this.pinch.enableTransform = false
    },
    afterLayout(cb) {
      this.$nextTick(() => requestAnimationFrame(() => requestAnimationFrame(cb)))
    },
    zoomToClientPoint(committedZoom, clientX, clientY) {
      const container = this.$refs.scrollContainer
      if (!container) return

      const rect = container.getBoundingClientRect()
      const xInContainer = clientX - rect.left
      const yInContainer = clientY - rect.top

      const baseX = (container.scrollLeft + xInContainer) / this.zoom
      const baseY = (container.scrollTop + yInContainer) / this.zoom

      this.zoom = committedZoom
      this.liveZoom = committedZoom
      this.pendingZoomCommit = {
        committedZoom,
        baseX,
        baseY,
        centerClientX: clientX,
        centerClientY: clientY
      }

      this.afterLayout(() => this.finalizeZoomCommit())
    },

    registerTouchListeners() {
      const container = this.$refs.scrollContainer
      if (!container) return

      if (!this._onTouchStart) {
        this._onTouchStart = this.onTouchStart.bind(this)
        this._onTouchMove = this.onTouchMove.bind(this)
        this._onTouchEnd = this.onTouchEnd.bind(this)
      }

      container.addEventListener('touchstart', this._onTouchStart, { passive: true })
      container.addEventListener('touchmove', this._onTouchMove, { passive: false })
      container.addEventListener('touchend', this._onTouchEnd, { passive: true })
      container.addEventListener('touchcancel', this._onTouchEnd, { passive: true })
    },

    unregisterTouchListeners() {
      const container = this.$refs.scrollContainer
      if (!container || !this._onTouchStart) return

      container.removeEventListener('touchstart', this._onTouchStart)
      container.removeEventListener('touchmove', this._onTouchMove)
      container.removeEventListener('touchend', this._onTouchEnd)
      container.removeEventListener('touchcancel', this._onTouchEnd)
    },

    handlePinchStart(e) {
      const container = this.$refs.scrollContainer
      if (!container) return

      const [touchA, touchB] = e.touches

      this.isPinching = true
      this.pinch.enableTransform = this.zoom > 1
      this.pinch.startDistance = this.getTouchDistance(touchA, touchB)
      this.pinch.startZoom = this.zoom
      this.liveZoom = this.zoom

      const rect = container.getBoundingClientRect()
      const center = this.getTouchCenter(touchA, touchB)
      const centerXInContainer = center.clientX - rect.left
      const centerYInContainer = center.clientY - rect.top

      this.pinch.lastCenterClientX = center.clientX
      this.pinch.lastCenterClientY = center.clientY

      const wrapper = this.$refs.pdfWrapper
      if (wrapper) {
        const wrapperRect = wrapper.getBoundingClientRect()
        this.pinch.wrapperStartLeft = wrapperRect.left
        this.pinch.wrapperStartTop = wrapperRect.top

        this.pinch.startLocalX = centerXInContainer + container.scrollLeft
        this.pinch.startLocalY = centerYInContainer + container.scrollTop

        this.pinch.baseContentX = this.pinch.startLocalX / this.pinch.startZoom
        this.pinch.baseContentY = this.pinch.startLocalY / this.pinch.startZoom
      }

      this.pinch.translateX = 0
      this.pinch.translateY = 0
    },
    handlePinchMove(e) {
      e.preventDefault()

      const container = this.$refs.scrollContainer
      if (!container || !this.pinch.startDistance) return

      const [touchA, touchB] = e.touches
      const newDistance = this.getTouchDistance(touchA, touchB)
      const desiredZoom = this.pinch.startZoom * (newDistance / this.pinch.startDistance)
      const newZoom = this.clamp(desiredZoom, this.minZoom, this.maxZoom)

      if (!this.pinch.enableTransform && this.pinch.startZoom === 1 && Math.abs(newZoom - this.pinch.startZoom) > 0.03) {
        this.pinch.enableTransform = true
      }

      if (!this.pinch.enableTransform && this.pinch.startZoom === 1) return

      const center = this.getTouchCenter(touchA, touchB)
      this.pinch.lastCenterClientX = center.clientX
      this.pinch.lastCenterClientY = center.clientY

      if (this.pinch.rafId) cancelAnimationFrame(this.pinch.rafId)
      // Throttle DOM updates during pinch.
      this.pinch.rafId = requestAnimationFrame(() => {
        this.liveZoom = newZoom

        const sx = this.pinch.wrapperStartLeft + this.pinch.startLocalX * (newZoom / this.pinch.startZoom)
        const sy = this.pinch.wrapperStartTop + this.pinch.startLocalY * (newZoom / this.pinch.startZoom)

        this.pinch.translateX = center.clientX - sx
        this.pinch.translateY = center.clientY - sy
      })
    },
    handlePinchEnd() {
      // Convert the temporary pinch translate into scroll offset and commit the zoom.
      if (!this.pinch.enableTransform) {
        this.isPinching = false
        return
      }

      const container = this.$refs.scrollContainer
      const committedZoom = this.clamp(this.liveZoom, this.minZoom, this.maxZoom)

      const translateX = this.pinch.translateX
      const translateY = this.pinch.translateY

      if (container) {
        container.scrollLeft = container.scrollLeft - translateX
        container.scrollTop = container.scrollTop - translateY
      }

      this.pinch.translateX = 0
      this.pinch.translateY = 0
      this.pinch.enableTransform = false
      this.isPinching = false

      this.zoom = committedZoom
      this.liveZoom = committedZoom

      this.pendingZoomCommit = {
        committedZoom,
        baseX: this.pinch.baseContentX,
        baseY: this.pinch.baseContentY,
        centerClientX: this.pinch.lastCenterClientX,
        centerClientY: this.pinch.lastCenterClientY
      }

      this.afterLayout(() => this.finalizeZoomCommit())

      if (this.pinch.rafId) {
        cancelAnimationFrame(this.pinch.rafId)
        this.pinch.rafId = null
      }
    },
    handleDoubleTap(touch) {
      // Double-tap toggles zoom: zoom in to 2x, or reset back to 1x.
      this.clearSingleTapTimeout()
      this.lastTapTime = 0

      if (this.zoom > 1) {
        this.resetZoom()
        return
      }

      this.zoomToClientPoint(2, touch.clientX, touch.clientY)
    },
    handleSingleTap(touch, now) {
      // Single tap is delayed so we can detect double-tap.
      // Edge paging runs in the timer so it doesn't steal a double-tap.
      this.lastTapTime = now
      this.clearSingleTapTimeout()

      this.singleTapTimeoutId = setTimeout(() => {
        this.singleTapTimeoutId = null

        // Single tap is delayed so we can detect double-tap.
        // Edge paging runs in the timer so it doesn't steal a double-tap.
        if (this.zoom === 1) {
          const container = this.$refs.scrollContainer
          if (container) {
            const rect = container.getBoundingClientRect()
            const tapX = touch.clientX - rect.left
            const edgeWidth = Math.min(120, rect.width * 0.2)
            if (tapX <= edgeWidth) return this.prev()
            if (tapX >= rect.width - edgeWidth) return this.next()
          }
        }

        this.$emit('pdf-tap')
      }, 320)
    },
    handleSwipe(dx) {
      this.clearSingleTapTimeout()
      this.lastTapTime = 0
      if (dx < 0) this.next()
      else this.prev()
    },
    handleTapOrSwipeEnd(e) {
      if (!e.changedTouches || !e.changedTouches.length) return

      const now = Date.now()
      const touch = e.changedTouches[0]

      const dx = touch.clientX - this.swipe.startX
      const dy = touch.clientY - this.swipe.startY
      const dt = now - this.swipe.startTime

      const tapDistance = Math.sqrt(dx * dx + dy * dy)
      const isTap = tapDistance < 20 && dt < 350
      const isSwipe = dt < 600 && Math.abs(dx) > 80 && Math.abs(dy) < 70 && Math.abs(dx) > Math.abs(dy) * 1.2

      const isOnLink = this.touchStartedOnLink || this.isLinkTarget(e)
      const action =
        isTap && !isOnLink
          ? this.lastTapTime && now - this.lastTapTime < 300
            ? 'DOUBLE_TAP'
            : 'SINGLE_TAP'
          : this.zoom === 1 && !this.isPinching && isSwipe
            ? 'SWIPE'
            : 'NONE'

      switch (action) {
        case 'DOUBLE_TAP':
          this.handleDoubleTap(touch)
          return
        case 'SINGLE_TAP':
          this.handleSingleTap(touch, now)
          return
        case 'SWIPE':
          this.handleSwipe(dx)
          return
        default:
          return
      }
    },
    onTouchStart(e) {
      if (!e || !e.touches) return

      if (e.touches.length === 2) {
        this.handlePinchStart(e)
        return
      }

      if (e.touches.length === 1) {
        this.swipe.startX = e.touches[0].clientX
        this.swipe.startY = e.touches[0].clientY
        this.swipe.startTime = Date.now()
        this.touchStartedOnLink = this.isLinkTarget(e)
      }
    },
    onTouchMove(e) {
      if (!e || !e.touches) return
      if (this.isPinching && e.touches.length === 2) this.handlePinchMove(e)
    },
    onTouchEnd(e) {
      if (this.isPinching && (!e.touches || e.touches.length < 2)) {
        this.handlePinchEnd()
        return
      }

      this.handleTapOrSwipeEnd(e)
    },

    async updateProgress() {
      if (!this.keepProgress) return

      if (!this.numPages) {
        console.error('Num pages not loaded')
        return
      }

      const payload = {
        ebookLocation: String(this.page),
        ebookProgress: Math.max(0, Math.min(1, (Number(this.page) - 1) / Number(this.numPages)))
      }

      // Update local item
      if (this.localLibraryItemId) {
        const localPayload = {
          localLibraryItemId: this.localLibraryItemId,
          ...payload
        }
        const localResponse = await this.$db.updateLocalEbookProgress(localPayload)
        if (localResponse.localMediaProgress) {
          this.$store.commit('globals/updateLocalMediaProgress', localResponse.localMediaProgress)
        }
      }

      // Update server item
      if (this.serverLibraryItemId) {
        this.$nativeHttp.patch(`/api/me/progress/${this.serverLibraryItemId}`, payload).catch((error) => {
          console.error('PdfReader.updateProgress failed:', error)
        })
      }
    },
    loadedEvt() {
      if (this.savedPage && this.savedPage > 0 && this.savedPage <= this.numPages) {
        this.page = this.savedPage
      }
    },
    numPagesLoaded(e) {
      if (!e) return
      this.numPages = e
    },
    prev() {
      if (this.page <= 1) return
      this.page--
      this.updateProgress()
    },
    next() {
      if (this.page >= this.numPages) return
      this.page++
      this.updateProgress()
    },
    async handleRefreshFailure() {
      try {
        console.log('[PdfReader] Handling refresh failure - logging out user')

        const serverConnectionConfigId = this.$store.getters['user/getServerConnectionConfigId']

        // Clear store
        await this.$store.dispatch('user/logout')

        if (serverConnectionConfigId) {
          // Clear refresh token for server connection config
          await this.$db.clearRefreshToken(serverConnectionConfigId)
        }

        if (window.location.pathname !== '/connect') {
          window.location.href = '/connect?error=refreshTokenFailed&serverConnectionConfigId=' + serverConnectionConfigId
        }
      } catch (error) {
        console.error('[PdfReader] Failed to handle refresh failure:', error)
      }
    },
    async refreshToken() {
      if (this.isRefreshing) return
      this.isRefreshing = true
      // Cannot use axios with this pdf reader so we need to handle the refresh separately
      // Should work on migrating to a different pdf reader in the future
      const newAccessToken = await this.$store.dispatch('user/refreshToken').catch((error) => {
        console.error('Failed to refresh token', error)
        return null
      })
      if (!newAccessToken) {
        this.handleRefreshFailure()
        return
      }

      // Force Vue to re-render the PDF component by creating a new object
      this.pdfDocInitParams = {
        url: this.ebookUrl,
        httpHeaders: {
          Authorization: `Bearer ${newAccessToken}`
        }
      }
      this.isRefreshing = false
    },
    async error(err) {
      if (err && err.status === 401) {
        console.log('Received 401 error, refreshing token')
        await this.refreshToken()
        return
      }
      console.error(err)
    },
    screenOrientationChange() {
      this.windowWidth = window.innerWidth
      this.windowHeight = window.innerHeight
    },
    init() {
      this.pdfDocInitParams = {
        url: this.ebookUrl,
        httpHeaders: {
          Authorization: `Bearer ${this.userToken}`
        }
      }
    }
  },
  mounted() {
    this.windowWidth = window.innerWidth
    this.windowHeight = window.innerHeight

    if (screen.orientation) {
      // Not available on ios
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    } else {
      document.addEventListener('orientationchange', this.screenOrientationChange)
    }
    window.addEventListener('resize', this.screenOrientationChange)

    this.init()
    this.registerTouchListeners()
  },

  beforeDestroy() {
    this.clearSingleTapTimeout()
    this.unregisterTouchListeners()

    if (screen.orientation) {
      // Not available on ios
      screen.orientation.removeEventListener('change', this.screenOrientationChange)
    } else {
      document.removeEventListener('orientationchange', this.screenOrientationChange)
    }
    window.removeEventListener('resize', this.screenOrientationChange)
  }
}
</script>
