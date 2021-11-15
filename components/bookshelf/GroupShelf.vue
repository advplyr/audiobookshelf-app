<template>
  <div class="w-full relative">
    <div class="bookshelfRow flex items-end justify-around px-3 max-w-full" :class="shelfHeightClass">
      <template v-for="group in groups">
        <cards-series-card v-if="groupType === 'series'" :key="group.id" :group="group" :width="112" class="mx-2" />
        <cards-collection-card v-if="groupType === 'collection'" :key="group.id" :collection="group" :width="90" class="mx-2" />
      </template>
    </div>

    <div class="w-full h-5 z-40 bookshelfDivider"></div>
  </div>
</template>

<script>
export default {
  props: {
    groupType: String,
    groups: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  computed: {
    shelfHeightClass() {
      if (this.groupType === 'series') return 'h-48'
      return 'h-44'
    }
  },
  methods: {},
  mounted() {}
}
</script>
