<template>
  <div v-if="!author" class="w-full h-full relative flex items-center justify-center bg-bg">
    <ui-loading-indicator />
  </div>
  <div v-else id="author-page-wrapper" class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
    <div class="flex flex-col items-center">
      <!-- Author image -->
      <div class="rounded-full overflow-hidden bg-primary" :style="{ width: imageSize + 'px', height: imageSize + 'px' }">
        <img v-if="authorImageSrc" :src="authorImageSrc" class="w-full h-full object-cover" />
        <div v-else class="w-full h-full flex items-center justify-center">
          <span class="material-symbols text-6xl text-fg-muted">person</span>
        </div>
      </div>

      <!-- Author name -->
      <h1 class="text-2xl font-semibold mt-4 text-center">{{ author.name }}</h1>
      <p v-if="numBooks" class="text-fg-muted text-sm mt-1">{{ numBooks }} {{ numBooks === 1 ? 'Book' : 'Books' }}</p>
    </div>

    <!-- Description / Bio -->
    <div v-if="author.description" class="mt-6 px-4 max-w-2xl mx-auto">
      <p class="text-base text-fg leading-relaxed">{{ author.description }}</p>
    </div>

    <!-- Author's books -->
    <div v-if="libraryItems.length" class="mt-8">
      <h2 class="text-lg font-semibold px-4 mb-4">Books by {{ author.name }}</h2>
      <div class="flex flex-wrap justify-center">
        <div v-for="item in libraryItems" :key="item.id" tabindex="0" :id="`author-book-${item.id}`" class="p-2 cursor-pointer relative" @click="openItem(item)" @keydown.enter="openItem(item)">
          <div :style="{ width: bookWidth + 'px', height: bookHeight + 'px' }" class="bg-primary box-shadow-book rounded-sm relative overflow-hidden">
            <covers-book-cover :library-item="item" :width="bookWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" />
          </div>
          <p class="text-center text-sm mt-1 truncate" :style="{ width: bookWidth + 'px' }">{{ item.media?.metadata?.title }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    const author = await app.$nativeHttp.get(`/api/authors/${params.id}?include=items`).catch((error) => {
      console.error('Failed to load author', error)
      return false
    })

    if (!author) {
      return redirect('/bookshelf')
    }

    return { author }
  },
  data() {
    return {}
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    imageSize() {
      return Math.min(window.innerWidth * 0.3, 200)
    },
    bookWidth() {
      const cols = window.innerWidth >= 1280 ? 8 : window.innerWidth >= 640 ? 4 : 2
      return Math.min((window.innerWidth - 64) / cols, 150)
    },
    bookHeight() {
      return this.bookWidth * this.bookCoverAspectRatio
    },
    authorImageSrc() {
      if (!this.author?.imagePath) return null
      const serverAddress = this.$store.getters['user/getServerAddress']
      if (!serverAddress) return null
      const urlQuery = new URLSearchParams({ ts: this.author.updatedAt || '' })
      if (this.$store.getters.getDoesServerImagesRequireToken) {
        urlQuery.append('token', this.$store.getters['user/getToken'])
      }
      return `${serverAddress}/api/authors/${this.author.id}/image?${urlQuery.toString()}`
    },
    libraryItems() {
      return this.author?.libraryItems || []
    },
    numBooks() {
      return this.libraryItems.length || this.author?.numBooks || 0
    }
  },
  methods: {
    openItem(item) {
      this.$router.push(`/item/${item.id}`)
    }
  },
  mounted() {}
}
</script>
