<template>
  <div>
    <div :style="{ width: width + 'px', height: height + 'px' }" class="material-3-card rounded-2xl bg-surface-container cursor-pointer shadow-elevation-1 hover:shadow-elevation-3 transition-all duration-300 ease-expressive state-layer overflow-hidden relative" @click="clickCard">
      <!-- Author image container - fills entire card -->
      <div class="cover-container absolute inset-0 overflow-hidden z-0">
        <!-- Loading placeholder with author icon -->
        <div v-show="author && !imageReady" class="absolute inset-0 flex items-center justify-center bg-surface-container z-10">
          <span class="material-symbols text-on-surface-variant" :style="{ fontSize: sizeMultiplier * 2.5 + 'rem' }">person</span>
        </div>

        <!-- Author image -->
        <covers-author-image v-if="author" :author="author" class="w-full h-full transition-opacity duration-300" :style="{ opacity: imageReady ? 1 : 0 }" @imageLoaded="imageLoaded" />
      </div>

      <!-- Author name & num books overlay with enhanced visibility -->
      <div v-if="!searching && !nameBelow" class="absolute bottom-2 left-2 z-50 max-w-[80%]">
        <div class="bg-surface-container bg-opacity-95 backdrop-blur-md rounded-lg p-2 shadow-elevation-2 border border-outline-variant border-opacity-20">
          <p class="text-on-surface font-bold truncate drop-shadow-sm" :style="{ fontSize: sizeMultiplier * 0.7 + 'rem' }">{{ name }}</p>
          <p class="text-on-surface-variant font-medium truncate drop-shadow-sm" :style="{ fontSize: sizeMultiplier * 0.6 + 'rem' }">{{ numBooks }} {{ $strings.LabelBooks }}</p>
        </div>
      </div>

      <!-- Loading spinner with enhanced visibility -->
      <div v-show="searching" class="absolute top-0 left-0 z-40 w-full h-full bg-surface-dim bg-opacity-80 backdrop-blur-sm flex items-center justify-center">
        <widgets-loading-spinner size="" />
      </div>
    </div>

    <!-- Name below card with improved styling -->
    <div v-show="nameBelow" class="w-full py-2 px-2">
      <p class="text-center font-bold truncate text-on-surface" :style="{ fontSize: sizeMultiplier * 0.75 + 'rem' }">{{ name }}</p>
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
    width: Number,
    height: Number,
    sizeMultiplier: {
      type: Number,
      default: 1
    },
    nameBelow: Boolean
  },
  data() {
    return {
      searching: false,
      imageReady: false
    }
  },
  computed: {
    _author() {
      return this.author || {}
    },
    authorId() {
      return this._author.id
    },
    name() {
      return this._author.name || ''
    },
    numBooks() {
      return this._author.numBooks || 0
    }
  },
  methods: {
    imageLoaded() {
      this.imageReady = true
    },
    clickCard() {
      if (!this.author) return
      this.$router.push(`/bookshelf/library?filter=authors.${this.$encode(this.authorId)}`)
    }
  },
  mounted() {}
}
</script>

<style scoped>
/* Material 3 Expressive Author Card Styles */
.material-3-card {
  transition: box-shadow 300ms cubic-bezier(0.2, 0, 0, 1), transform 300ms cubic-bezier(0.2, 0, 0, 1);
}

.material-3-card::before {
  content: '';
  position: absolute;
  border-radius: inherit;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: transparent;
  transition: background-color 200ms cubic-bezier(0.2, 0, 0, 1);
  pointer-events: none;
  z-index: 1;
}

.material-3-card:hover {
  transform: translateY(-2px);
}

.material-3-card:hover::before {
  background-color: rgba(var(--md-sys-color-on-surface), 0.08);
}

.material-3-card:active {
  transform: translateY(0px);
}

.material-3-card:active::before {
  background-color: rgba(var(--md-sys-color-on-surface), 0.12);
}

/* Ensure content stays above state layer, but exclude cover container and absolutely positioned elements */
.material-3-card > *:not(.cover-container):not(.absolute) {
  position: relative;
  z-index: 2;
}

/* Force cover images to fit container */
.material-3-card .covers-author-image,
.material-3-card .covers-author-image > div,
.material-3-card .covers-author-image img,
.material-3-card img {
  object-fit: cover;
  object-position: center center;
}
</style>

/* Force square aspect ratio for author images */
.material-3-card .covers-author-image,
.material-3-card img {
  aspect-ratio: 1 / 1;
  object-fit: cover;
  object-position: center center;
}

/* Enhanced text visibility */
.drop-shadow-sm {
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.4));
}

/* Ensure overlays are always visible */
.bg-opacity-95 {
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

/* Expressive easing definition */
.ease-expressive {
  transition-timing-function: cubic-bezier(0.2, 0, 0, 1);
}
</style>
