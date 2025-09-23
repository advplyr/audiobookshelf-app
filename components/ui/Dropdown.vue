<template>
  <div class="relative w-full" v-click-outside="clickedOutside">
    <label v-if="label" class="block mb-2 text-sm font-medium text-on-surface" :class="disabled ? 'opacity-50' : ''">{{ label }}</label>

    <button
      type="button"
      :disabled="disabled"
      class="relative w-full border border-outline bg-surface-container text-on-surface rounded-lg pl-4 pr-10 py-3 text-left focus:outline-none transition-all duration-200"
      :class="buttonClass"
      aria-haspopup="listbox"
      :aria-expanded="showMenu.toString()"
      @click.stop.prevent="clickShowMenu"
    >
      <span class="flex items-center">
        <span class="block truncate" :class="!selectedText ? 'text-on-surface-variant' : 'text-on-surface'">{{ selectedText || placeholder || '' }}</span>
      </span>

      <span class="absolute inset-y-0 right-0 flex items-center pr-3 pointer-events-none">
        <span class="material-symbols text-on-surface-variant">arrow_drop_down</span>
      </span>
    </button>

    <transition name="menu">
      <ul
        v-if="showMenu"
        class="absolute z-50 mt-1 w-full bg-surface-container border border-outline-variant rounded-lg py-1 max-h-56 overflow-auto focus:outline-none shadow-elevation-2"
        role="listbox"
        tabindex="-1"
      >
        <li
          v-for="item in items"
          :key="item.value"
          class="select-none relative py-3 px-4 cursor-pointer text-on-surface state-layer hover:bg-on-surface/8"
          role="option"
          @click="clickedOption(item.value)"
        >
          <div class="flex items-center">
            <span class="font-normal block truncate">{{ item.text }}</span>
          </div>
        </li>
      </ul>
    </transition>
  </div>
</template>

<script>
export default {
  props: {
    value: [String, Number],
    label: {
      type: String,
      default: ''
    },
    items: {
      type: Array,
      default: () => []
    },
    disabled: Boolean,
    small: Boolean,
    placeholder: String
  },
  data() {
    return {
      showMenu: false
    }
  },
  computed: {
    selected: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    selectedItem() {
      return this.items.find((i) => i.value === this.selected) || null
    },
    selectedText() {
      return this.selectedItem ? this.selectedItem.text : ''
    },
    buttonClass() {
      const classes = []
      classes.push(this.small ? 'h-9' : 'h-12')
      if (this.disabled) classes.push('cursor-not-allowed opacity-50')
      else classes.push('cursor-pointer hover:shadow-elevation-2')
      return classes.join(' ')
    }
  },
  methods: {
    clickShowMenu() {
      if (this.disabled) return
      this.showMenu = !this.showMenu
    },
    clickedOutside() {
      this.showMenu = false
    },
    clickedOption(itemValue) {
      this.selected = itemValue
      this.showMenu = false
    }
  }
}
</script>

<style scoped>
.menu-enter-active, .menu-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.menu-enter, .menu-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
