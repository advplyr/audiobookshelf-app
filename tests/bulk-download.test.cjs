const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const script = fs
  .readFileSync(path.join(__dirname, '../components/widgets/BulkDownloadButton.vue'), 'utf8')
  .split('<script>')[1]
  .split('</script>')[0]
  .replace(/^import .*$/gm, '')
  .replace('export default', 'return')

function createDownload(props, { get, submit, local = [], cellular = async () => true, confirm = async () => true }) {
  const calls = []
  const errors = []
  const manifests = new Map()
  const releasedInventories = []
  const inventoryRequests = []
  let nextManifest = 0
  const component = new Function('App', 'Dialog', 'AbsDownloader', 'cellularPermissionHelpers', script)(
    { getState: async () => ({ isActive: true }) },
    { confirm: async () => ({ value: await confirm(manifests) }) },
    {
      getCancellationGeneration: async () => ({ generation: 42 }),
      releaseBulkOfflineItems: async ({ inventoryId }) => releasedInventories.push(inventoryId),
      getBulkOfflineItems: async (payload) => {
        inventoryRequests.push(payload)
        return {
          inventoryId: 'inventory',
          items: local
            .filter((item) => payload.libraryItemIds.includes(item.libraryItemId))
            .map((item) => ({
              hasOfflineBook: true,
              ...item,
              episodeIds: (item.media?.episodes || []).map((episode) => episode.serverEpisodeId)
            }))
        }
      },
      bulkPodcastManifest: async ({ action, id, ids, manifest }) => {
        if (action === 'stage') {
          const key = String(++nextManifest)
          manifests.set(key, manifest)
          return { id: key }
        }
        if (action === 'consume') {
          const staged = manifests.get(id)
          if (!staged) throw new Error('Podcast manifest expired')
          manifests.delete(id)
          return staged
        }
        for (const key of ids) manifests.delete(key)
      },
      downloadLibraryItem: async (payload) => {
        calls.push(payload)
        return submit?.(payload, vm)
      }
    },
    {}
  )
  const vm = {
    ...component.data(),
    ...props,
    canDownload: true,
    connectionId: 'server',
    userId: 'user',
    $store: { state: { networkConnectionType: 'wifi', user: { user: { mediaProgress: [{ episodeId: 'ep-1', currentTime: 80 }] } } } },
    $nativeHttp: { get },
    $db: { getLocalFolders: async () => [], getLocalLibraryItems: async () => local },
    $strings: new Proxy({}, { get: (_, key) => key }),
    $getString: (key, args) => [key, ...args],
    $toast: { error: (error) => errors.push(error), info: () => {} },
    checkCellularPermission: cellular
  }
  for (const [name, fn] of Object.entries(component.methods)) vm[name] = fn.bind(vm)
  return { vm, calls, errors, manifests, releasedInventories, inventoryRequests }
}

function podcast(id, count) {
  return {
    id,
    mediaType: 'podcast',
    media: {
      episodes: Array.from({ length: count }, (_, i) => ({
        id: `ep-${i}`,
        audioFile: { ino: String(i) },
        audioTrack: { metadata: { relPath: `${i}.mp3` } }
      })).concat({ id: 'feed-only', enclosure: { url: 'feed' } })
    }
  }
}

test('a 1,000-episode podcast makes one expanded request and sends one episode per submission', async () => {
  let fetches = 0
  let inFlight = 0
  let peakInFlight = 0
  const { vm, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => {
        fetches++
        return podcast('pod', 1000)
      },
      submit: async () => {
        peakInFlight = Math.max(peakInFlight, ++inFlight)
        await Promise.resolve()
        inFlight--
      }
    }
  )
  await Promise.all([vm.download(), vm.download()])
  assert.equal(fetches, 1)
  assert.equal(calls.length, 1000)
  assert.equal(peakInFlight, 1)
  for (const payload of calls) {
    assert.deepEqual(
      payload.podcastSnapshot.media.episodes.map((episode) => episode.id),
      [payload.episodeId]
    )
    assert.equal(payload.cancellationGeneration, 42)
  }
  assert.equal(new Set(calls.map((call) => call.episodeId)).size, 1000)
  assert.equal(calls[1].podcastSnapshot.userMediaProgress.currentTime, 80)
})

