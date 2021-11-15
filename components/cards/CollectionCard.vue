<template>
  <div class="relative">
    <div class="rounded-sm h-full relative" @click="clickCard">
      <nuxt-link :to="groupTo" class="cursor-pointer">
        <div class="w-full relative bg-primary" :style="{ height: coverHeight + 'px', width: coverWidth + 'px' }">
          <cards-collection-cover ref="groupcover" :book-items="bookItems" :width="coverWidth" :height="coverHeight" />
        </div>
      </nuxt-link>
    </div>

    <div class="categoryPlacard absolute z-30 left-0 right-0 mx-auto -bottom-5 h-5 rounded-md font-book text-center" :style="{ width: Math.min(160, coverWidth) + 'px' }">
      <div class="w-full h-full shinyBlack flex items-center justify-center rounded-sm border" :style="{ padding: `0rem ${0.8 * sizeMultiplier}rem` }">
        <p class="truncate pt-px" :style="{ fontSize: labelFontSize + 'rem' }">{{ collectionName }}</p>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    collection: {
      type: Object,
      default: () => null
    },
    width: {
      type: Number,
      default: 120
    },
    paddingY: {
      type: Number,
      default: 24
    }
  },
  data() {
    return {}
  },
  watch: {
    width(newVal) {
      this.$nextTick(() => {
        if (this.$refs.groupcover && this.$refs.groupcover.init) {
          this.$refs.groupcover.init()
        }
      })
    }
  },
  computed: {
    labelFontSize() {
      if (this.coverWidth < 160) return 0.7
      return 0.75
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    _collection() {
      return this.collection || {}
    },
    groupTo() {
      return `/collection/${this._collection.id}`
    },
    coverWidth() {
      return this.width * 2
    },
    coverHeight() {
      return this.width * 1.6
    },
    sizeMultiplier() {
      return this.width / 120
    },
    paddingX() {
      return 16 * this.sizeMultiplier
    },
    bookItems() {
      return this._collection.books || []
    },
    collectionName() {
      return this._collection.name || 'No Name'
    }
  },
  methods: {
    clickCard() {
      this.$emit('click', this.collection)
    }
  }
}
</script>