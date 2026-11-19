<template>
  <modals-modal v-model="show" :width="260" height="100%">
    <template #outer>
      <div class="absolute top-8 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.LabelVolumeBoost }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-border" style="max-height: 75%" @click.stop>
        <div class="flex items-center justify-between px-4 py-3 border-b border-fg/10">
          <span class="text-fg text-base font-medium">{{ $strings.LabelVolumeBoost }}</span>
          <ui-toggle-switch v-model="localEnabled" on-color="success" @input="onToggleEnabled" />
        </div>

        <ul class="w-full" role="listbox" aria-labelledby="listbox-label">
          <li class="text-fg select-none relative py-3 cursor-pointer" :class="!localEnabled ? 'bg-bg-hover/50' : ''" role="option" @click="selectOff">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate text-lg">{{ $strings.LabelOff }}</span>
            </div>
          </li>
          <template v-for="level in levels">
            <li :key="level" class="text-fg select-none relative py-3 cursor-pointer" :class="localEnabled && level === localBoostDb ? 'bg-bg-hover/50' : ''" role="option" @click="selectLevel(level)">
              <div class="flex items-center justify-center">
                <span class="font-normal block truncate text-lg">+{{ level }} dB</span>
              </div>
            </li>
          </template>
        </ul>

        <div class="flex items-center justify-center py-3 border-t border-fg/10">
          <button :disabled="!canDecrement" @click="decrement" class="icon-num-btn w-8 h-8 text-fg-muted rounded border border-border flex items-center justify-center">
            <span class="material-symbols">remove</span>
          </button>
          <div class="w-24 text-center">
            <p class="text-xl">{{ localEnabled ? `+${localBoostDb} dB` : $strings.LabelOff }}</p>
          </div>
          <button :disabled="!canIncrement" @click="increment" class="icon-num-btn w-8 h-8 text-fg-muted rounded border border-border flex items-center justify-center">
            <span class="material-symbols">add</span>
          </button>
        </div>

        <p v-if="localEnabled && localBoostDb > 0" class="px-4 pb-3 text-center text-xs text-fg-muted">
          {{ $strings.MessageVolumeBoostDistortionWarning }}
        </p>
      </div>
    </div>
  </modals-modal>
</template>

<script>
const DEFAULT_BOOST_DB = 3
const MIN_BOOST_DB = 1
const MAX_BOOST_DB = 15

export default {
  props: {
    value: Boolean,
    enabled: Boolean,
    boostDb: {
      type: Number,
      default: DEFAULT_BOOST_DB
    }
  },
  data() {
    return {
      localEnabled: false,
      localBoostDb: DEFAULT_BOOST_DB
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.localEnabled = !!this.enabled
        this.localBoostDb = this.boostDb > 0 ? this.boostDb : DEFAULT_BOOST_DB
      }
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
    levels() {
      return [3, 6, 9, 12, 15]
    },
    canIncrement() {
      return !this.localEnabled || this.localBoostDb < MAX_BOOST_DB
    },
    canDecrement() {
      return this.localEnabled && this.localBoostDb >= MIN_BOOST_DB
    }
  },
  methods: {
    emitChange() {
      this.$emit('change', {
        enabled: this.localEnabled,
        boostDb: this.localBoostDb
      })
    },
    onToggleEnabled(val) {
      if (val && this.localBoostDb <= 0) {
        this.localBoostDb = DEFAULT_BOOST_DB
      }
      this.emitChange()
    },
    selectOff() {
      this.localEnabled = false
      this.emitChange()
      this.show = false
    },
    selectLevel(level) {
      this.localEnabled = true
      this.localBoostDb = level
      this.emitChange()
      this.show = false
    },
    increment() {
      if (!this.localEnabled) {
        this.localEnabled = true
        this.localBoostDb = MIN_BOOST_DB
      } else if (this.localBoostDb < MAX_BOOST_DB) {
        this.localBoostDb += 1
      }
      this.emitChange()
    },
    decrement() {
      if (!this.localEnabled) return
      if (this.localBoostDb <= MIN_BOOST_DB) {
        this.localEnabled = false
      } else {
        this.localBoostDb -= 1
      }
      this.emitChange()
    }
  }
}
</script>