test('a malformed later page leaves the queue untouched', async () => {
  const { vm, calls, errors } = createDownload(
    { libraryId: 'books', mediaType: 'book' },
    {
      get: async (url) => (url.includes('page=0') ? { total: 101, results: Array.from({ length: 100 }, (_, index) => ({ id: `book-${index}` })) } : {})
    }
  )
  await vm.download()
  assert.equal(calls.length, 0)
  assert.deepEqual(errors, ['MessageBulkDownloadPrepareFailed'])
})

test('a Wi-Fi to cellular transition rechecks permission before the next item', async () => {
  let checks = 0
  const { vm, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 2),
      cellular: async () => ++checks === 1,
      submit: async (_, vm) => {
        vm.$store.state.networkConnectionType = 'cellular'
      }
    }
  )
  await vm.download()
  assert.equal(checks, 2)
  assert.equal(calls.length, 1)
})

test('native cancellation stops future submissions', async () => {
  const { vm, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 3),
      submit: async () => ({ submissionStopped: true })
    }
  )
  await vm.download()
  assert.equal(calls.length, 1)
  assert.equal(vm.busy, false)
})

test('a failed item does not prevent later submissions', async () => {
  const { vm, calls, errors } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 3),
      submit: async ({ episodeId }) => (episodeId === 'ep-1' ? { error: 'Could not persist download' } : undefined)
    }
  )
  await vm.download()
  assert.deepEqual(
    calls.map((call) => call.episodeId),
    ['ep-0', 'ep-1', 'ep-2']
  )
  assert.deepEqual(errors, [['MessageBulkDownloadSummary', 2, 0, 1]])
})

test('an account switch stops the next submission', async () => {
  const { vm, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 3),
      submit: async (_, vm) => {
        vm.userId = 'other-user'
      }
    }
  )
  await vm.download()
  assert.equal(calls.length, 1)
  assert.equal(vm.busy, false)
})

test('bulk internal podcast download skips a destination owned by another account', async () => {
  const { vm, calls, errors } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 2),
      local: [{ id: 'local_pod', libraryItemId: 'pod', serverConnectionConfigId: 'server', serverUserId: 'other-user' }]
    }
  )
  await vm.download()
  assert.equal(calls.length, 0)
  assert.deepEqual(errors, [['MessageBulkDownloadSummary', 0, 0, 2]])
})

test('bulk podcast submission carries the existing podcast path in the selected folder', async () => {
  const { vm, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 2),
      local: [
        {
          id: 'local_shared',
          libraryItemId: 'pod',
          folderId: 'shared',
          absolutePath: '/storage/shared/Podcast',
          serverConnectionConfigId: 'server',
          serverUserId: 'user',
          media: { episodes: [{ serverEpisodeId: 'ep-0' }] }
        }
      ]
    }
  )
  vm.chooseFolder = async () => ({ id: 'shared', name: 'Shared' })
  await vm.download()
  assert.deepEqual(
    calls.map(({ episodeId, existingPodcastPath }) => [episodeId, existingPodcastPath]),
    [['ep-1', '/storage/shared/Podcast']]
  )
})

test('switching accounts while the cellular prompt is open stops the batch', async () => {
  let checks = 0
  const { vm, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 3),
      submit: async (_, vm) => {
        vm.$store.state.networkConnectionType = 'cellular'
      },
      cellular: async () => {
        if (++checks === 2) vm.userId = 'replacement'
        return true
      }
    }
  )
  await vm.download()
  assert.equal(checks, 2)
  assert.equal(calls.length, 1)
})

test('duplicate entries across valid-length pages prevent an incomplete library download', async () => {
  const { vm, calls, errors } = createDownload(
    { libraryId: 'books', mediaType: 'book' },
    {
      get: async (url) => ({
        total: 101,
        results: url.includes('page=0') ? Array.from({ length: 100 }, (_, index) => ({ id: `book-${index}` })) : [{ id: 'book-99' }]
      })
    }
  )
  await vm.download()
  assert.equal(calls.length, 0)
  assert.deepEqual(errors, ['MessageBulkDownloadPrepareFailed'])
})

