<template>
  <modals-modal v-model="show" :width="200" height="100%">
    <template #outer>
      <div class="absolute top-5 left-4 z-40">
        <p class="text-white text-2xl truncate">Playback Speed</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="rate in rates">
            <li :key="rate" class="text-gray-50 select-none relative py-4 cursor-pointer hover:bg-black-400" :class="rate === selected ? 'bg-bg bg-opacity-80' : ''" role="option" @click="clickedOption(rate)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">{{ rate }}x</span>
              </div>
            </li>
          </template>
        </ul>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    playbackSpeed: Number
  },
  data() {
    return {}
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    selected: {
      get() {
        return this.playbackSpeed
      },
      set(val) {
        this.$emit('update:playbackSpeed', val)
      }
    },
    rates() {
      return [0.25, 0.5, 0.8, 1, 1.3, 1.5, 2, 2.5, 3]
    }
  },
  methods: {
    clickedOption(speed) {
      if (this.selected === speed) {
        this.show = false
        return
      }
      this.selected = speed
      this.show = false
      this.$nextTick(() => this.$emit('change', speed))
    }
  },
  mounted() {}
}
</script>
