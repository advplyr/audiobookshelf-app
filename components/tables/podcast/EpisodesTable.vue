<template>
  <div class="w-full">
    <p class="text-lg mb-1 font-semibold">Episodes ({{ episodes.length }})</p>

    <template v-for="episode in episodes">
      <tables-podcast-episode-row :episode="episode" :local-episode="localEpisodeMap[episode.id]" :library-item-id="libraryItemId" :local-library-item-id="localLibraryItemId" :is-local="isLocal" :key="episode.id" />
    </template>
  </div>
</template>

<script>
export default {
  props: {
    libraryItemId: String,
    episodes: {
      type: Array,
      default: () => []
    },
    localLibraryItemId: String,
    localEpisodes: {
      type: Array,
      default: () => []
    },
    isLocal: Boolean // If is local then episodes and libraryItemId are local, otherwise local is passed in localLibraryItemId and localEpisodes
  },
  data() {
    return {}
  },
  computed: {
    // Map of local episodes where server episode id is key
    localEpisodeMap() {
      var epmap = {}
      this.localEpisodes.forEach((localEp) => {
        if (localEp.serverEpisodeId) {
          epmap[localEp.serverEpisodeId] = localEp
        }
      })
      return epmap
    }
  },
  methods: {},
  mounted() {}
}
</script>