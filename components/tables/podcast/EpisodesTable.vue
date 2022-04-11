<template>
  <div class="w-full">
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