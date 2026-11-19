const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

function loadComponent(filename, dependencies = {}) {
  const source = fs
    .readFileSync(path.join(__dirname, '..', filename), 'utf8')
    .split('<script>')[1]
    .split('</script>')[0]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'return')
  return new Function(...Object.keys(dependencies), source)(...Object.values(dependencies))
}

test('legacy full-item completion updates offline views and playback progress', () => {
  const component = loadComponent('components/widgets/DownloadProgressIndicator.vue')
  const events = []
  const commits = []
  const localItem = { id: 'local_podcast', media: { episodes: [{ id: 'local_ep_one' }] } }
  const progress = { id: 'local_podcast-local_ep_one', currentTime: 12 }
  component.methods.onItemDownloadComplete.call(
    {
      $eventBus: { $emit: (...args) => events.push(args) },
      $store: { commit: (...args) => commits.push(args) },
      $toast: { error: () => assert.fail('A valid legacy completion was rejected') },
      scheduleProgressUpdate() {}
    },
    { libraryItemId: 'podcast-one', localLibraryItem: localItem, localMediaProgress: progress }
  )
  assert.deepEqual(events, [['new-local-library-item', localItem]])
  assert.deepEqual(commits, [
    ['globals/updateLocalMediaProgress', progress],
    ['globals/removeItemDownload', 'podcast-one']
  ])
})

test('legacy iOS idle events still clear the completed frontend queue', () => {
  const component = loadComponent('components/widgets/DownloadProgressIndicator.vue')
  const commits = []
  component.methods.onQueueChanged.call(
    {
      $store: { commit: (...args) => commits.push(args) },
      scheduleProgressUpdate() {}
    },
    { hasWork: false }
  )
  assert.deepEqual(commits, [['globals/clearItemDownloads']])
})

test('existing audiobook and single-episode actions retain their original native payloads', async () => {
  const payloads = []
  const dependencies = {
    AbsDownloader: {
      downloadLibraryItem: async (payload) => {
        payloads.push(payload)
        return {}
      }
    },
    cellularPermissionHelpers: {},
    localPodcastDownloads: {}
  }
  const item = loadComponent('pages/item/_id/index.vue', dependencies)
  const episode = loadComponent('pages/item/_id/_episode/index.vue', dependencies)
  const vm = { libraryItemId: 'item', episode: { id: 'episode' }, $toast: { error: assert.fail } }
  await item.methods.startDownload.call(vm)
  await item.methods.startDownload.call(vm, { id: 'internal-book', name: 'Internal' })
  await episode.methods.startDownload.call(vm, { id: 'internal-podcast' })
  assert.deepEqual(payloads, [{ libraryItemId: 'item' }, { libraryItemId: 'item', localFolderId: 'internal-book' }, { libraryItemId: 'item', episodeId: 'episode', localFolderId: 'internal-podcast' }])
})

test('feed acquisition still posts selected episodes to the server', async () => {
  const component = loadComponent('components/modals/PodcastEpisodesFeedModal.vue')
  const episodes = [{ id: 'first', enclosure: { url: 'https://example.test/first.mp3' } }, { id: 'second' }]
  const requests = []
  const vm = {
    episodesSelected: ['0'],
    episodes,
    libraryItem: { id: 'podcast' },
    show: true,
    $nativeHttp: { post: async (...args) => requests.push(args) },
    $toast: { success() {}, error: assert.fail }
  }
  component.methods.downloadEpisodes.call(vm)
  await new Promise((resolve) => setImmediate(resolve))
  assert.deepEqual(requests, [['/api/podcasts/podcast/download-episodes', [episodes[0]]]])
  assert.equal(vm.processing, false)
  assert.equal(vm.show, false)
})
