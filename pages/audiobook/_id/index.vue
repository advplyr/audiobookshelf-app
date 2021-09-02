<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto">
    <div class="flex">
      <div class="w-32">
        <cards-book-cover :audiobook="audiobook" :width="128" />
      </div>
      <div class="flex-grow px-3">
        <h1 class="text-lg">{{ title }}</h1>
        <h3 v-if="series" class="font-book text-gray-300 text-lg leading-7">{{ seriesText }}</h3>
        <p class="text-sm text-gray-400">by {{ author }}</p>
        <p class="text-gray-300 text-sm my-1">
          {{ durationPretty }}<span class="px-4">{{ sizePretty }}</span>
        </p>
        <ui-btn color="success" class="flex items-center justify-center w-full mt-2" :padding-x="4" @click="playClick">
          <span class="material-icons">play_arrow</span>
          <span class="px-1">Play</span>
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
    return {}
  },
  computed: {
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
      return this.book.description || 'No Description'
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
    }
  },
  methods: {
    playClick() {
      this.$store.commit('setPlayOnLoad', true)
      this.$store.commit('setStreamAudiobook', this.audiobook)
      this.$server.socket.emit('open_stream', this.audiobook.id)
    }
  },
  mounted() {}
}
</script>