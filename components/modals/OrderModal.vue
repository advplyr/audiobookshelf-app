<template>
  <modals-modal v-model="show" width="90%">
    <div class="w-full h-full bg-primary rounded-lg border border-fg/20">
      <ul class="w-full rounded-lg text-base" role="listbox" aria-labelledby="listbox-label">
        <template v-for="item in items">
          <li :key="item.value" class="text-fg select-none relative py-4 pr-9 cursor-pointer" :class="item.value === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedOption(item.value)">
            <div class="flex items-center">
              <span class="font-normal ml-3 block truncate text-lg">{{ item.text }}</span>
            </div>
            <span v-if="item.value === selected" class="text-yellow-300 absolute inset-y-0 right-0 flex items-center pr-4">
              <span class="material-icons text-3xl">{{ descending ? 'south' : 'north' }}</span>
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
    descending: Boolean,
    episodes: Boolean
  },
  data() {
    return {
      bookItems: [
        {
          text: this.$strings.LabelTitle,
          value: 'media.metadata.title'
        },
        {
          text: this.$strings.LabelAuthorFirstLast,
          value: 'media.metadata.authorName'
        },
        {
          text: this.$strings.LabelAuthorLastFirst,
          value: 'media.metadata.authorNameLF'
        },
        {
          text: this.$strings.LabelPublishYear,
          value: 'media.metadata.publishedYear'
        },
        {
          text: this.$strings.LabelAddedAt,
          value: 'addedAt'
        },
        {
          text: this.$strings.LabelSize,
          value: 'size'
        },
        {
          text: this.$strings.LabelDuration,
          value: 'media.duration'
        },
        {
          text: this.$strings.LabelFileBirthtime,
          value: 'birthtimeMs'
        },
        {
          text: this.$strings.LabelFileModified,
          value: 'mtimeMs'
        },
        {
          text: this.$strings.LabelRandomly,
          value: 'random'
        }
      ],
      podcastItems: [
        {
          text: this.$strings.LabelTitle,
          value: 'media.metadata.title'
        },
        {
          text: this.$strings.LabelAuthor,
          value: 'media.metadata.author'
        },
        {
          text: this.$strings.LabelAddedAt,
          value: 'addedAt'
        },
        {
          text: this.$strings.LabelSize,
          value: 'size'
        },
        {
          text: this.$strings.LabelNumberOfEpisodes,
          value: 'media.numTracks'
        },
        {
          text: this.$strings.LabelFileBirthtime,
          value: 'birthtimeMs'
        },
        {
          text: this.$strings.LabelFileModified,
          value: 'mtimeMs'
        },
        {
          text: this.$strings.LabelRandomly,
          value: 'random'
        }
      ],
      episodeItems: [
        {
          text: this.$strings.LabelPubDate,
          value: 'publishedAt'
        },
        {
          text: this.$strings.LabelTitle,
          value: 'title'
        },
        {
          text: this.$strings.LabelSeason,
          value: 'season'
        },
        {
          text: this.$strings.LabelEpisode,
          value: 'episode'
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
    },
    isPodcast() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'podcast'
    },
    items() {
      if (this.episodes) return this.episodeItems
      if (this.isPodcast) return this.podcastItems
      return this.bookItems
    }
  },
  methods: {
    async clickedOption(val) {
      await this.$hapticsImpact()
      if (this.selected === val) {
        this.selectedDesc = !this.selectedDesc
      } else {
        if (val === 'recent' || val === 'addedAt') this.selectedDesc = true // Progress defaults to descending
        this.selected = val
      }
      this.show = false
      this.$nextTick(() => this.$emit('change', val))
    }
  },
  mounted() {}
}
</script>
