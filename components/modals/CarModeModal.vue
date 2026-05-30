<template>
  <modals-modal v-model="show" :width="400" max-width="95%" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full h-full" @click.stop>
        <div class="w-full h-full flex flex-col">
          <div class="h-1/2 mt-20">
            <button @click.stop="playPauseClick" class="aspect-square w-full flex items-center justify-center">
              <span class="material-symbols text-7xl text-white">{{ localPlaying ? 'pause' : 'play_arrow' }}</span>
            </button>
          </div>

          <div class="h-1/2 flex items-end justify-center px-6 pb-10">
            <div class="flex items-center justify-center w-full max-w-2xl gap-8">
              <button @click.stop="jumpBackward" class="aspect-square flex-col w-44 flex items-center justify-center">
                  <span class="material-symbols text-5xl text-white leading-none">replay</span>
                  <span class="jump-label font-semibold leading-tight text-4xl text-white"">{{ jumpBackwardsLabel }}</span>
              </button>
              <button @click.stop="jumpForward" class="aspect-square flex-col w-44 flex items-center justify-center">
                  <span class="material-symbols text-5xl text-white leading-none">forward_media</span>
                  <span class="jump-label font-semibold leading-tight text-4xl text-white"">{{ jumpForwardLabel }}</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'
import jumpLabelMixin from '@/mixins/jumpLabel'

export default {
  props: {
    value: Boolean
  },
  mixins: [jumpLabelMixin],
  data() {
    return {
      localPlaying: false,
      playingListener: null
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
    },
    jumpForwardLabel() {
      return this.getJumpLabel(this.jumpForwardTime)
    },
    jumpBackwardsLabel() {
      return this.getJumpLabel(this.jumpBackwardsTime)
    },
    jumpForwardTime() {
      return this.$store.getters['getJumpForwardTime']
    },
    jumpBackwardsTime() {
      return this.$store.getters['getJumpBackwardsTime']
    }
  },
  methods: {
    async playPauseClick() {
      try {
        const res = await AbsAudioPlayer.playPause()
        if (res && typeof res.playing !== 'undefined') {
          this.localPlaying = !!res.playing
        } else {
          this.localPlaying = !this.localPlaying
        }
      } catch (e) {
        console.error('[CarModeModal] playToggle failed', e)
      }
    },
    onPlayingUpdate(data) {
      if (data && typeof data.value !== 'undefined') this.localPlaying = !!data.value
      else if (data && typeof data.playing !== 'undefined') this.localPlaying = !!data.playing
    },
    async jumpForward() {
      try {
        await AbsAudioPlayer.seekForward({ value: this.jumpForwardTime })
      } catch (e) {
        console.error('[CarModeModal] skipForward failed', e)
      }
    },
    async jumpBackward() {
      try {
        await AbsAudioPlayer.seekBackward({ value: this.jumpBackwardsTime })
      } catch (e) {
        console.error('[CarModeModal] skipBackward failed', e)
      }
    }
  },
  mounted() {
    try {
      this.playingListener = AbsAudioPlayer.addListener('onPlayingUpdate', this.onPlayingUpdate)
    } catch (e) {
      // ignore if unavailable in environment
    }
  },
  beforeDestroy() {
    try {
      if (this.playingListener && this.playingListener.remove) {
        this.playingListener.remove()
      }
    } catch (e) {
      // Ignore
    }
  }
}
</script>
