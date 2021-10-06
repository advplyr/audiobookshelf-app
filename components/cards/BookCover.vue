<template>
  <div class="relative rounded-sm overflow-hidden" :style="{ height: width * 1.6 + 'px', width: width + 'px', maxWidth: width + 'px', minWidth: width + 'px' }">
    <div class="w-full h-full relative">
      <div class="bg-primary absolute top-0 left-0 w-full h-full">
        <!-- Blurred background for covers that dont fill -->
        <div v-if="showCoverBg" class="w-full h-full z-0" ref="coverBg" />

        <!-- Image Loading indicator -->
        <div v-if="!isImageLoaded" class="w-full h-full flex items-center justify-center text-white">
          <svg class="animate-spin w-12 h-12" viewBox="0 0 24 24">
            <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
          </svg>
        </div>
      </div>
      <img ref="cover" :src="fullCoverUrl" @error="imageError" @load="imageLoaded" class="w-full h-full absolute top-0 left-0" :class="showCoverBg ? 'object-contain' : 'object-cover'" />
    </div>

    <div v-if="imageFailed" class="absolute top-0 left-0 right-0 bottom-0 w-full h-full bg-red-100" :style="{ padding: placeholderCoverPadding + 'rem' }">
      <div class="w-full h-full border-2 border-error flex flex-col items-center justify-center">
        <img src="/Logo.png" class="mb-2" :style="{ height: 64 * sizeMultiplier + 'px' }" />
        <p class="text-center font-book text-error" :style="{ fontSize: titleFontSize + 'rem' }">Invalid Cover</p>
      </div>
    </div>

    <div v-if="!hasCover" class="absolute top-0 left-0 right-0 bottom-0 w-full h-full flex items-center justify-center" :style="{ padding: placeholderCoverPadding + 'rem' }">
      <div>
        <p class="text-center font-book" style="color: rgb(247 223 187)" :style="{ fontSize: titleFontSize + 'rem' }">{{ titleCleaned }}</p>
      </div>
    </div>
    <div v-if="!hasCover" class="absolute left-0 right-0 w-full flex items-center justify-center" :style="{ padding: placeholderCoverPadding + 'rem', bottom: authorBottom + 'rem' }">
      <p class="text-center font-book" style="color: rgb(247 223 187); opacity: 0.75" :style="{ fontSize: authorFontSize + 'rem' }">{{ authorCleaned }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    audiobook: {
      type: Object,
      default: () => {}
    },
    downloadCover: String,
    authorOverride: String,
    width: {
      type: Number,
      default: 120
    }
  },
  data() {
    return {
      imageFailed: false,
      showCoverBg: false,
      isImageLoaded: false
    }
  },
  watch: {
    cover() {
      this.imageFailed = false
    }
  },
  computed: {
    userToken() {
      return this.$store.getters['user/getToken']
    },
    book() {
      return this.audiobook.book || {}
    },
    title() {
      return this.book.title || 'No Title'
    },
    titleCleaned() {
      if (this.title.length > 60) {
        return this.title.slice(0, 57) + '...'
      }
      return this.title
    },
    author() {
      if (this.authorOverride) return this.authorOverride
      return this.book.author || 'Unknown'
    },
    authorCleaned() {
      if (this.author.length > 30) {
        return this.author.slice(0, 27) + '...'
      }
      return this.author
    },
    placeholderUrl() {
      return '/book_placeholder.jpg'
    },
    serverUrl() {
      return this.$store.state.serverUrl
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    fullCoverUrl() {
      if (this.downloadCover) return this.downloadCover
      else if (!this.networkConnected) return this.placeholderUrl
      return this.$store.getters['audiobooks/getBookCoverSrc'](this.audiobook)
      // if (this.cover.startsWith('http')) return this.cover
      // var _clean = this.cover.replace(/\\/g, '/')
      // if (_clean.startsWith('/local')) {
      //   var _cover = process.env.NODE_ENV !== 'production' && process.env.PROD !== '1' ? _clean.replace('/local', '') : _clean
      //   return `${this.$store.state.serverUrl}${_cover}?token=${this.userToken}&ts=${Date.now()}`
      // } else if (_clean.startsWith('/metadata')) {
      //   return `${this.$store.state.serverUrl}${_clean}?token=${this.userToken}&ts=${Date.now()}`
      // }
      // return _clean
    },
    cover() {
      return this.book.cover || this.placeholderUrl
    },
    hasCover() {
      if (!this.networkConnected && !this.downloadCover) return false
      return !!this.book.cover
    },
    sizeMultiplier() {
      return this.width / 120
    },
    titleFontSize() {
      return 0.75 * this.sizeMultiplier
    },
    authorFontSize() {
      return 0.6 * this.sizeMultiplier
    },
    placeholderCoverPadding() {
      return 0.8 * this.sizeMultiplier
    },
    authorBottom() {
      return 0.75 * this.sizeMultiplier
    }
  },
  methods: {
    setCoverBg() {
      if (this.$refs.coverBg) {
        this.$refs.coverBg.style.backgroundImage = `url("${this.fullCoverUrl}")`
        this.$refs.coverBg.style.backgroundSize = 'cover'
        this.$refs.coverBg.style.backgroundPosition = 'center'
        this.$refs.coverBg.style.opacity = 0.25
        this.$refs.coverBg.style.filter = 'blur(1px)'
      }
    },
    hideCoverBg() {},
    imageLoaded() {
      if (this.$refs.cover && this.cover !== this.placeholderUrl) {
        var { naturalWidth, naturalHeight } = this.$refs.cover
        var aspectRatio = naturalHeight / naturalWidth
        var arDiff = Math.abs(aspectRatio - 1.6)

        // If image aspect ratio is <= 1.45 or >= 1.75 then use cover bg, otherwise stretch to fit
        if (arDiff > 0.15) {
          this.showCoverBg = true
          this.$nextTick(this.setCoverBg)
        } else {
          this.showCoverBg = false
        }
      }
      this.isImageLoaded = true
    },
    imageError(err) {
      this.imageFailed = true
      console.error('ImgError', err, `SET IMAGE FAILED ${this.imageFailed}`)
    }
  }
}
</script>