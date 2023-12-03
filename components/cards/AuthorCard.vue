<template>
  <div>
    <div :style="{ width: width + 'px', height: height + 'px' }" class="bg-primary box-shadow-book rounded-md relative overflow-hidden">
      <!-- Image or placeholder -->
      <covers-author-image :author="author" />

      <!-- Author name & num books overlay -->
      <div v-show="!searching && !nameBelow" class="absolute bottom-0 left-0 w-full py-1 bg-black bg-opacity-60 px-2">
        <p class="text-center font-semibold truncate" :style="{ fontSize: sizeMultiplier * 0.75 + 'rem' }">{{ name }}</p>
        <p class="text-center text-gray-200" :style="{ fontSize: sizeMultiplier * 0.65 + 'rem' }">{{ numBooks }} {{ $strings.LabelBooks }}</p>
      </div>

      <!-- Loading spinner -->
      <div v-show="searching" class="absolute top-0 left-0 z-10 w-full h-full bg-black bg-opacity-50 flex items-center justify-center">
        <widgets-loading-spinner size="" />
      </div>
    </div>
    <div v-show="nameBelow" class="w-full py-1 px-2">
      <p class="text-center font-semibold truncate text-gray-200" :style="{ fontSize: sizeMultiplier * 0.75 + 'rem' }">{{ name }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    author: {
      type: Object,
      default: () => {}
    },
    width: Number,
    height: Number,
    sizeMultiplier: {
      type: Number,
      default: 1
    },
    nameBelow: Boolean
  },
  data() {
    return {
      searching: false
    }
  },
  computed: {
    userToken() {
      return this.$store.getters['user/getToken']
    },
    _author() {
      return this.author || {}
    },
    authorId() {
      return this._author.id
    },
    name() {
      return this._author.name || ''
    },
    numBooks() {
      return this._author.numBooks || 0
    }
  },
  methods: {},
  mounted() {}
}
</script>