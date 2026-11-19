const episodeIndexes = new WeakMap()
const fileIndexes = new WeakMap()

// Recheck the requested ID because playback can reorder these arrays in place.
function indexById(items, cache, id) {
  let indexed = cache.get(items)
  const position = indexed?.positions.get(id)
  if (!indexed || indexed.length !== items.length || (position !== undefined && items[position]?.id !== id)) {
    indexed = { length: items.length, positions: new Map(items.map((item, index) => [item.id, index])) }
    cache.set(items, indexed)
  }
  return indexed
}

// Merge one episode and its files, removing replaced audio only when no episode still uses it.
function mergeEpisode(current, delta) {
  const episodes = current.media.episodes || []
  const indexedEpisodes = indexById(episodes, episodeIndexes, delta.episode.id)
  const previousIndex = indexedEpisodes.positions.get(delta.episode.id)
  const index = previousIndex ?? episodes.length
  const previousFile = episodes[previousIndex]?.audioTrack?.localFileId
  episodes.splice(index, previousIndex === undefined ? 0 : 1, { ...delta.episode, index: index + 1 })
  indexedEpisodes.positions.set(delta.episode.id, index)
  indexedEpisodes.length = episodes.length

  const files = current.localFiles
  let indexedFiles = indexById(files, fileIndexes, previousFile)
  if (previousFile && previousFile !== delta.audioFile?.id && !episodes.some((episode) => episode.audioTrack?.localFileId === previousFile)) {
    const oldIndex = indexedFiles.positions.get(previousFile)
    if (oldIndex !== undefined) {
      files.splice(oldIndex, 1)
      fileIndexes.delete(files)
      indexedFiles = indexById(files, fileIndexes)
    }
  }
  for (const file of [delta.audioFile, delta.coverFile]) {
    if (!file) continue
    indexedFiles = indexById(files, fileIndexes, file.id)
    const existing = indexedFiles.positions.get(file.id)
    const position = existing ?? files.length
    files.splice(position, existing === undefined ? 0 : 1, file)
    indexedFiles.positions.set(file.id, position)
    indexedFiles.length = files.length
  }

  return {
    ...current,
    ...(delta.coverFile ? { coverContentUrl: delta.coverFile.contentUrl, coverAbsolutePath: delta.coverFile.absolutePath } : {}),
    localFiles: files,
    media: { ...current.media, episodes }
  }
}

export default {
  data() {
    return { pendingLocalPodcastReads: new Map() }
  },
  mounted() {
    this.$eventBus.$on('local-podcast-download-delta', this.onLocalPodcastDownloadDelta)
  },
  beforeDestroy() {
    this.$eventBus.$off('local-podcast-download-delta', this.onLocalPodcastDownloadDelta)
    this.pendingLocalPodcastReads.clear()
  },
  methods: {
    // Preserve items added or replaced while the initial database snapshot was loading.
    mergeLocalDownloadSnapshot(snapshot, before) {
      const items = new Map(snapshot.map((item) => [item.id, item]))
      for (const item of this.localLibraryItems) {
        if (before.get(item.id) !== item) items.set(item.id, item)
      }
      return [...items.values()]
    },
    // Merge known podcasts immediately; hydrate an unseen podcast once while collecting later deltas.
    async onLocalPodcastDownloadDelta(delta) {
      if (!this.acceptLocalPodcastDelta(delta)) return
      const pending = this.pendingLocalPodcastReads.get(delta.id)
      if (pending) {
        pending.push(delta)
        return
      }
      const current = this.findLocalPodcastRecord(delta.id)
      if (current) {
        this.newLocalLibraryItem(mergeEpisode(current, delta))
        return
      }

      const subsequentDeltas = []
      this.pendingLocalPodcastReads.set(delta.id, subsequentDeltas)
      try {
        const item = await this.$db.getLocalLibraryItem(delta.id)
        if (!item || this._isDestroyed || !this.acceptLocalPodcastDelta(delta)) return
        // The durable record already contains the first event. Apply updates that arrived during the read.
        this.newLocalLibraryItem(subsequentDeltas.reduce(mergeEpisode, item))
      } catch (error) {
        console.error('Could not load downloaded podcast', error)
      } finally {
        this.pendingLocalPodcastReads.delete(delta.id)
      }
    }
  }
}
