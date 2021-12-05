<template>
  <div ref="wrapper" :style="{ height: height + 'px', width: width + 'px' }" class="relative">
    <div v-if="noValidCovers" class="absolute top-0 left-0 w-full h-full flex items-center justify-center box-shadow-book" :style="{ padding: `${sizeMultiplier}rem` }">
      <p :style="{ fontSize: sizeMultiplier + 'rem' }">{{ name }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    id: String,
    name: String,
    bookItems: {
      type: Array,
      default: () => []
    },
    width: Number,
    height: Number,
    bookCoverAspectRatio: Number
  },
  data() {
    return {
      noValidCovers: false,
      coverDiv: null,
      isHovering: false,
      coverWrapperEl: null,
      coverImageEls: [],
      coverWidth: 0,
      offsetIncrement: 0,
      isFannedOut: false,
      isDetached: false,
      isAttaching: false,
      windowWidth: 0,
      isInit: false
    }
  },
  watch: {
    bookItems: {
      immediate: true,
      handler(newVal) {
        if (newVal) {
          // ensure wrapper is initialized
          this.$nextTick(this.init)
        }
      }
    }
  },
  computed: {
    sizeMultiplier() {
      if (this.bookCoverAspectRatio === 1) return this.width / (100 * 1.6 * 2)
      return this.width / 200
    },
    store() {
      return this.$store || this.$nuxt.$store
    },
    router() {
      return this.$router || this.$nuxt.$router
    }
  },
  methods: {
    detchCoverWrapper() {
      if (!this.coverWrapperEl || !this.$refs.wrapper || this.isDetached) return

      this.coverWrapperEl.remove()

      this.isDetached = true
      document.body.appendChild(this.coverWrapperEl)
      this.coverWrapperEl.addEventListener('mouseleave', this.mouseleaveCover)

      this.coverWrapperEl.style.position = 'absolute'
      this.coverWrapperEl.style.zIndex = 40

      this.updatePosition()
    },
    attachCoverWrapper() {
      if (!this.coverWrapperEl || !this.$refs.wrapper || !this.isDetached) return

      this.coverWrapperEl.remove()
      this.coverWrapperEl.style.position = 'relative'
      this.coverWrapperEl.style.left = 'unset'
      this.coverWrapperEl.style.top = 'unset'
      this.coverWrapperEl.style.width = this.$refs.wrapper.clientWidth + 'px'

      this.$refs.wrapper.appendChild(this.coverWrapperEl)

      this.isDetached = false
    },
    updatePosition() {
      var rect = this.$refs.wrapper.getBoundingClientRect()
      this.coverWrapperEl.style.top = rect.top + window.scrollY + 'px'

      this.coverWrapperEl.style.left = rect.left + window.scrollX + 4 + 'px'

      this.coverWrapperEl.style.height = rect.height + 'px'
      this.coverWrapperEl.style.width = rect.width + 'px'
    },
    getCoverUrl(book) {
      return this.store.getters['audiobooks/getBookCoverSrc'](book, '')
    },
    async buildCoverImg(coverData, bgCoverWidth, offsetLeft, zIndex, forceCoverBg = false) {
      var src = coverData.coverUrl

      var showCoverBg =
        forceCoverBg ||
        (await new Promise((resolve) => {
          var image = new Image()

          image.onload = () => {
            var { naturalWidth, naturalHeight } = image
            var aspectRatio = naturalHeight / naturalWidth
            var arDiff = Math.abs(aspectRatio - this.bookCoverAspectRatio)

            // If image aspect ratio is <= 1.45 or >= 1.75 then use cover bg, otherwise stretch to fit
            if (arDiff > 0.15) {
              resolve(true)
            } else {
              resolve(false)
            }
          }
          image.onerror = (err) => {
            console.error(err)
            resolve(false)
          }
          image.src = src
        }))

      var imgdiv = document.createElement('div')
      imgdiv.style.height = this.height + 'px'
      imgdiv.style.width = bgCoverWidth + 'px'
      imgdiv.style.left = offsetLeft + 'px'
      imgdiv.style.zIndex = zIndex
      imgdiv.dataset.audiobookId = coverData.id
      imgdiv.dataset.volumeNumber = coverData.volumeNumber || ''
      imgdiv.className = 'absolute top-0 box-shadow-book transition-transform'
      imgdiv.style.boxShadow = '4px 0px 4px #11111166'
      // imgdiv.style.transform = 'skew(0deg, 15deg)'

      if (showCoverBg) {
        var coverbgwrapper = document.createElement('div')
        coverbgwrapper.className = 'absolute top-0 left-0 w-full h-full overflow-hidden rounded-sm bg-primary'

        var coverbg = document.createElement('div')
        coverbg.className = 'absolute cover-bg'
        coverbg.style.backgroundImage = `url("${src}")`

        coverbgwrapper.appendChild(coverbg)
        imgdiv.appendChild(coverbgwrapper)
      }

      var img = document.createElement('img')
      img.src = src
      img.className = 'absolute top-0 left-0 w-full h-full'
      img.style.objectFit = showCoverBg ? 'contain' : 'cover'

      imgdiv.appendChild(img)
      return imgdiv
    },
    async init() {
      if (this.isInit) return
      this.isInit = true

      if (this.coverDiv) {
        this.coverDiv.remove()
        this.coverDiv = null
      }
      var validCovers = this.bookItems
        .map((bookItem) => {
          return {
            id: bookItem.id,
            volumeNumber: bookItem.book ? bookItem.book.volumeNumber : null,
            coverUrl: this.getCoverUrl(bookItem)
          }
        })
        .filter((b) => b.coverUrl !== '')
      if (!validCovers.length) {
        this.noValidCovers = true
        return
      }
      this.noValidCovers = false

      var coverWidth = this.width
      var widthPer = this.width
      if (validCovers.length > 1) {
        coverWidth = this.height / this.bookCoverAspectRatio
        widthPer = (this.width - coverWidth) / (validCovers.length - 1)
      }
      this.coverWidth = coverWidth
      this.offsetIncrement = widthPer

      var outerdiv = document.createElement('div')
      outerdiv.id = `group-cover-${this.id || this.$encode(this.name)}`
      this.coverWrapperEl = outerdiv
      outerdiv.className = 'w-full h-full relative box-shadow-book'

      var coverImageEls = []
      var offsetLeft = 0
      for (let i = 0; i < validCovers.length; i++) {
        offsetLeft = widthPer * i
        var zIndex = validCovers.length - i
        var img = await this.buildCoverImg(validCovers[i], coverWidth, offsetLeft, zIndex, validCovers.length === 1)
        outerdiv.appendChild(img)
        coverImageEls.push(img)
      }

      this.coverImageEls = coverImageEls

      if (this.$refs.wrapper) {
        this.coverDiv = outerdiv
        this.$refs.wrapper.appendChild(outerdiv)
      }
    }
  },
  mounted() {
    this.windowWidth = window.innerWidth
  },
  beforeDestroy() {
    if (this.coverWrapperEl) this.coverWrapperEl.remove()
    if (this.coverImageEls && this.coverImageEls.length) {
      this.coverImageEls.forEach((el) => el.remove())
    }
    if (this.coverDiv) this.coverDiv.remove()
  }
}
</script>