<template>
  <modals-modal v-model="show" :width="400" max-width="95%" height="100%">
    <template #outer>
      <div class="absolute top-10 left-4 z-40 pt-1" style="max-width: 80%">
        <p class="text-white text-lg truncate">{{ $strings.LabelUpNext }} · {{ episodes.length }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-secondary rounded-lg border border-fg/20" style="max-height: 75%" @click.stop>
        <p v-if="!episodes.length" class="text-fg-muted text-sm px-3 py-4">{{ $strings.MessageNoMoreEpisodes }}</p>
        <ul v-else class="h-full w-full" role="listbox">
          <li v-for="(episode, i) in episodes" :key="episode.id" class="text-fg select-none relative cursor-pointer border-b border-fg/5" role="option" @click="$emit('select', episode)">
            <div class="grid grid-cols-[auto_1fr_auto] gap-3 px-3 py-3 items-center">
              <span class="material-symbols text-xl text-fg-muted">{{ i === 0 ? 'skip_next' : 'play_arrow' }}</span>
              <p class="font-normal line-clamp-2 text-sm text-fg/80">{{ episode.title }}</p>
              <span v-if="episode.duration" class="font-mono text-fg-muted text-sm" style="letter-spacing: -0.5px">{{ $secondsToTimestamp(episode.duration) }}</span>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    episodes: {
      type: Array,
      default: () => []
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
  }
}
</script>
