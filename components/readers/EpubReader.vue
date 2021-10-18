<template>
  <div id="epub-frame" class="w-full">
    <div id="viewer" class="border border-gray-100 bg-white shadow-md h-full w-full"></div>
    <div class="fixed bottom-0 left-0 h-8 w-full bg-bg px-2 flex items-center">
      <p class="text-xs">epub</p>
      <div class="flex-grow" />

      <p class="text-sm">{{ progress }}%</p>
    </div>
  </div>
</template>

<script>
import ePub from 'epubjs'

export default {
  props: {
    url: String
  },
  data() {
    return {
      book: null,
      rendition: null,
      chapters: [],
      title: '',
      author: '',
      progress: 0,
      hasNext: true,
      hasPrev: false
    }
  },
  computed: {},
  methods: {
    prev() {
      if (this.rendition) {
        this.rendition.prev()
      }
    },
    next() {
      if (this.rendition) {
        this.rendition.next()
      }
    },
    keyUp() {
      if ((e.keyCode || e.which) == 37) {
        this.prev()
      } else if ((e.keyCode || e.which) == 39) {
        this.next()
      }
    },
    initEpub() {
      var book = ePub(this.url)
      this.book = book

      this.rendition = book.renderTo('viewer', {
        width: window.innerWidth,
        height: window.innerHeight - 64,
        snap: true,
        manager: 'continuous',
        flow: 'paginated'
      })
      var displayed = this.rendition.display()

      book.ready
        .then(() => {
          console.log('Book ready')
          return book.locations.generate(1600)
        })
        .then((locations) => {
          // console.log('Loaded locations', locations)
          // Wait for book to be rendered to get current page
          displayed.then(() => {
            // Get the current CFI
            var currentLocation = this.rendition.currentLocation()
            if (!currentLocation.start) {
              console.error('No Start', currentLocation)
            } else {
              var currentPage = book.locations.percentageFromCfi(currentLocation.start.cfi)
              // console.log('current page', currentPage)
            }
          })
        })

      book.loaded.navigation.then((toc) => {
        var _chapters = []
        toc.forEach((chapter) => {
          _chapters.push(chapter)
        })
        this.chapters = _chapters
      })
      book.loaded.metadata.then((metadata) => {
        // this.author = metadata.creator
        // this.title = metadata.title
      })

      // const spine_get = book.spine.get.bind(book.spine)
      // book.spine.get = function (target) {
      //   var t = spine_get(target)
      //   console.log(t, target)
      //   // while (t == null && target.includes('#')) {
      //   //   target = target.split('#')[0]
      //   //   t = spine_get(target)
      //   // }
      //   return t
      // }

      this.rendition.on('keyup', this.keyUp)

      this.rendition.on('relocated', (location) => {
        var percent = book.locations.percentageFromCfi(location.start.cfi)
        this.progress = Math.floor(percent * 100)

        this.hasNext = !location.atEnd
        this.hasPrev = !location.atStart
      })
    }
  },
  mounted() {
    this.initEpub()
  }
}
</script>

<style>
#epub-frame {
  height: calc(100% - 32px);
  max-height: calc(100% - 32px);
  overflow: hidden;
}
</style>