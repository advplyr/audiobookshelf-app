<template>
  <modals-modal v-model="show" width="100%" height="100%" max-width="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <covers-book-cover :library-item="libraryItem" :width="width" raw :book-cover-aspect-ratio="bookCoverAspectRatio" />
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    libraryItem: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {
      width: 0
    }
  },
  watch: {
    show(val) {
      if (val) {
        this.setWidth()
        this.setListeners()
      } else {
        this.removeListeners()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    }
  },
  methods: {
    screenOrientationChange() {
      setTimeout(this.setWidth, 50)
    },
    setListeners() {
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    },
    removeListeners() {
      screen.orientation.removeEventListener('change', this.screenOrientationChange)
    },
    setWidth() {
      if (window.innerHeight > window.innerWidth) {
        this.width = window.innerWidth
      } else {
        this.width = window.innerHeight / this.bookCoverAspectRatio
      }
    }
  },
  mounted() {
    this.setWidth()
  }
}
</script>

<style>
.filter-modal-wrapper {
  max-height: calc(100% - 320px);
}
</style>