test('whole-library download queues each item across two complete pages', async () => {
  const expected = Array.from({ length: 101 }, (_, index) => `book-${index}`)
  const pages = []
  const { vm, calls } = createDownload(
    { libraryId: 'books', mediaType: 'book' },
    {
      get: async (url) => {
        const page = Number(new URL(url, 'https://server.test').searchParams.get('page'))
        pages.push(page)
        return {
          total: expected.length,
          results: expected.slice(page * 100, (page + 1) * 100).map((id) => ({ id }))
        }
      }
    }
  )
  await vm.download()
  assert.deepEqual(pages, [0, 1])
  assert.deepEqual(
    calls.map((call) => call.libraryItemId),
    expected
  )
})

test('bulk podcast preparation skips episodes without usable audio metadata', async () => {
  const fixture = podcast('pod', 3)
  fixture.media.episodes[1].audioFile.ino = ' '
  fixture.media.episodes[2].audioTrack.metadata.relPath = ''
  const { vm, calls } = createDownload({ podcastId: 'pod', mediaType: 'podcast' }, { get: async () => fixture })
  await vm.download()
  assert.deepEqual(
    calls.map((call) => call.episodeId),
    ['ep-0']
  )
})

test('podcast preflight retains offline metadata and drops unused expanded audio fields', async () => {
  const fixture = podcast('pod', 1)
  const episode = fixture.media.episodes[0]
  episode.description = 'Offline description'
  episode.chapters = [{ id: 1, start: 0, end: 10 }]
  episode.audioFile.metadata = { largeServerOnlyValue: 'unused' }
  episode.audioTrack.metadata.size = 1024
  episode.audioTrack.embeddedCover = 'unused'
  const { vm, calls } = createDownload({ podcastId: 'pod', mediaType: 'podcast' }, { get: async () => fixture })
  await vm.download()
  const [compact] = calls[0].podcastSnapshot.media.episodes
  assert.equal(compact.description, episode.description)
  assert.deepEqual(compact.chapters, episode.chapters)
  assert.equal(compact.audioFile.metadata.size, 1024)
  assert.equal(compact.audioFile.metadata.largeServerOnlyValue, undefined)
  assert.equal(compact.audioTrack.embeddedCover, undefined)
})

test('a later podcast preflight failure clears earlier cached manifests without queueing', async () => {
  const { vm, calls, errors, manifests } = createDownload(
    { libraryId: 'podcasts', mediaType: 'podcast' },
    {
      get: async (url) => {
        if (url.includes('/libraries/')) return { total: 2, results: [{ id: 'first' }, { id: 'second' }] }
        if (url.includes('/items/first')) return podcast('first', 3)
        assert.equal(manifests.size, 1)
        throw new Error('Podcast unavailable')
      }
    }
  )
  await vm.download()
  assert.equal(manifests.size, 0)
  assert.equal(calls.length, 0)
  assert.deepEqual(errors, ['MessageBulkDownloadPrepareFailed'])
})

test('declining confirmation discards staged manifests', async () => {
  const { vm, manifests, calls } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 1),
      confirm: async (staged) => {
        assert.equal(staged.size, 1)
        return false
      }
    }
  )
  await vm.download()
  assert.equal(manifests.size, 0)
  assert.equal(calls.length, 0)
})

test('an evicted manifest counts as failed and later podcasts still queue', async () => {
  const { vm, manifests, calls, errors } = createDownload(
    { libraryId: 'podcasts', mediaType: 'podcast' },
    {
      get: async (url) => {
        if (url.includes('/libraries/')) return { total: 2, results: [{ id: 'first' }, { id: 'second' }] }
        return url.includes('/items/first') ? podcast('first', 2) : podcast('second', 1)
      },
      confirm: async (staged) => {
        staged.delete('1')
        return true
      }
    }
  )
  await vm.download()
  assert.equal(manifests.size, 0)
  assert.deepEqual(
    calls.map((call) => call.libraryItemId),
    ['second']
  )
  assert.deepEqual(errors, [['MessageBulkDownloadSummary', 1, 0, 2]])
})

