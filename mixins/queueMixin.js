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
      
      // Build queue item object
      const queueItem = {
        libraryItemId: isLocal ? localLibraryItemId : serverLibraryItemId,
        episodeId: episode ? (isLocal ? localEpisodeId : serverEpisodeId) : null,
        serverLibraryItemId: serverLibraryItemId,
        serverEpisodeId: serverEpisodeId,
        title: episode ? episode.title : libraryItem.media?.metadata?.title,
        author: libraryItem.media?.metadata?.authorName || libraryItem.media?.metadata?.author,
        duration: episode ? episode.duration : libraryItem.media?.duration,
        coverPath: libraryItem.media?.coverPath
      }

      // Add to queue
      this.$store.dispatch('addToQueue', queueItem)
      
      // Show success message
      const itemName = episode ? episode.title : libraryItem.media?.metadata?.title
      this.$toast.success(`Added "${itemName}" to queue`)
    }
  }
}