<template>
  <modals-modal v-model="show" width="90%">
    <div class="w-full h-full bg-primary rounded-lg border border-white border-opacity-20">
      <ul class="w-full rounded-lg text-base" role="listbox" aria-labelledby="listbox-label">
        <template v-for="item in items">
          <li :key="item.value" class="text-gray-50 select-none relative py-4 pr-9 cursor-pointer hover:bg-black-400" :class="item.value === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedOption(item.value)">
            <div class="flex items-center">
              <span class="font-normal ml-3 block truncate text-lg">{{ item.text }}</span>
            </div>
            <span v-if="item.value === selected" class="text-yellow-400 absolute inset-y-0 right-0 flex items-center pr-4">
              <span class="material-icons text-4xl">{{ descending ? 'expand_more' : 'expand_less' }}</span>
            </span>
          </li>
        </template>
      </ul>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    orderBy: String,
    descending: Boolean
  },
  data() {
    return {
      items: [
        {
          text: 'Title',
          value: 'book.title'
        },
        {
          text: 'Author (First Last)',
          value: 'book.authorFL'
        },
        {
          text: 'Author (Last, First)',
          value: 'book.authorLF'
        },
        {
          text: 'Added At',
          value: 'addedAt'
        },
        {
          text: 'Volume #',
          value: 'book.volumeNumber'
        },
        {
          text: 'Duration',
          value: 'duration'
        },
        {
          text: 'Size',
          value: 'size'
        }
      ]
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
    selected: {
      get() {
        return this.orderBy
      },
      set(val) {
        this.$emit('update:orderBy', val)
      }
    },
    selectedDesc: {
      get() {
        return this.descending
      },
      set(val) {
        this.$emit('update:descending', val)
      }
    }
  },
  methods: {
    clickedOption(val) {
      if (this.selected === val) {
        this.selectedDesc = !this.selectedDesc
      } else {
        this.selected = val
      }
      this.show = false
      this.$nextTick(() => this.$emit('change', val))
    }
  },
  mounted() {}
}
</script>