<template>
  <div class="w-full relative">
    <div v-if="altViewEnabled" class="px-5 pb-2 pt-3 shelf-loading">
      <p class="font-semibold" :style="{ fontSize: sizeMultiplier + 'rem' }">{{ label }}</p>
    </div>

    <div class="flex items-end px-3 max-w-full overflow-x-auto shelf-scroll-container" :class="altViewEnabled ? '' : 'bookshelfRow'" :style="{ height: shelfHeight + 'px', paddingBottom: entityPaddingBottom + 'px' }">
      <template v-for="(entity, index) in entities">
        <cards-lazy-book-card v-if="type === 'book' || type === 'podcast'" :key="entity.id" :index="index" :book-mount="entity" :width="bookWidth" :height="entityHeight" :book-cover-aspect-ratio="bookCoverAspectRatio" :is-alt-view-enabled="altViewEnabled" class="mx-1 relative item-loading-animation" :class="`loading-delay-${Math.min(index, 12)}`" :style="{ animationDelay: index * 80 + 'ms' }" />
        <cards-lazy-book-card v-if="type === 'episode'" :key="entity.recentEpisode.id" :index="index" :book-mount="entity" :width="bookWidth" :height="entityHeight" :book-cover-aspect-ratio="bookCoverAspectRatio" :is-alt-view-enabled="altViewEnabled" class="mx-1 relative item-loading-animation" :class="`loading-delay-${Math.min(index, 12)}`" :style="{ animationDelay: index * 80 + 'ms' }" />
        <cards-lazy-series-card
          v-else-if="type === 'series'"
          :key="entity.id"
          :index="index"
          :series-mount="entity"
          :width="bookWidth"
          :height="entityHeight"
          :book-cover-aspect-ratio="bookCoverAspectRatio"
          :is-alt-view-enabled="altViewEnabled"
          is-categorized
          class="mx-1 relative item-loading-animation"
          :class="`loading-delay-${Math.min(index, 12)}`"
          :style="{ animationDelay: index * 80 + 'ms' }"
        />
        <cards-author-card v-else-if="type === 'authors'" :key="entity.id" :width="bookWidth" :height="bookHeight" :author="entity" :size-multiplier="sizeMultiplier" class="mx-1 item-loading-animation" :class="`loading-delay-${Math.min(index, 12)}`" :style="{ animationDelay: index * 80 + 'ms' }" />
      </template>
    </div>

    <div v-if="!altViewEnabled" class="absolute text-center categoryPlacardtransform z-30 bottom-0.5 left-4 md:left-8 w-36 rounded-md shelf-loading" style="height: 18px">
      <div class="w-full h-full flex items-center justify-center rounded-sm border shinyBlack">
        <p class="transform text-xs">{{ label }}</p>
      </div>
    </div>
    <div v-if="!altViewEnabled" class="w-full h-1 z-40 bookshelfDivider"></div>
  </div>
</template>

<script>
export default {
  props: {
    label: String,
    type: String,
    entities: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  computed: {
    entityPaddingBottom() {
      if (!this.altViewEnabled) return 0
      if (this.type === 'authors') return 8
      return 15 * this.sizeMultiplier // Consistent padding for all types
    },
    shelfHeight() {
      if (this.altViewEnabled) {
        var extraTitleSpace = this.type === 'authors' ? 5 : 25
        return this.entityHeight + extraTitleSpace * this.sizeMultiplier
      }
      return this.entityHeight + 24 // Original spacing for bookshelf view
    },
    bookWidth() {
      // Use base sizes that match card sizeMultiplier calculations
      if (this.isCoverSquareAspectRatio) return 192 // Base size for square covers
      return 120 // Base size for rectangular covers
    },
    bookHeight() {
      if (this.isCoverSquareAspectRatio) return this.bookWidth
      return this.bookWidth * 1.6
    },
    entityHeight() {
      return this.bookHeight
    },
    sizeMultiplier() {
      var baseSize = this.isCoverSquareAspectRatio ? 192 : 120
      return this.bookWidth / baseSize
    },
    isCoverSquareAspectRatio() {
      return this.bookCoverAspectRatio === 1
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    altViewEnabled() {
      return this.$store.getters['getAltViewEnabled']
    }
  },
  methods: {},
  mounted() {},
  beforeDestroy() {}
}
</script>

<style scoped>
/* Material 3 Expressive Scroll Container */
.shelf-scroll-container {
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-x: contain;
}

/* Enhanced scroll effect for iOS/Android */
.shelf-scroll-container::-webkit-scrollbar {
  display: none;
}

/* Material 3 scroll behavior */
@supports (overscroll-behavior: bounce) {
  .shelf-scroll-container {
    overscroll-behavior-x: auto;
  }
}

/* Material 3 Loading Animations */
.item-loading-animation {
  opacity: 0;
  transform: translateY(24px) scale(0.8);
  animation: materialLoadIn 600ms cubic-bezier(0.05, 0.7, 0.1, 1) forwards;
}

@keyframes materialLoadIn {
  0% {
    opacity: 0;
    transform: translateY(24px) scale(0.8);
  }
  60% {
    opacity: 0.8;
    transform: translateY(-4px) scale(1.02);
  }
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

/* Staggered loading delays for smooth sequential animation */
.loading-delay-0 {
  animation-delay: 0ms;
}
.loading-delay-1 {
  animation-delay: 80ms;
}
.loading-delay-2 {
  animation-delay: 160ms;
}
.loading-delay-3 {
  animation-delay: 240ms;
}
.loading-delay-4 {
  animation-delay: 320ms;
}
.loading-delay-5 {
  animation-delay: 400ms;
}
.loading-delay-6 {
  animation-delay: 480ms;
}
.loading-delay-7 {
  animation-delay: 560ms;
}
.loading-delay-8 {
  animation-delay: 640ms;
}
.loading-delay-9 {
  animation-delay: 720ms;
}
.loading-delay-10 {
  animation-delay: 800ms;
}
.loading-delay-11 {
  animation-delay: 880ms;
}
.loading-delay-12 {
  animation-delay: 960ms;
}

/* Shelf label animation */
.shelf-loading {
  animation: shelfLabelIn 500ms cubic-bezier(0.05, 0.7, 0.1, 1) forwards;
}

@keyframes shelfLabelIn {
  0% {
    opacity: 0;
    transform: translateX(-16px);
  }
  100% {
    opacity: 1;
    transform: translateX(0);
  }
}

/* Reduce motion for users who prefer it */
@media (prefers-reduced-motion: reduce) {
  .item-loading-animation {
    animation: materialLoadInReduced 300ms ease-out forwards;
  }

  @keyframes materialLoadInReduced {
    0% {
      opacity: 0;
    }
    100% {
      opacity: 1;
    }
  }
}
</style>
