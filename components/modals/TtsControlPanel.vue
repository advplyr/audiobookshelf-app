<template>
  <div v-if="show" class="fixed inset-0 z-50 flex items-end" @click.self="close">
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/60" @click="close" />

    <!-- Panel -->
    <div class="relative w-full rounded-t-2xl px-6 pt-5 pb-8 z-10" :class="isDark ? 'bg-[#1e1e1e] text-white' : 'bg-white text-black'">
      <!-- Handle -->
      <div class="w-10 h-1 rounded-full bg-gray-500 mx-auto mb-4" />

      <h2 class="text-lg font-semibold mb-4 text-center">{{ $strings.labelReadAloud || 'Read Aloud' }}</h2>

      <!-- Progress bar -->
      <div class="w-full h-1.5 rounded-full mb-5" :class="isDark ? 'bg-gray-700' : 'bg-gray-200'">
        <div class="h-full rounded-full bg-yellow-400 transition-all" :style="{ width: progress + '%' }" />
      </div>

      <!-- Main controls -->
      <div class="flex items-center justify-center gap-8 mb-6">
        <!-- Prev sentence -->
        <button class="p-2 rounded-full" :class="isDark ? 'hover:bg-white/10' : 'hover:bg-black/10'" @click="prev">
          <svg xmlns="http://www.w3.org/2000/svg" class="w-7 h-7" fill="currentColor" viewBox="0 0 24 24">
            <path d="M6 6h2v12H6zm3.5 6 8.5 6V6z" />
          </svg>
        </button>

        <!-- Play / Pause -->
        <button class="w-16 h-16 rounded-full flex items-center justify-center bg-yellow-400 text-black shadow-lg active:scale-95 transition-transform" @click="togglePlay">
          <svg v-if="!isPlaying" xmlns="http://www.w3.org/2000/svg" class="w-8 h-8" fill="currentColor" viewBox="0 0 24 24">
            <path d="M8 5v14l11-7z" />
          </svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" class="w-8 h-8" fill="currentColor" viewBox="0 0 24 24">
            <path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" />
          </svg>
        </button>

        <!-- Next sentence -->
        <button class="p-2 rounded-full" :class="isDark ? 'hover:bg-white/10' : 'hover:bg-black/10'" @click="next">
          <svg xmlns="http://www.w3.org/2000/svg" class="w-7 h-7" fill="currentColor" viewBox="0 0 24 24">
            <path d="M6 18l8.5-6L6 6v12zm2-8.14 5.47 3.86L8 17.14V9.86zM16 6h2v12h-2z" />
          </svg>
        </button>
      </div>

      <!-- Speed -->
      <div class="mb-4">
        <div class="flex justify-between text-xs mb-1">
          <span class="text-gray-400">{{ $strings.labelSpeed || 'Speed' }}</span>
          <span class="font-mono">{{ rate.toFixed(1) }}x</span>
        </div>
        <input type="range" min="0.5" max="3.0" step="0.1" :value="rate" class="w-full accent-yellow-400" @input="onRateChange" />
      </div>

      <!-- Pitch -->
      <div class="mb-4">
        <div class="flex justify-between text-xs mb-1">
          <span class="text-gray-400">{{ $strings.labelPitch || 'Pitch' }}</span>
          <span class="font-mono">{{ pitch.toFixed(1) }}</span>
        </div>
        <input type="range" min="0.5" max="2.0" step="0.1" :value="pitch" class="w-full accent-yellow-400" @input="onPitchChange" />
      </div>

      <!-- Voice selector -->
      <div v-if="availableVoices.length" class="mb-5">
        <label class="text-xs text-gray-400 block mb-1">{{ $strings.labelVoice || 'Voice' }}</label>
        <select :value="selectedVoice" class="w-full rounded-lg px-3 py-2 text-sm outline-none" :class="isDark ? 'bg-[#2e2e2e] text-white' : 'bg-gray-100 text-black'" @change="onVoiceChange">
          <option :value="null">{{ $strings.labelDefault || 'Default' }}</option>
          <option v-for="v in availableVoices" :key="v.name" :value="v.name">{{ v.name }} ({{ v.lang }})</option>
        </select>
      </div>

      <!-- Stop & Close -->
      <button class="w-full py-3 rounded-xl text-sm font-semibold" :class="isDark ? 'bg-red-900/60 text-red-300 hover:bg-red-900' : 'bg-red-100 text-red-600 hover:bg-red-200'" @click="stopAndClose">
        {{ $strings.buttonStopReading || 'Stop & Close' }}
      </button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'TtsControlPanel',
  props: {
    show: Boolean,
    theme: {
      type: String,
      default: 'dark'
    }
  },
  computed: {
    isDark() {
      return this.theme !== 'light'
    },
    isPlaying() {
      return this.$store.getters['tts/isPlaying']
    },
    isPaused() {
      return this.$store.getters['tts/isPaused']
    },
    progress() {
      return this.$store.getters['tts/progress']
    },
    rate() {
      return this.$store.getters['tts/rate']
    },
    pitch() {
      return this.$store.getters['tts/pitch']
    },
    selectedVoice() {
      return this.$store.getters['tts/selectedVoice']
    },
    availableVoices() {
      return this.$store.getters['tts/availableVoices']
    }
  },
  methods: {
    close() {
      this.$emit('close')
    },
    stopAndClose() {
      this.$store.dispatch('tts/stopReading')
      this.$emit('close')
    },
    togglePlay() {
      if (this.isPlaying) {
        this.$store.dispatch('tts/pauseReading')
      } else if (this.isPaused) {
        this.$store.dispatch('tts/resumeReading')
      } else {
        // Trigger from parent via event
        this.$emit('start')
      }
    },
    prev() {
      this.$store.dispatch('tts/prevSentence')
    },
    next() {
      this.$store.dispatch('tts/nextSentence')
    },
    onRateChange(e) {
      this.$store.dispatch('tts/setRate', parseFloat(e.target.value))
    },
    onPitchChange(e) {
      this.$store.dispatch('tts/setPitch', parseFloat(e.target.value))
    },
    onVoiceChange(e) {
      this.$store.dispatch('tts/setVoice', e.target.value || null)
    }
  }
}
</script>
