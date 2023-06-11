<template>
  <div id="comic-reader" class="w-full h-full relative">
    <div v-show="showInfoMenu" v-click-outside="clickedOutside" class="pagemenu absolute top-8 left-0 rounded-md overflow-y-auto bg-bg shadow-lg z-20 border border-gray-400 w-full" @click.stop.prevent>
      <div v-for="key in comicMetadataKeys" :key="key" class="w-full px-2 py-1">
        <p class="text-xs">
          <strong>{{ key }}</strong>
          : {{ comicMetadata[key] }}
        </p>
      </div>
    </div>

    <div v-if="comicMetadata" id="btn-metadata" class="absolute top-0 left-14 bg-bg text-gray-100 border-b border-l border-r border-gray-400 hover:bg-black-200 cursor-pointer rounded-b-md w-8 h-7 flex items-center justify-center text-center z-20" @mousedown.prevent @click.stop.prevent="clickShowInfoMenu">
      <span class="material-icons text-base pointer-events-none">more</span>
    </div>
    <div v-if="numPages" id="btn-pages" class="absolute top-0 left-3 bg-bg text-gray-100 border-b border-l border-r border-gray-400 hover:bg-black-200 cursor-pointer rounded-b-md w-8 h-7 flex items-center justify-center text-center z-20" @mousedown.prevent @mouseup.prevent @click.stop.prevent="clickShowPageMenu">
      <span class="material-icons text-base pointer-events-none">menu</span>
    </div>
    <div v-if="numPages" class="absolute top-0 right-3 bg-bg text-gray-100 border-b border-l border-r border-gray-400 rounded-b-md px-2 h-7 flex items-center text-center z-20" @click.stop>
      <p class="text-sm">{{ page }} / {{ numPages }}</p>
    </div>

    <div class="overflow-hidden m-auto comicwrapper relative">
      <div class="h-full flex justify-center">
        <img v-if="mainImg" :src="mainImg" class="object-contain comicimg" />
      </div>

      <div v-show="loading" class="w-full h-full absolute top-0 left-0 flex items-center justify-center z-10">
        <ui-loading-indicator />
      </div>
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
    keepProgress: Boolean
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
      pageMenuWidth: 256
    }
  },
  watch: {
    url: {
      immediate: true,
      handler() {
        this.extract()
      }
    }
  },
  computed: {
    userToken() {
      return this.$store.getters['user/getToken']
    },
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
        this.$axios.$patch(`/api/me/progress/${this.serverLibraryItemId}`, payload).catch((error) => {
          console.error('ComicReader.updateProgress failed:', error)
        })
      }
    },
    clickShowInfoMenu() {
      this.showInfoMenu = !this.showInfoMenu
      this.showPageMenu = false
    },
    clickShowPageMenu() {
      this.showPageMenu = !this.showPageMenu
      this.showInfoMenu = false
    },
    clickedOutside(e) {
      if (e.target?.id == 'btn-metadata') return

      if (this.showInfoMenu) this.showInfoMenu = false
    },
    next() {
      if (!this.canGoNext) return
      this.setPage(this.page + 1)
    },
    prev() {
      if (!this.canGoPrev) return
      this.setPage(this.page - 1)
    },
    setPage(page) {
      if (page <= 0 || page > this.numPages) {
        return
      }
      this.showPageMenu = false
      const filename = this.pages[page - 1]
      this.page = page

      this.updateProgress()
      return this.extractFile(filename)
    },
    setLoadTimeout() {
      this.loadTimeout = setTimeout(() => {
        this.loading = true
      }, 150)
    },
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

      var buff = await this.$axios.$get(this.url, {
        responseType: 'blob',
        headers: {
          Authorization: `Bearer ${this.userToken}`
        }
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
      var numbersinpath = basename.match(/\d{1,5}/g)
      if (!numbersinpath || !numbersinpath.length) {
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
      const acceptableImages = ['.jpeg', '.jpg', '.png']
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
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>

<style scoped>
#comic-reader {
  height: calc(100% - 36px);
  max-height: calc(100% - 36px);
  padding-top: 36px;
}
.reader-player-open #comic-reader {
  height: calc(100% - 156px);
  max-height: calc(100% - 156px);
  padding-top: 36px;
}

.pagemenu {
  max-height: calc(100% - 20px);
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