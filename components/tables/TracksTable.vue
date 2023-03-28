<template>
  <div class="w-full my-4">
    <div class="w-full bg-primary px-4 py-2 flex items-center" :class="showTracks ? 'rounded-t-md' : 'rounded-md'" @click.stop="clickBar">
      <p class="pr-2">{{ title }}</p>
      <div class="h-6 w-6 rounded-full bg-white bg-opacity-10 flex items-center justify-center">
        <span class="text-xs font-mono">{{ tracks.length }}</span>
      </div>
      <div class="flex-grow" />
      <div class="h-10 w-10 rounded-full flex justify-center items-center duration-500" :class="showTracks ? 'transform rotate-180' : ''">
        <span class="material-icons text-3xl">expand_more</span>
      </div>
    </div>
    <transition name="slide">
      <div class="w-full" v-show="showTracks">
        <table class="text-xs tracksTable">
          <tr>
            <th class="text-left">Filename</th>
            <th class="text-center w-16">Duration</th>
          </tr>
          <template v-for="track in tracks">
            <tr :key="track.index">
              <td>{{ (track.metadata && track.metadata.filename) || track.title || 'Unknown' }}</td>
              <td class="font-mono text-center w-16">
                {{ $secondsToTimestamp(track.duration) }}
              </td>
            </tr>
          </template>
        </table>
      </div>
    </transition>
  </div>
</template>

<script>
export default {
  props: {
    title: {
      type: String,
      default: 'Audio Tracks'
    },
    tracks: {
      type: Array,
      default: () => []
    },
    libraryItemId: String
  },
  data() {
    return {
      showTracks: false
    }
  },
  computed: {},
  methods: {
    clickBar() {
      this.showTracks = !this.showTracks
    }
  },
  mounted() {}
}
</script>

<style scoped>
.tracksTable {
  border-collapse: collapse;
  width: 100%;
  border: 1px solid #474747;
}

.tracksTable tr:nth-child(even) {
  background-color: #2e2e2e;
}

.tracksTable tr {
  background-color: #373838;
}

.tracksTable td {
  padding: 8px 8px;
}

.tracksTable th {
  padding: 4px 8px;
  font-size: 0.75rem;
}
</style>