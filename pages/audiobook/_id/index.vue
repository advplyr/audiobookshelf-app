<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto">
    <div class="flex">
      <div class="w-32">
        <div class="relative">
          <cards-book-cover :audiobook="audiobook" :width="128" />
          <div class="absolute bottom-0 left-0 h-1.5 bg-yellow-400 shadow-sm" :style="{ width: 128 * progressPercent + 'px' }"></div>
        </div>
        <!-- <cards-book-cover :audiobook="audiobook" :width="128" /> -->
      </div>
      <div class="flex-grow px-3">
        <h1 class="text-lg">{{ title }}</h1>
        <h3 v-if="series" class="font-book text-gray-300 text-lg leading-7">{{ seriesText }}</h3>
        <p class="text-sm text-gray-400">by {{ author }}</p>
        <p class="text-gray-300 text-sm my-1">
          {{ durationPretty }}<span class="px-4">{{ sizePretty }}</span>
        </p>

        <div v-if="progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-gray-200 mt-4 relative" :class="resettingProgress ? 'opacity-25' : ''">
          <p class="leading-6">Your Progress: {{ Math.round(progressPercent * 100) }}%</p>
          <p class="text-gray-400 text-xs">{{ $elapsedPretty(userTimeRemaining) }} remaining</p>
          <div v-if="!resettingProgress" class="absolute -top-1.5 -right-1.5 p-1 w-5 h-5 rounded-full bg-bg hover:bg-error border border-primary flex items-center justify-center cursor-pointer" @click.stop="clearProgressClick">
            <span class="material-icons text-sm">close</span>
          </div>
        </div>

        <ui-btn color="success" :disabled="streaming" class="flex items-center justify-center w-full mt-4" :padding-x="4" @click="playClick">
          <span v-show="!streaming" class="material-icons">play_arrow</span>
          <span class="px-1">{{ streaming ? 'Streaming' : 'Play' }}</span>
        </ui-btn>
        <div class="flex my-4"></div>
      </div>
    </div>
    <div class="w-full py-4">
      <p>{{ description }}</p>
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  async asyncData({ params, redirect, app }) {
    var audiobookId = params.id
    var audiobook = await app.$axios.$get(`/api/audiobook/${audiobookId}`).catch((error) => {
      console.error('Failed', error)
      return false
    })
    if (!audiobook) {
      console.error('No audiobook...', params.id)
      return redirect('/')
    }
    return {
      audiobook
    }
  },
  data() {
    return {
      resettingProgress: false
    }
  },
  computed: {
    audiobookId() {
      return this.audiobook.id
    },
    book() {
      return this.audiobook.book || {}
    },
    title() {
      return this.book.title
    },
    author() {
      return this.book.author || 'Unknown'
    },
    description() {
      return this.book.description || ''
    },
    series() {
      return this.book.series || null
    },
    volumeNumber() {
      return this.book.volumeNumber || null
    },
    seriesText() {
      if (!this.series) return ''
      if (!this.volumeNumber) return this.series
      return `${this.series} #${this.volumeNumber}`
    },
    durationPretty() {
      return this.audiobook.durationPretty
    },
    duration() {
      return this.audiobook.duration
    },
    sizePretty() {
      return this.audiobook.sizePretty
    },
    userAudiobooks() {
      return this.$store.state.user.user ? this.$store.state.user.user.audiobooks || {} : {}
    },
    userAudiobook() {
      return this.userAudiobooks[this.audiobookId] || null
    },
    userCurrentTime() {
      return this.userAudiobook ? this.userAudiobook.currentTime : 0
    },
    userTimeRemaining() {
      return this.duration - this.userCurrentTime
    },
    progressPercent() {
      return this.userAudiobook ? this.userAudiobook.progress : 0
    },
    streamAudiobook() {
      return this.$store.state.streamAudiobook
    },
    isStreaming() {
      return this.streamAudiobook && this.streamAudiobook.id === this.audiobookId
    }
  },
  methods: {
    playClick() {
      this.$store.commit('setPlayOnLoad', true)
      this.$store.commit('setStreamAudiobook', this.audiobook)
      this.$server.socket.emit('open_stream', this.audiobook.id)
    },
    async clearProgressClick() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Are you sure you want to reset your progress?'
      })

      if (value) {
        this.resettingProgress = true
        this.$axios
          .$delete(`/api/user/audiobook/${this.audiobookId}`)
          .then(() => {
            console.log('Progress reset complete')
            this.$toast.success(`Your progress was reset`)
            this.resettingProgress = false
          })
          .catch((error) => {
            console.error('Progress reset failed', error)
            this.resettingProgress = false
          })
      }
    },
    audiobookUpdated() {
      console.log('Audiobook Updated - Fetch full audiobook')
      this.$axios
        .$get(`/api/audiobook/${this.audiobookId}`)
        .then((audiobook) => {
          this.audiobook = audiobook
        })
        .catch((error) => {
          console.error('Failed', error)
        })
    }
  },
  mounted() {
    this.$store.commit('audiobooks/addListener', { id: 'audiobook', audiobookId: this.audiobookId, meth: this.audiobookUpdated })
  },
  beforeDestroy() {
    this.$store.commit('audiobooks/removeListener', 'audiobook')
  }
}
</script>