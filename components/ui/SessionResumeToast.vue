<template>
  <div v-if="show" class="session-resume-toast" @click="resumeSession">
    <div class="flex items-center">
      <div class="w-12 h-12 bg-bg rounded-lg mr-3 flex items-center justify-center">
        <span class="material-icons text-primary">play_arrow</span>
      </div>
      <div class="flex-1">
        <p class="text-sm font-medium text-gray-100 mb-1">Continue listening</p>
        <p class="text-xs text-gray-300">{{ sessionTitle }} • {{ progressText }}</p>
      </div>
      <button @click.stop="dismiss" class="ml-2 text-gray-400 hover:text-gray-200">
        <span class="material-icons text-lg">close</span>
      </button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'SessionResumeToast',
  props: {
    session: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      show: false
    }
  },
  computed: {
    sessionTitle() {
      return this.session?.displayTitle || 'Unknown'
    },
    progressText() {
      if (!this.session) return ''
      const progress = (this.session.currentTime / this.session.duration) * 100
      return `${Math.floor(progress)}% complete`
    }
  },
  watch: {
    session: {
      handler(newSession) {
        if (newSession) {
          this.show = true
          // Auto-hide after 8 seconds
          setTimeout(() => {
            this.show = false
          }, 8000)
        }
      },
      immediate: true
    }
  },
  methods: {
    async resumeSession() {
      try {
        this.$emit('resume')
        this.show = false
      } catch (error) {
        console.error('Failed to resume session', error)
        this.$toast.error('Failed to resume playback')
      }
    },
    dismiss() {
      this.show = false
      this.$emit('dismiss')
    }
  }
}
</script>

<style scoped>
.session-resume-toast {
  position: fixed;
  bottom: 5rem;
  left: 1rem;
  right: 1rem;
  background: rgba(55, 65, 81, 0.95);
  border-radius: 0.75rem;
  padding: 1rem;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
  z-index: 50;
  cursor: pointer;
  transition: all 0.3s;
  backdrop-filter: blur(10px);
  border: 1px solid rgba(75, 85, 99, 0.3);
}

.session-resume-toast:hover {
  background: rgba(55, 65, 81, 0.8);
}

@media (min-width: 640px) {
  .session-resume-toast {
    left: auto;
    right: 1rem;
    width: 20rem;
  }
}
</style>
