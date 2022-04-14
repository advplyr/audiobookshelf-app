<template>
  <div class="w-full">
    <p class="text-lg mb-1 font-semibold">Episodes ({{ episodes.length }})</p>

    <template v-for="episode in episodes">
      <tables-podcast-episode-row :episode="episode" :library-item-id="libraryItemId" :key="episode.id" />
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
    }
  },
  data() {
    return {}
  },
  computed: {},
  methods: {},
  mounted() {}
}
</script>