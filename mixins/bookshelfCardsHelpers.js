import Vue from 'vue'
import LazyListBookCard from '@/components/cards/LazyListBookCard'

export default {
  data() {
    return {
      cardsHelpers: {
        mountEntityCard: this.mountEntityCard
      }
    }
  },
  methods: {
    getComponentClass() {
      // Always use LazyListBookCard for all entities since we force list view everywhere
      return Vue.extend(LazyListBookCard)
    },
    async mountEntityCard(index) {
      var shelf = Math.floor(index / this.entitiesPerShelf)
      var shelfEl = document.getElementById(`shelf-${shelf}`)
      if (!shelfEl) {
        console.error('mount entity card invalid shelf', shelf, 'book index', index)
        return
      }
      this.entityIndexesMounted.push(index)
      if (this.entityComponentRefs[index]) {
        var bookComponent = this.entityComponentRefs[index]
        shelfEl.appendChild(bookComponent.$el)
        bookComponent.setSelectionMode(false)
        bookComponent.isHovering = false
        return
      }
      // Since everything now uses list view, center the wider items within the shelf container
      var availableWidth = this.bookshelfWidth - 32 // Container has px-4 padding (32px total)
      var overflow = Math.max(0, this.entityWidth - availableWidth)
      var shelfOffsetX = -overflow / 2 // Center by offsetting half the overflow to the left
      var shelfOffsetY = 4

      var ComponentClass = this.getComponentClass()
      var props = {
        index,
        width: this.entityWidth,
        height: this.entityHeight,
        bookCoverAspectRatio: this.bookCoverAspectRatio,
        isAltViewEnabled: this.altViewEnabled
      }
      if (this.entityName === 'series-books') props.showSequence = true
      if (this.entityName === 'books') {
        props.filterBy = this.filterBy
        props.orderBy = this.orderBy
        props.sortingIgnorePrefix = !!this.sortingIgnorePrefix
      }

      // var _this = this
      var instance = new ComponentClass({
        propsData: props,
        created() {
          // this.$on('edit', (entity) => {
          //   if (_this.editEntity) _this.editEntity(entity)
          // })
          // this.$on('select', (entity) => {
          //   if (_this.selectEntity) _this.selectEntity(entity)
          // })
        }
      })
      this.entityComponentRefs[index] = instance
      instance.$mount()

      // Since everything uses list view now, always use absolute positioning
      instance.$el.style.transform = `translate3d(${shelfOffsetX}px, ${shelfOffsetY}px, 0px)`
      instance.$el.classList.add('absolute', 'top-0', 'left-0')

      shelfEl.appendChild(instance.$el)

      if (this.entities[index]) {
        var entity = this.entities[index]
        instance.setEntity(entity)

        if (this.isBookEntity && !entity.isLocal) {
          var localLibraryItem = this.localLibraryItems.find((lli) => lli.libraryItemId == entity.id)
          if (localLibraryItem) {
            instance.setLocalLibraryItem(localLibraryItem)
          }
        }
      }
    }
  }
}
