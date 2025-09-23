<template>
  <div ref="wrapper" :class="`rounded-${rounded}`" class="w-full h-full bg-primary overflow-hidden flex items-center justify-center cursor-pointer" @click="clickAuthor">
    <div v-if="!imagePath" class="w-full h-full flex items-center justify-center bg-primary">
      <span class="material-symbols text-on-primary" style="font-size: 40%">person</span>
    </div>
    <div v-else class="w-full h-full relative">
      <div v-if="showCoverBg" class="cover-bg absolute" :style="{ backgroundImage: `url(${imgSrc})` }" />
      <img ref="img" :src="imgSrc" @load="imageLoaded" class="absolute top-0 left-0 h-full w-full" :class="coverContain ? 'object-contain' : 'object-cover'" />
    </div>
  </div>
</template>

<script>
export default {
  props: {
    author: {
      type: Object,
      default: () => {}
    },
    rounded: {
      type: String,
      default: 'lg'
    }
  },
  data() {
    return {
      showCoverBg: false,
      coverContain: true
    }
  },
  computed: {
    _author() {
      return this.author || {}
    },
    authorId() {
      return this._author.id
    },
    imagePath() {
      return this._author.imagePath
    },
    updatedAt() {
      return this._author.updatedAt
    },
    serverAddress() {
      return this.$store.getters['user/getServerAddress']
    },
    imgSrc() {
      if (!this.imagePath || !this.serverAddress) return null
      const urlQuery = new URLSearchParams({ ts: this.updatedAt })
      if (this.$store.getters.getDoesServerImagesRequireToken) {
        urlQuery.append('token', this.$store.getters['user/getToken'])
      }
      if (process.env.NODE_ENV !== 'production' && this.serverAddress.startsWith('http://192.168')) {
        // Testing
        return `http://localhost:3333/api/authors/${this.authorId}/image?${urlQuery.toString()}`
      }
      return `${this.serverAddress}/api/authors/${this.authorId}/image?${urlQuery.toString()}`
    }
  },
  methods: {
    imageLoaded() {
      var aspectRatio = 1.25
      if (this.$refs.wrapper) {
        aspectRatio = this.$refs.wrapper.clientHeight / this.$refs.wrapper.clientWidth
      }
      if (this.$refs.img) {
        var { naturalWidth, naturalHeight } = this.$refs.img
        var imgAr = naturalHeight / naturalWidth
        var arDiff = Math.abs(imgAr - aspectRatio)
        if (arDiff > 0.15) {
          this.showCoverBg = true
        } else {
          this.showCoverBg = false
          this.coverContain = false
        }
      }
      this.$emit('imageLoaded')
    },
    clickAuthor() {
      this.$router.push(`/bookshelf/library?filter=authors.${this.$encode(this.authorId)}`)
    }
  },
  mounted() {}
}
</script>