test('whole-library download queues a book whose local record has missing files', async () => {
  const { vm, calls } = createDownload(
    { libraryId: 'books', mediaType: 'book' },
    {
      get: async () => ({ total: 2, results: [{ id: 'missing' }, { id: 'available' }] }),
      local: [
        { libraryItemId: 'missing', serverConnectionConfigId: 'server', serverUserId: 'user', hasOfflineBook: false },
        { libraryItemId: 'available', serverConnectionConfigId: 'server', serverUserId: 'user' }
      ]
    }
  )
  await vm.download()
  assert.deepEqual(
    calls.map((call) => call.libraryItemId),
    ['missing']
  )
})

test('bulk releases its native completion receipt session when submission stops', async () => {
  const { vm, calls, releasedInventories } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 2),
      submit: async () => ({ submissionStopped: true })
    }
  )
  await vm.download()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].inventoryId, 'inventory')
  assert.deepEqual(releasedInventories, ['inventory'])
})

test('an account switch during podcast metadata preparation queues nothing', async () => {
  const { vm, calls, manifests } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => {
        vm.userId = 'replacement-user'
        return podcast('pod', 2)
      }
    }
  )
  await vm.download()
  assert.equal(calls.length, 0)
  assert.equal(manifests.size, 0)
  assert.equal(vm.busy, false)
})

test('a migrated partial podcast queues its missing episode under the current account', async () => {
  const { vm, calls, errors, inventoryRequests } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 2),
      local: [{ id: 'local_pod', libraryItemId: 'pod', serverConnectionConfigId: 'server', serverUserId: 'old-user', media: { episodes: [{ serverEpisodeId: 'ep-0' }] } }]
    }
  )
  vm.$store.state.user.user.oldUserId = 'old-user'
  await vm.download()
  assert.deepEqual(
    calls.map((call) => call.episodeId),
    ['ep-1']
  )
  assert.equal(calls[0].expectedUserId, 'user')
  assert.equal(inventoryRequests[0].oldUserId, 'old-user')
  assert.deepEqual(errors, [])
})

test('a migrated book with missing files remains eligible for repair', async () => {
  const { vm, calls, errors } = createDownload(
    { libraryId: 'books', mediaType: 'book' },
    {
      get: async () => ({ total: 1, results: [{ id: 'book' }] }),
      local: [{ id: 'local_book', libraryItemId: 'book', serverConnectionConfigId: 'server', serverUserId: 'old-user', hasOfflineBook: false }]
    }
  )
  vm.$store.state.user.user.oldUserId = 'old-user'
  await vm.download()
  assert.deepEqual(
    calls.map((call) => call.libraryItemId),
    ['book']
  )
  assert.deepEqual(errors, [])
})

test('a migrated podcast reuses its existing folder in shared storage', async () => {
  const { vm, calls, errors } = createDownload(
    { podcastId: 'pod', mediaType: 'podcast' },
    {
      get: async () => podcast('pod', 2),
      local: [{ id: 'local_shared', libraryItemId: 'pod', folderId: 'shared', absolutePath: '/storage/shared/Podcast', serverConnectionConfigId: 'server', serverUserId: 'old-user', media: { episodes: [{ serverEpisodeId: 'ep-0' }] } }]
    }
  )
  vm.$store.state.user.user.oldUserId = 'old-user'
  vm.chooseFolder = async () => ({ id: 'shared', name: 'Shared' })
  await vm.download()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].existingPodcastPath, '/storage/shared/Podcast')
  assert.deepEqual(errors, [])
})

for (const owner of [
  { serverConnectionConfigId: 'server', serverUserId: 'unrelated-user' },
  { serverConnectionConfigId: 'another-server', serverUserId: 'old-user' }
]) {
  test(`a migration alias does not authorize an unrelated owner ${JSON.stringify(owner)}`, async () => {
    const { vm, calls, errors } = createDownload(
      { podcastId: 'pod', mediaType: 'podcast' },
      {
        get: async () => podcast('pod', 1),
        local: [{ id: 'local_pod', libraryItemId: 'pod', ...owner }]
      }
    )
    vm.$store.state.user.user.oldUserId = 'old-user'
    await vm.download()
    assert.equal(calls.length, 0)
    assert.deepEqual(errors, [['MessageBulkDownloadSummary', 0, 0, 1]])
  })
}
