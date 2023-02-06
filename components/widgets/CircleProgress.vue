<template>
  <div ref="progressbar" class="progressbar">
    <svg class="progressbar__svg">
      <circle cx="20" cy="20" r="17.5" ref="circle" class="progressbar__svg-circle circle-anim"></circle>
      <circle cx="20" cy="20" r="17.5" class="progressbar__svg-circlebg"></circle>
    </svg>
    <p class="progressbar__text text-sm text-warning">{{ count }}</p>
    <!-- <span class="material-icons progressbar__text text-xl">arrow_downward</span> -->
    <!-- <div class="w-4 h-4 rounded-full bg-red-600 absolute bottom-1 right-1 flex items-center justify-center transform rotate-90">
      <p class="text-xs text-white">4</p>
    </div> -->
  </div>
</template>

<script>
export default {
  props: {
    value: Number,
    count: Number
  },
  data() {
    return {
      lastProgress: 0,
      updateTimeout: null
    }
  },
  watch: {
    value: {
      handler(newVal, oldVal) {
        this.updateProgress()
      }
    }
  },
  computed: {},
  methods: {
    updateProgress() {
      const progbar = this.$refs.progressbar
      const circle = this.$refs.circle
      if (!progbar || !circle) return

      clearTimeout(this.updateTimeout)
      const progress = Math.min(this.value || 0, 1)

      progbar.style.setProperty('--progress-percent-before', this.lastProgress)
      progbar.style.setProperty('--progress-percent', progress)

      this.lastProgress = progress
      circle.classList.remove('circle-static')
      circle.classList.add('circle-anim')
      this.updateTimeout = setTimeout(() => {
        circle.classList.remove('circle-anim')
        circle.classList.add('circle-static')
      }, 500)
    }
  },
  mounted() {}
}
</script>

<style scoped>
/* https://codepen.io/alvarotrigo/pen/VwMvydQ */
.progressbar {
  position: relative;
  width: 42.5px;
  height: 42.5px;
  margin: 0.25em;
  transform: rotate(-90deg);
  box-sizing: border-box;
  --progress-percent-before: 0;
  --progress-percent: 0;
}

.progressbar__svg {
  position: relative;
  width: 100%;
  height: 100%;
}

.progressbar__svg-circlebg {
  width: 100%;
  height: 100%;
  fill: none;
  stroke-width: 4;
  /* stroke-dasharray: 110;
  stroke-dashoffset: 110; */
  stroke: #fb8c0022;
  stroke-linecap: round;
  transform: translate(2px, 2px);
}

.progressbar__svg-circle {
  width: 100%;
  height: 100%;
  fill: none;
  stroke-width: 4;
  stroke-dasharray: 110;
  stroke-dashoffset: 110;
  /* stroke: hsl(0, 0%, 100%); */
  stroke: #fb8c00;
  stroke-linecap: round;
  transform: translate(2px, 2px);
}

.circle-anim {
  animation: anim_circle 0.5s ease-in-out forwards;
}

.circle-static {
  stroke-dashoffset: calc(110px - (110px * var(--progress-percent)));
}

@keyframes anim_circle {
  from {
    stroke-dashoffset: calc(110px - (110px * var(--progress-percent-before)));
  }
  to {
    stroke-dashoffset: calc(110px - (110px * var(--progress-percent)));
  }
}

.progressbar__text {
  position: absolute;
  top: 50%;
  left: 50%;
  margin-top: 1px;
  transform: translate(-50%, -50%) rotate(90deg);
  animation: bounce 0.75s infinite;
}

@keyframes bounce {
  0%,
  100% {
    transform: translate(-35%, -50%) rotate(90deg);
    -webkit-animation-timing-function: cubic-bezier(0.8, 0, 1, 1);
    animation-timing-function: cubic-bezier(0.8, 0, 1, 1);
  }
  50% {
    transform: translate(-50%, -50%) rotate(90deg);
    -webkit-animation-timing-function: cubic-bezier(0, 0, 0.2, 1);
    animation-timing-function: cubic-bezier(0, 0, 0.2, 1);
  }
}
</style>