<template>
  <div id="comic-reader" class="w-full h-full relative">
    <modals-modal v-model="showInfoMenu" height="90%">
      <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click.stop="showInfoMenu = false">
        <div class="w-full overflow-x-hidden overflow-y-auto bg-bg rounded-lg border border-border text-fg" style="max-height: 75%" @click.stop>
          <div v-for="key in comicMetadataKeys" :key="key" class="w-full px-2 py-1">
            <p class="text-xs">
              <strong>{{ key }}</strong>
              : {{ comicMetadata[key] }}
            </p>
          </div>
        </div>
      </div>
    </modals-modal>

    <div class="overflow-hidden m-auto comicwrapper relative">
      <div class="h-full flex justify-center">
        <img v-if="mainImg" :src="mainImg" class="object-contain comicimg" @error="onImageError" />
      </div>

      <div v-show="loading" class="w-full h-full absolute top-0 left-0 flex items-center justify-center z-10">
        <ui-loading-indicator />
      </div>
    </div>

    <div class="fixed left-0 h-8 w-full bg-bg px-4 flex items-center text-fg-muted transition-transform" :class="showingToolbar ? 'translate-y-0' : isPlayerOpen ? 'translate-y-[120px]' : 'translate-y-32'" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <div class="flex-grow" />
      <p class="text-xs">{{ page }} / {{ numPages }}</p>
    </div>

    <modals-dialog v-model="showPageMenu" :items="pageItems" :selected="page" :width="360" item-padding-y="8px" @action="setPage" />
  </div>
</template>

<script>
import Path from 'path'
import { Archive } from 'libarchive.js/main.js'
import { CompressedFile } from 'libarchive.js/src/compressed-file'

Archive.init({
  workerUrl: '/libarchive/worker-bundle.js'
})

