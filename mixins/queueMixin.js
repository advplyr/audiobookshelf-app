export default {
  methods: {
    addItemToQueue(libraryItem, episode = null) {
      // Determine if this is a local item
      const isLocal = !!libraryItem?.isLocal

      // Get the appropriate IDs
      const localLibraryItemId = isLocal ? libraryItem.id : libraryItem?.localLibraryItem?.id
      const serverLibraryItemId = isLocal ? libraryItem.libraryItemId : libraryItem?.id

      const localEpisodeId = episode && isLocal ? episode.id : episode?.localEpisode?.id
      const serverEpisodeId = episode && isLocal ? episode.serverEpisodeId : episode?.id

      // Get current progress for this item
      let currentTime = 0
      if (episode) {
        // For episodes, get progress from appropriate store
        const progress = isLocal
          ? this.$store.getters['globals/getLocalMediaProgressById'](localLibraryItemId, localEpisodeId)
          : this.$store.getters['user/getUserMediaProgress'](serverLibraryItemId, serverEpisodeId)
        currentTime = progress?.currentTime || 0
      } else {
        // For audiobooks, get progress
        const progress = isLocal
          ? this.$store.getters['globals/getLocalMediaProgressById'](localLibraryItemId)
          : this.$store.getters['user/getUserMediaProgress'](serverLibraryItemId)
        currentTime = progress?.currentTime || 0
      }

      // Build queue item object
      const queueItem = {
        libraryItemId: isLocal ? localLibraryItemId : serverLibraryItemId,
        episodeId: episode ? (isLocal ? localEpisodeId : serverEpisodeId) : null,
        serverLibraryItemId: serverLibraryItemId,
        serverEpisodeId: serverEpisodeId,
        title: episode ? episode.title : libraryItem.media?.metadata?.title,
        author: libraryItem.media?.metadata?.authorName || libraryItem.media?.metadata?.author,
        duration: episode ? episode.duration : libraryItem.media?.duration,
        coverPath: libraryItem.media?.coverPath,
        // For better cover display in queue
        libraryItem: libraryItem,
        episode: episode,
        isLocal: isLocal,
        // Store current progress so playback can resume from where it left off
        currentTime: currentTime
      }

      // Add to queue
      this.$store.dispatch('addToQueue', queueItem)

      // Show success message
      const itemName = episode ? episode.title : libraryItem.media?.metadata?.title
      this.$toast.success(`Added "${itemName}" to queue`)
    }
  }
}
