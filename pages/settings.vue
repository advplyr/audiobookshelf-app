<template>
  <div class="w-full h-full p-8">
    <div class="flex items-center py-3" @click="toggleDisableAutoRewind">
      <div class="w-10 flex justify-center">
        <ui-toggle-switch v-model="settings.disableAutoRewind" @input="saveSettings" />
      </div>
      <p class="pl-4">Disable Auto Rewind</p>
    </div>
    <div class="flex items-center py-3" @click="toggleJumpBackwards">
      <div class="w-10 flex justify-center">
        <span class="material-icons text-4xl">{{ currentJumpBackwardsTimeIcon }}</span>
      </div>
      <p class="pl-4">Jump backwards time</p>
    </div>
    <div class="flex items-center py-3" @click="toggleJumpForwards">
      <div class="w-10 flex justify-center">
        <span class="material-icons text-4xl">{{ currentJumpForwardsTimeIcon }}</span>
      </div>
      <p class="pl-4">Jump forwards time</p>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      settings: {
        disableAutoRewind: false,
        jumpForwardsTime: 10000,
        jumpBackwardsTime: 10000
      },
      jumpForwardsItems: [
        {
          icon: 'forward_5',
          value: 5000
        },
        {
          icon: 'forward_10',
          value: 10000
        },
        {
          icon: 'forward_30',
          value: 30000
        }
      ],
      jumpBackwardsItems: [
        {
          icon: 'replay_5',
          value: 5000
        },
        {
          icon: 'replay_10',
          value: 10000
        },
        {
          icon: 'replay_30',
          value: 30000
        }
      ]
    }
  },
  computed: {
    currentJumpForwardsTimeIcon() {
      return this.jumpForwardsItems[this.currentJumpForwardsTimeIndex].icon
    },
    currentJumpForwardsTimeIndex() {
      return this.jumpForwardsItems.findIndex((jfi) => jfi.value === this.settings.jumpForwardsTime)
    },
    currentJumpBackwardsTimeIcon() {
      return this.jumpBackwardsItems[this.currentJumpBackwardsTimeIndex].icon
    },
    currentJumpBackwardsTimeIndex() {
      return this.jumpBackwardsItems.findIndex((jfi) => jfi.value === this.settings.jumpBackwardsTime)
    }
  },
  methods: {
    toggleDisableAutoRewind() {
      this.settings.disableAutoRewind = !this.settings.disableAutoRewind
      this.saveSettings()
    },
    toggleJumpForwards() {
      var next = (this.currentJumpForwardsTimeIndex + 1) % 3
      this.settings.jumpForwardsTime = this.jumpForwardsItems[next].value
      this.saveSettings()
    },
    toggleJumpBackwards() {
      var next = (this.currentJumpBackwardsTimeIndex + 4) % 3
      console.log('next', next)
      if (next > 2) return
      this.settings.jumpBackwardsTime = this.jumpBackwardsItems[next].value
      this.saveSettings()
    },
    saveSettings() {
      // TODO: Save settings
    }
  },
  mounted() {
    // TODO: Load settings
  }
}
</script>