export default {
  props: {
    url: String,
    libraryItem: {
      type: Object,
      default: () => {}
    },
    isLocal: Boolean,
    keepProgress: Boolean,
    showingToolbar: Boolean
  },
  data() {
    return {
      loading: false,
      pages: null,
      filesObject: null,
      mainImg: null,
      page: 0,
      numPages: 0,
      showPageMenu: false,
      showInfoMenu: false,
      loadTimeout: null,
      loadedFirstPage: false,
      comicMetadata: null,
      pageMenuWidth: 256,
      // Server-side extraction support
      useServerExtraction: false,
      serverPagesData: null,
      fileIno: null,
      extractedItemId: null,
      // Preloading for smooth navigation
      preloadedPages: {},
      preloadQueue: []
    }
  },
  watch: {
    url: {
      immediate: true,
      handler() {
        this.init()
      }
    }
  },
  computed: {
    libraryItemId() {
      return this.libraryItem?.id
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
    comicMetadataKeys() {
      return this.comicMetadata ? Object.keys(this.comicMetadata) : []
    },
    canGoNext() {
      return this.page < this.numPages
    },
    canGoPrev() {
      return this.page > 1
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
    selectedCleanedPage() {
      return this.cleanedPageNames[this.page - 1]
    },
    cleanedPageNames() {
      return (
        this.pages?.map((p) => {
          if (p.length > 40) {
            let firstHalf = p.slice(0, 18)
            let lastHalf = p.slice(p.length - 17)
            return `${firstHalf} ... ${lastHalf}`
          }
          return p
        }) || []
      )
    },
    pageItems() {
      let index = 1
      return this.cleanedPageNames.map((p) => {
        return {
          text: p,
          value: index++
        }
      })
    },
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    }
  },
  methods: {
    async updateProgress() {
      if (!this.keepProgress) return

      if (!this.numPages) {
        console.error('Num pages not loaded')
        return
      }
      if (this.savedPage === this.page) {
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
          console.error('ComicReader.updateProgress failed:', error)
        })
      }
    },
    clickShowInfoMenu() {
      this.showInfoMenu = !this.showInfoMenu
      this.showPageMenu = false
    },
    clickShowPageMenu() {
      if (!this.numPages) return
      this.showPageMenu = !this.showPageMenu
      this.showInfoMenu = false
    },
    next() {
      if (!this.canGoNext) return
      this.setPage(this.page + 1)
    },
    prev() {
      if (!this.canGoPrev) return
      this.setPage(this.page - 1)
    },
    async setPage(page) {
      if (page <= 0 || page > this.numPages) {
        return
      }
      this.showPageMenu = false
      this.page = page

      this.updateProgress()

      if (this.useServerExtraction) {
        await this.loadServerPage(page)
        this.preloadAdjacentPages(page)
      } else {
        const filename = this.pages[page - 1]
        await this.extractFile(filename)
      }
    },
    setLoadTimeout() {
      this.loadTimeout = setTimeout(() => {
        this.loading = true
      }, 150)
    },
    onImageError(e) {
      console.error('Failed to load image', e)
      this.$toast.error('Failed to load page')
      this.loading = false
    },

    // ===== Server-side extraction methods =====
    async loadServerPage(pageNum) {
      // Check if already preloaded
      if (this.preloadedPages[pageNum]) {
        this.mainImg = this.preloadedPages[pageNum]
        this.loading = false
        return
      }

      this.setLoadTimeout()

      try {
        const pageUrl = this.getServerPageUrl(pageNum)
        // For server pages, we can use the URL directly as img src
        // The server handles extraction and returns the image
        this.mainImg = pageUrl
        this.loading = false
        clearTimeout(this.loadTimeout)
      } catch (error) {
        console.error('Failed to load server page:', error)
        this.$toast.error('Failed to load page')
        this.loading = false
        clearTimeout(this.loadTimeout)
      }
    },
    getServerPageUrl(pageNum) {
      const serverAddress = this.$store.getters['user/getServerAddress']
      const itemId = this.extractedItemId || this.serverLibraryItemId
      const baseUrl = `/api/items/${itemId}/comic-page/${pageNum}`
      if (this.fileIno) {
        return `${serverAddress}${baseUrl}/${this.fileIno}`
      }
      return `${serverAddress}${baseUrl}`
    },
    preloadAdjacentPages(currentPage) {
      // Preload 2 pages ahead and 1 behind
      const pagesToPreload = [
        currentPage + 1,
        currentPage + 2,
        currentPage - 1
      ].filter(p => p > 0 && p <= this.numPages && !this.preloadedPages[p])

      pagesToPreload.forEach(pageNum => {
        const img = new Image()
        img.onload = () => {
          this.preloadedPages[pageNum] = img.src
        }
        img.src = this.getServerPageUrl(pageNum)
      })
    },
    async initServerExtraction() {
      this.loading = true

      try {
        // Extract item ID from the ebook URL which we know works
        // URL format: /api/items/{itemId}/ebook or /api/items/{itemId}/ebook/{fileId}
        const itemIdMatch = this.url.match(/\/api\/items\/([^/]+)\/ebook/)
        if (!itemIdMatch) {
          console.error('[ComicReader] Could not extract item ID from URL:', this.url)
          throw new Error('Could not extract item ID from URL')
        }
        const itemId = itemIdMatch[1]
        
        // Get comic metadata from server
        let pagesUrl = `/api/items/${itemId}/comic-pages`
        
        // Extract file ino from URL if present (for supplementary ebooks)
        const urlMatch = this.url.match(/\/ebook\/([^/]+)$/)
        if (urlMatch) {
          this.fileIno = urlMatch[1]
          pagesUrl += `/${this.fileIno}`
        }

        console.log('[ComicReader] URL:', this.url)
        console.log('[ComicReader] Fetching comic pages from:', pagesUrl)
        // Use nativeHttp which handles server address and auth
        const response = await this.$nativeHttp.get(pagesUrl)
        console.log('[ComicReader] Got response:', response)
        this.serverPagesData = response

        this.pages = response.pages.map(p => p.filename)
        this.numPages = response.numPages
        this.fileIno = response.fileIno
        this.extractedItemId = itemId  // Store for use in getServerPageUrl

        console.log(`[ComicReader] Server extraction ready: ${this.numPages} pages`)
        this.loading = false

        const startPage = this.savedPage > 0 && this.savedPage <= this.numPages ? this.savedPage : 1
        await this.setPage(startPage)
        this.loadedFirstPage = true

        this.$emit('loaded', {
          hasMetadata: false // Server extraction doesn't include comic metadata yet
        })
      } catch (error) {
        console.error('[ComicReader] Failed to init server extraction:', error)
        console.error('[ComicReader] Error details:', error.response?.status, error.response?.data)
        // Fall back to client-side extraction
        this.$toast.error(`Server failed (${error.response?.status || 'network'}), using slow extraction`)
        console.log('[ComicReader] Falling back to client-side extraction')
        this.useServerExtraction = false
        await this.extract()
      }
    },

    // ===== Client-side extraction methods (for local files) =====
    extractFile(filename) {
      return new Promise(async (resolve) => {
        this.setLoadTimeout()
        var file = await this.filesObject[filename].extract()
        var reader = new FileReader()
        reader.onload = (e) => {
          this.mainImg = e.target.result
          this.loading = false
          resolve()
        }
        reader.onerror = (e) => {
          console.error(e)
          this.$toast.error('Read page file failed')
          this.loading = false
          resolve()
        }
        reader.readAsDataURL(file)
        clearTimeout(this.loadTimeout)
      })
    },
    async extract() {
      this.loading = true

      // TODO: Handle JWT auth refresh
      const buff = await this.$axios.$get(this.url, {
        responseType: 'blob'
      })

      const archive = await Archive.open(buff)
      const originalFilesObject = await archive.getFilesObject()
      // to support images in subfolders we need to flatten the object
      //   ref: https://github.com/advplyr/audiobookshelf/issues/811
      this.filesObject = this.flattenFilesObject(originalFilesObject)
      console.log('Extracted files object', this.filesObject)
      var filenames = Object.keys(this.filesObject)
      this.parseFilenames(filenames)

      var xmlFile = filenames.find((f) => (Path.extname(f) || '').toLowerCase() === '.xml')
      if (xmlFile) await this.extractXmlFile(xmlFile)

      this.numPages = this.pages.length

      // Calculate page menu size
      const largestFilename = this.cleanedPageNames
        .map((p) => p)
        .sort((a, b) => a.length - b.length)
        .pop()
      const pEl = document.createElement('p')
      pEl.innerText = largestFilename
      pEl.style.fontSize = '0.875rem'
      pEl.style.opacity = 0
      pEl.style.position = 'absolute'
      document.body.appendChild(pEl)
      const textWidth = pEl.getBoundingClientRect()?.width
      if (textWidth) {
        this.pageMenuWidth = textWidth + (16 + 5 + 2 + 5)
      }
      pEl.remove()

      if (this.pages.length) {
        this.loading = false

        const startPage = this.savedPage > 0 && this.savedPage <= this.numPages ? this.savedPage : 1
        await this.setPage(startPage)
        this.loadedFirstPage = true

        this.$emit('loaded', {
          hasMetadata: this.comicMetadata
        })
      } else {
        this.$toast.error('Unable to extract pages')
        this.loading = false
      }
    },
    flattenFilesObject(filesObject) {
      const flattenObject = (obj, prefix = '') => {
        var _obj = {}
        for (const key in obj) {
          const newKey = prefix ? prefix + '/' + key : key
          if (obj[key] instanceof CompressedFile) {
            _obj[newKey] = obj[key]
          } else if (!key.startsWith('_') && typeof obj[key] === 'object' && !Array.isArray(obj[key])) {
            _obj = {
              ..._obj,
              ...flattenObject(obj[key], newKey)
            }
          } else {
            _obj[newKey] = obj[key]
          }
        }
        return _obj
      }
      return flattenObject(filesObject)
    },
    async extractXmlFile(filename) {
      try {
        var file = await this.filesObject[filename].extract()
        var reader = new FileReader()
        reader.onload = (e) => {
          this.comicMetadata = this.$xmlToJson(e.target.result)
          console.log('Metadata', this.comicMetadata)
        }
        reader.onerror = (e) => {
          console.error(e)
        }
        reader.readAsText(file)
      } catch (error) {
        console.error(error)
      }
    },
    parseImageFilename(filename) {
      var basename = Path.basename(filename, Path.extname(filename))
      var numbersinpath = basename.match(/\d+/g)
      if (!numbersinpath?.length) {
        return {
          index: -1,
          filename
        }
      } else {
        return {
          index: Number(numbersinpath[numbersinpath.length - 1]),
          filename
        }
      }
    },
    parseFilenames(filenames) {
      const acceptableImages = ['.jpeg', '.jpg', '.png', '.webp']
      var imageFiles = filenames.filter((f) => {
        return acceptableImages.includes((Path.extname(f) || '').toLowerCase())
      })
      var imageFileObjs = imageFiles.map((img) => {
        return this.parseImageFilename(img)
      })

      var imagesWithNum = imageFileObjs.filter((i) => i.index >= 0)
      var orderedImages = imagesWithNum.sort((a, b) => a.index - b.index).map((i) => i.filename)
      var noNumImages = imageFileObjs.filter((i) => i.index < 0)
      orderedImages = orderedImages.concat(noNumImages.map((i) => i.filename))

      this.pages = orderedImages
    },

    // ===== Initialization =====
    async init() {
      // Reset state
      this.preloadedPages = {}
      this.filesObject = null
      this.serverPagesData = null
      this.fileIno = null
      this.page = 0
      this.numPages = 0
      this.mainImg = null
      this.loadedFirstPage = false

      // Determine whether to use server-side or client-side extraction
      // Use server-side for remote files, client-side for local files
      console.log('[ComicReader] init - isLocal:', this.isLocal, 'serverLibraryItemId:', this.serverLibraryItemId)
      if (this.isLocal || !this.serverLibraryItemId) {
        console.log('[ComicReader] Using client-side comic extraction (local file)')
        console.log('[ComicReader] Using client-side extraction')
        this.useServerExtraction = false
        await this.extract()
      } else {
        console.log('[ComicReader] Using server-side comic extraction')
        console.log('[ComicReader] Trying server-side extraction')
        this.useServerExtraction = true
        await this.initServerExtraction()
      }
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>

<style scoped>
#comic-reader {
  height: 100%;
  max-height: 100%;
}
.reader-player-open #comic-reader {
  height: calc(100% - 120px);
  max-height: calc(100% - 120px);
}

.comicimg {
  height: 100%;
  margin: auto;
}
.comicwrapper {
  width: 100vw;
  height: 100%;
}
</style>
