import Vue from 'vue'
import LazyBookCard from '@/components/cards/LazyBookCard'
import LazySeriesCard from '@/components/cards/LazySeriesCard'
import LazyCollectionCard from '@/components/cards/LazyCollectionCard'

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
      if (this.entityName === 'series') return Vue.extend(LazySeriesCard)
      if (this.entityName === 'collections') return Vue.extend(LazyCollectionCard)
      return Vue.extend(LazyBookCard)
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
      var shelfOffsetY = this.isBookEntity ? 24 : 16
      var row = index % this.entitiesPerShelf

      var marginShiftLeft = 12
      var shelfOffsetX = row * this.totalEntityCardWidth + this.bookshelfMarginLeft + marginShiftLeft

      var ComponentClass = this.getComponentClass()
      var props = {
        index,
        width: this.entityWidth,
        height: this.entityHeight,
        bookCoverAspectRatio: this.bookCoverAspectRatio
      }
      if (this.entityName === 'series-books') props.showVolumeNumber = true

      var _this = this
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
      instance.$el.style.transform = `translate3d(${shelfOffsetX}px, ${shelfOffsetY}px, 0px)`

      instance.$el.classList.add('absolute', 'top-0', 'left-0')
      shelfEl.appendChild(instance.$el)

      if (this.entities[index]) {
        var entity = this.entities[index]
        instance.setEntity(entity)

        if (this.isBookEntity && !entity.isLocal) {
          var localLibraryItem = this.localLibraryItems.find(lli => lli.libraryItemId == entity.id)
          if (localLibraryItem) {
            instance.setLocalLibraryItem(localLibraryItem)
          }
        }
      }
    },
  }
}