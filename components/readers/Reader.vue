<template>
  <div v-if="show" class="absolute top-0 left-0 w-full h-full bg-bg z-40 pt-8">
    <div class="h-8 w-full bg-primary flex items-center px-2 fixed top-0 left-0 z-30 box-shadow-sm">
      <p class="w-5/6 truncate">{{ title }}</p>
      <div class="flex-grow" />
      <span class="material-icons text-xl text-white" @click.stop="show = false">close</span>
    </div>
    <component v-if="readerComponentName" ref="readerComponent" :is="readerComponentName" :url="ebookUrl" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      ebookType: null,
      ebookUrl: null,
      touchstartX: 0,
      touchendX: 0
    }
  },
  watch: {
    show: {
      handler(newVal) {
        if (newVal) {
          this.init()
          this.registerListeners()
        } else {
          this.unregisterListeners()
        }
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.showReader
      },
      set(val) {
        this.$store.commit('setShowReader', val)
      }
    },
    title() {
      return this.selectedBook ? this.selectedBook.book.title : null
    },
    selectedBook() {
      return this.$store.state.selectedBook
    },
    readerComponentName() {
      if (this.ebookType === 'epub') return 'readers-epub-reader'
      else if (this.ebookType === 'mobi') return 'readers-mobi-reader'
      else if (this.ebookType === 'comic') return 'readers-comic-reader'
      return null
    },
    ebook() {
      if (!this.selectedBook || !this.selectedBook.ebooks || !this.selectedBook.ebooks.length) return null
      return this.selectedBook.ebooks[0]
    },
    ebookPath() {
      return this.ebook ? this.ebook.path : null
    },
    folderId() {
      return this.selectedBook ? this.selectedBook.folderId : null
    },
    libraryId() {
      return this.selectedBook ? this.selectedBook.libraryId : null
    },
    ebookRelPath() {
      return `/ebook/${this.libraryId}/${this.folderId}/${this.ebookPath}`
    }
  },
  methods: {
    init() {
      if (!this.ebook) {
        console.error('No ebook for book', this.selectedBook)
        return
      }
      if (this.ebook.ext === '.epub') {
        this.ebookType = 'epub'
      } else if (this.ebook.ext === '.mobi' || this.ebook.ext === '.azw3') {
        this.ebookType = 'mobi'
      } else if (this.ebook.ext === '.cbr' || this.ebook.ext === '.cbz') {
        this.ebookType = 'comic'
      }

      var serverUrl = this.$store.state.serverUrl
      this.ebookUrl = `${serverUrl}${this.ebookRelPath}`
    },
    next() {
      if (this.$refs.readerComponent && this.$refs.readerComponent.next) {
        this.$refs.readerComponent.next()
      }
    },
    prev() {
      if (this.$refs.readerComponent && this.$refs.readerComponent.prev) {
        this.$refs.readerComponent.prev()
      }
    },
    handleGesture() {
      if (this.touchendX < this.touchstartX) {
        console.log('swiped left')

        this.next()
      }
      if (this.touchendX > this.touchstartX) {
        console.log('swiped right')
        this.prev()
      }
    },
    touchstart(e) {
      this.touchstartX = e.changedTouches[0].screenX
    },
    touchend(e) {
      this.touchendX = e.changedTouches[0].screenX
      this.handleGesture()
    },
    registerListeners() {
      document.body.addEventListener('touchstart', this.touchstart)
      document.body.addEventListener('touchend', this.touchend)
    },
    unregisterListeners() {
      document.body.removeEventListener('touchstart', this.touchstart)
      document.body.removeEventListener('touchend', this.touchend)
    }
  },
  beforeDestroy() {
    this.unregisterListeners()
  }
}
</script>