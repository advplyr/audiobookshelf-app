<template>
  <div :key="playlist.id" :id="`playlist-row-${playlist.id}`" class="flex items-center px-3 py-2 justify-start cursor-pointer relative" :class="inPlaylist ? 'bg-bg bg-opacity-60' : 'bg-opacity-20'">
    <div v-if="inPlaylist" class="absolute top-0 left-0 h-full w-1 bg-success z-10" />
    <div class="w-16 max-w-16 text-center">
      <covers-playlist-cover :items="items" :width="52" :height="52" />
    </div>
    <div class="flex-grow overflow-hidden">
      <p class="pl-1 pr-2 truncate text-sm">{{ playlist.name }}</p>
    </div>

    <div class="absolute top-0 right-0 h-full flex items-center px-3" @click.stop="click">
      <span v-if="inPlaylist" class="material-icons text-error">remove</span>
      <span v-else class="material-icons text-success">add</span>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    playlist: {
      type: Object,
      default: () => {}
    },
    inPlaylist: Boolean
  },
  data() {
    return {}
  },
  computed: {
    items() {
      return this.playlist.items || []
    }
  },
  methods: {
    click() {
      this.$emit('click', this.playlist)
    }
  },
  mounted() {}
}
</script>