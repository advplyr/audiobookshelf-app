<template>
  <div ref="box" class="inline-block" @click.stop="click">
    <slot />
  </div>
</template>

<script>
export default {
  props: {
    text: {
      type: [String, Number],
      required: true
    },
    direction: {
      type: String,
      default: 'right'
    },
    disabled: Boolean
  },
  data() {
    return {
      tooltip: null,
      tooltipTextEl: null,
      tooltipId: null,
      isShowing: false,
      hideTimeout: null
    }
  },
  watch: {
    text() {
      this.updateText()
    },
    disabled(newVal) {
      if (newVal && this.isShowing) {
        this.hideTooltip()
      }
    }
  },
  methods: {
    updateText() {
      if (this.tooltipTextEl) {
        this.tooltipTextEl.innerHTML = this.text
      }
    },
    createTooltip() {
      if (!this.$refs.box) return
      const tooltip = document.createElement('div')
      this.tooltipId = String(Math.floor(Math.random() * 10000))
      tooltip.id = this.tooltipId
      tooltip.className = 'fixed inset-0 w-screen h-screen bg-black/25 text-xs flex items-center justify-center p-2'
      tooltip.style.zIndex = 100
      tooltip.style.backgroundColor = 'rgba(0,0,0,0.85)'

      tooltip.addEventListener('click', this.hideTooltip)

      const innerDiv = document.createElement('div')
      innerDiv.className = 'w-full p-2 border border-white/20 pointer-events-none text-white bg-primary'
      innerDiv.innerHTML = this.text
      tooltip.appendChild(innerDiv)

      this.tooltipTextEl = innerDiv
      this.tooltip = tooltip
    },
    showTooltip() {
      if (this.disabled) return
      if (!this.tooltip) {
        this.createTooltip()
        if (!this.tooltip) return
      }
      if (!this.$refs.box) return // Ensure element is not destroyed
      try {
        document.body.appendChild(this.tooltip)
      } catch (error) {
        console.error(error)
      }

      this.isShowing = true
    },
    hideTooltip() {
      if (!this.tooltip) return
      this.tooltip.remove()
      this.isShowing = false
    },
    click() {
      if (!this.isShowing) this.showTooltip()
    }
  },
  beforeDestroy() {
    this.hideTooltip()
  }
}
</script>
