<template>
  <div ref="wrapper" class="modal modal-bg w-screen fixed bottom-0 left-0 flex items-center justify-center z-50" :class="threeQuartersScreen ? 'h-[75vh] min-h-[400px]' : 'h-screen'" @click.stop @touchstart.stop @touchend.stop>
    <div ref="content" class="relative text-on-surface h-full w-full bg-surface-dynamic">
      <slot />
    </div>
  </div>
</template>

<script>
export default {
  props: {
    value: Boolean,
    processing: Boolean,
    threeQuartersScreen: Boolean
  },
  data() {
    return {
      el: null,
      content: null
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.setShow()
      } else {
        this.setHide()
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
    }
  },
  methods: {
    setShow() {
      this.$store.commit('globals/setIsModalOpen', true)

      document.body.appendChild(this.el)
      setTimeout(() => {
        this.content.style.transform = 'translateY(0)'
      }, 10)
      document.documentElement.classList.add('modal-open')
    },
    setHide() {
      this.$store.commit('globals/setIsModalOpen', false)

      this.content.style.transform = 'translateY(100vh)'
      setTimeout(() => {
        this.el.remove()
        document.documentElement.classList.remove('modal-open')
      }, 250)
    },
    closeModalEvt() {
      console.log('Close modal event')
      this.show = false
    }
  },
  mounted() {
    this.$eventBus.$on('close-modal', this.closeModalEvt)
    this.el = this.$refs.wrapper
    this.content = this.$refs.content
    this.content.style.transform = 'translateY(100vh)'
    this.content.style.transition = 'transform 0.25s cubic-bezier(0.16, 1, 0.3, 1)'
    this.el.remove()
  },
  beforeDestroy() {
    this.$eventBus.$off('close-modal', this.closeModalEvt)
  }
}
</script>
