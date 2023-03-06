<template>
  <div id="comic-reader" class="w-full h-full relative">
    <div v-show="showPageMenu" v-click-outside="clickOutsideObj" class="pagemenu absolute top-12 right-16 rounded-md overflow-y-auto bg-bg shadow-lg z-20 border border-gray-400 w-52">
      <div v-for="(file, index) in pages" :key="file" class="w-full cursor-pointer hover:bg-black-200 px-2 py-1" :class="page === index ? 'bg-black-200' : ''" @click.stop="setPage(index)">
        <p class="text-sm truncate">{{ file }}</p>
      </div>
    </div>
    <div v-show="showInfoMenu" v-click-outside="clickedOutsideInfoMenu" class="pagemenu absolute top-12 right-0 rounded-md overflow-y-auto bg-bg shadow-lg z-20 border border-gray-400 w-full" @click.stop.prevent>
      <div v-for="key in comicMetadataKeys" :key="key" class="w-full px-2 py-1">
        <p class="text-xs">
          <strong>{{ key }}</strong>
          : {{ comicMetadata[key] }}
        </p>
      </div>
    </div>

    <div v-if="comicMetadata" class="absolute top-0 right-3 bg-bg text-gray-100 border-b border-l border-r border-gray-400 hover:bg-black-200 cursor-pointer rounded-b-md w-10 h-9 flex items-center justify-center text-center z-20" @mousedown.prevent @click.stop.prevent="clickShowInfoMenu">
      <span class="material-icons text-lg">more</span>
    </div>
    <div class="absolute top-0 right-16 bg-bg text-gray-100 border-b border-l border-r border-gray-400 hover:bg-black-200 cursor-pointer rounded-b-md w-10 h-9 flex items-center justify-center text-center z-20" @mousedown.prevent @mouseup.prevent @click.stop.prevent="clickShowPageMenu">
      <span class="material-icons text-lg">menu</span>
    </div>
    <div class="absolute top-0 left-3 bg-bg text-gray-100 border-b border-l border-r border-gray-400 rounded-b-md px-2 h-9 flex items-center text-center z-20" @click.stop>
      <p class="font-mono">{{ page + 1 }} / {{ numPages }}</p>
    </div>

    <div class="overflow-hidden m-auto comicwrapper relative">
      <div class="h-full flex justify-center">
        <img v-if="mainImg" :src="mainImg" class="object-contain comicimg" />
      </div>

      <div v-show="loading" class="w-full h-full absolute top-0 left-0 flex items-center justify-center z-10">
        <ui-loading-indicator />
      </div>
    </div>
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
    url: String
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
      clickOutsideObj: {
        handler: this.clickedOutside,
        events: ['mousedown'],
        isActive: true
      }
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
    comicMetadataKeys() {
      return this.comicMetadata ? Object.keys(this.comicMetadata) : []
    },
    canGoNext() {
      return this.page < this.numPages - 1
    },
    canGoPrev() {
      return this.page > 0
    }
  },
  methods: {
    clickShowInfoMenu() {
      this.showInfoMenu = !this.showInfoMenu
      this.showPageMenu = false
    },
    clickShowPageMenu() {
      this.showPageMenu = !this.showPageMenu
      this.showInfoMenu = false
    },
    clickedOutside() {
      this.showPageMenu = false
    },
    clickedOutsideInfoMenu() {
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
    setPage(index) {
      if (index < 0 || index > this.numPages - 1) {
        return
      }
      this.showPageMenu = false
      const filename = this.pages[index]
      this.page = index
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
      console.log('Extracting', this.url)

      var buff = await this.$axios.$get(this.url, {
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

      if (this.pages.length) {
        this.loading = false
        await this.setPage(0)
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
      console.log('extracting xml filename', filename)
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
  max-height: calc(100% - 80px);
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