<template>
  <div ref="wrapper" v-click-outside="clickOutside">
    <div @click.stop="toggleMenu">
      <slot />
    </div>
    <transition name="menu">
      <ul ref="menu" v-show="showMenu" class="absolute z-50 -mt-px bg-primary border border-gray-600 shadow-lg max-h-56 rounded-md py-1 text-base ring-1 ring-black ring-opacity-5 overflow-auto focus:outline-none sm:text-sm" tabindex="-1" role="listbox" aria-activedescendant="listbox-option-3" style="width: 160px">
        <template v-for="item in items">
          <nuxt-link :key="item.value" v-if="item.to" :to="item.to">
            <li :key="item.value" class="text-gray-100 select-none relative py-2 cursor-pointer hover:bg-black-400" id="listbox-option-0" role="option" @click="clickedOption(item.value)">
              <div class="flex items-center px-2">
                <span v-if="item.icon" class="material-icons-outlined text-lg mr-2" :class="item.iconClass ? item.iconClass : ''">{{ item.icon }}</span>
                <span class="font-normal block truncate font-sans text-center">{{ item.text }}</span>
              </div>
            </li>
          </nuxt-link>
          <li v-else :key="item.value" class="text-gray-100 select-none relative py-2 cursor-pointer hover:bg-black-400" id="listbox-option-0" role="option" @click="clickedOption(item.value)">
            <div class="flex items-center px-2">
              <span v-if="item.icon" class="material-icons-outlined text-lg mr-2" :class="item.iconClass ? item.iconClass : ''">{{ item.icon }}</span>
              <span class="font-normal block truncate font-sans text-center">{{ item.text }}</span>
            </div>
          </li>
        </template>
      </ul>
    </transition>
  </div>
</template>

<script>
export default {
  props: {
    items: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      menu: null,
      showMenu: false
    }
  },
  methods: {
    toggleMenu() {
      if (!this.showMenu) {
        this.openMenu()
      } else {
        this.closeMenu()
      }
    },
    openMenu() {
      this.showMenu = true
      this.$nextTick(() => {
        if (!this.menu) this.unmountMountMenu()
        this.recalcMenuPos()
      })
    },
    closeMenu() {
      this.showMenu = false
    },
    recalcMenuPos() {
      if (!this.menu) return
      var boundingBox = this.$refs.wrapper.getBoundingClientRect()
      if (boundingBox.y > window.innerHeight - 8) {
        // Input is off the page
        return this.closeMenu()
      }
      var menuHeight = this.menu.clientHeight
      var top = boundingBox.y + boundingBox.height - 4
      if (top + menuHeight > window.innerHeight - 20) {
        // Reverse menu to open upwards
        top = boundingBox.y - menuHeight - 4
      }

      var left = boundingBox.x
      if (left + this.menu.clientWidth > window.innerWidth - 20) {
        // Shift left
        left = boundingBox.x + boundingBox.width - this.menu.clientWidth
      }

      this.menu.style.top = top + 'px'
      this.menu.style.left = left + 'px'
    },
    unmountMountMenu() {
      if (!this.$refs.menu) return
      this.menu = this.$refs.menu
      this.menu.remove()
      document.body.appendChild(this.menu)
    },
    clickOutside() {
      this.closeMenu()
    },
    clickedOption(itemValue) {
      this.closeMenu()
      this.$emit('action', itemValue)
    }
  },
  mounted() {},
  beforeDestroy() {
    if (this.menu) {
      this.menu.remove()
    }
  }
}
</script>