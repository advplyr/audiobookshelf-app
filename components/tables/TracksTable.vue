<template>
    <div class="w-full my-4">
    <div class="w-full bg-surface-container text-on-surface px-4 py-3 flex items-center border border-outline-variant" :class="showTracks ? 'rounded-t-2xl' : 'rounded-2xl'" @click.stop="clickBar">
      <p class="pr-2 font-medium">{{ $strings.HeaderAudioTracks }}</p>
      <div class="h-6 w-6 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center">
        <span class="text-label-small font-mono">{{ tracks.length }}</span>
      </div>
      <div class="flex-grow" />
      <div class="h-10 w-10 rounded-full bg-secondary-container text-on-secondary-container flex justify-center items-center duration-500 hover:bg-secondary-container/80" :class="showTracks ? 'transform rotate-180' : ''">
        <span class="material-symbols text-display-small text-on-surface">arrow_drop_down</span>
      </div>
    </div>
    <transition name="slide">
      <div class="bg-surface-container border-x border-b border-outline-variant rounded-b-2xl px-4 py-2" v-show="showTracks">
        <table class="text-body-medium tracksTable w-full">
          <tr class="border-b border-outline-variant">
            <th class="text-left py-2 text-on-surface font-medium">{{ $strings.LabelFilename }}</th>
            <th class="text-center w-20 py-2 text-on-surface font-medium">{{ $strings.LabelDuration }}</th>
          </tr>
          <template v-for="track in tracks">
            <tr :key="track.index" class="hover:bg-surface-container-high">
              <td class="py-2 text-on-surface-variant">{{ (track.metadata && track.metadata.filename) || track.title || 'Unknown' }}</td>
              <td class="font-mono text-center w-20 py-2 text-on-surface-variant">
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
