<template>
  <transition
    name="dialog-transition"
    enter-active-class="transition-all duration-300 ease-expressive"
    leave-active-class="transition-all duration-200 ease-expressive"
    enter-from-class="opacity-0 scale-95"
    enter-to-class="opacity-100 scale-100"
    leave-from-class="opacity-100 scale-100"
    leave-to-class="opacity-0 scale-95"
  >
    <modals-modal v-if="show" v-model="show" :width="width" height="100%">
      <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop >
        <transition
          name="dialog-content-transition"
          enter-active-class="transition-all duration-250 ease-expressive"
          leave-active-class="transition-all duration-150 ease-expressive"
          enter-from-class="opacity-0 transform translate-y-4 scale-95"
          enter-to-class="opacity-100 transform translate-y-0 scale-100"
          leave-from-class="opacity-100 transform translate-y-0 scale-100"
          leave-to-class="opacity-0 transform translate-y-4 scale-95"
        >
          <div v-if="show" ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-surface-container-high rounded-3xl border border-outline-variant shadow-elevation-3 p-6 max-w-sm mx-4" >
            <!-- Material 3 Dialog Header -->
            <div v-if="title" class="mb-4">
              <h2 class="text-headline-small text-on-surface font-medium">{{ title }}</h2>
            </div>

            <!-- Dialog Content -->
            <div v-if="message" class="mb-6">
              <p class="text-body-medium text-on-surface-variant leading-relaxed">{{ message }}</p>
            </div>

            <!-- Action Buttons -->
            <div class="flex gap-3 justify-end">
              <button
                @click="handleCancel"
                class="material-3-button material-3-button-text px-6 py-2.5 rounded-full text-label-large font-medium text-primary transition-all duration-200 hover:bg-primary/0.08 focus:bg-primary/0.12"
              >
                {{ cancelText }}
              </button>
              <button
                @click="handleConfirm"
                class="material-3-button material-3-button-filled px-6 py-2.5 rounded-full text-label-large font-medium bg-primary text-on-primary shadow-elevation-0 hover:shadow-elevation-1 focus:shadow-elevation-2 transition-all duration-200"
              >
                {{ confirmText }}
              </button>
            </div>
          </div>
        </transition>
      </div>
    </modals-modal>
  </transition>
</template>

<script>
export default {
  props: {
    value: Boolean,
    title: {
      type: String,
      default: 'Confirm'
    },
    message: {
      type: String,
      required: true
    },
    confirmText: {
      type: String,
      default: 'OK'
    },
    cancelText: {
      type: String,
      default: 'Cancel'
    },
    width: {
      type: [String, Number],
      default: 400
    }
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
    }
  },
  methods: {
    handleConfirm() {
      this.$emit('confirm')
      this.show = false
    },
    handleCancel() {
      this.$emit('cancel')
      this.show = false
    }
  }
}
</script>
