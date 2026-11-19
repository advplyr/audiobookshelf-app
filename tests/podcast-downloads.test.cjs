const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

test('a compact batch completion removes the queue entry without reporting a scan error', () => {
  const componentPath = path.join(__dirname, '../components/widgets/DownloadProgressIndicator.vue')
  const source = fs
    .readFileSync(componentPath, 'utf8')
    .split('<script>')[1]
    .split('</script>')[0]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'return')
  const component = new Function(source)()
  const events = []
  const commits = []
  const vm = {
    $eventBus: { $emit: (...event) => events.push(event) },
    $store: { commit: (...commit) => commits.push(commit) },
    $toast: { error: () => assert.fail('Unexpected scan error') },
    scheduleProgressUpdate() {}
  }
  const complete = component.methods.onItemDownloadComplete.bind(vm)
  complete({ libraryItemId: 'pod-ep1', localLibraryItemId: 'local_pod' })
  const localItem = { id: 'local_pod' }
  complete({ libraryItemId: 'pod-ep2', localLibraryItemId: 'local_pod', localLibraryItem: localItem })
  assert.deepEqual(commits, [
    ['globals/removeItemDownload', 'pod-ep1'],
    ['globals/removeItemDownload', 'pod-ep2']
  ])
  assert.deepEqual(events, [['new-local-library-item', localItem]])
})

test('a podcast completion emits a compact delta and removes the completed queue row', () => {
  const componentPath = path.join(__dirname, '../components/widgets/DownloadProgressIndicator.vue')
  const source = fs
    .readFileSync(componentPath, 'utf8')
    .split('<script>')[1]
    .split('</script>')[0]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'return')
  const component = new Function(source)()
  const events = []
  const commits = []
  const vm = {
    $eventBus: { $emit: (...event) => events.push(event) },
    $store: { commit: (...commit) => commits.push(commit) },
    $toast: { error: () => assert.fail('Unexpected scan error') },
    scheduleProgressUpdate() {}
  }
  const delta = { id: 'local_pod', episode: { id: 'local_ep_1' } }
  component.methods.onItemDownloadComplete.call(vm, {
    libraryItemId: 'pod-ep1',
    localLibraryItemId: 'local_pod',
    localPodcastDelta: delta
  })
  assert.deepEqual(events, [['local-podcast-download-delta', delta]])
  assert.deepEqual(commits, [['globals/removeItemDownload', 'pod-ep1']])
})

test('a compact delta replaces episode metadata without duplicating it or losing unrelated files', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../mixins/localPodcastDownloads.js'), 'utf8').replace('export default', 'return')
  const mixin = new Function(source)()
  const current = {
    id: 'local_pod',
    localFiles: [{ id: 'old-audio' }, { id: 'other-audio' }, { id: 'cover' }],
    media: {
      episodes: [
        { id: 'episode', index: 1, audioTrack: { localFileId: 'old-audio' } },
        { id: 'other', index: 2, audioTrack: { localFileId: 'other-audio' } }
      ]
    }
  }
  let updated
  await mixin.methods.onLocalPodcastDownloadDelta.call(
    {
      ...mixin.data(),
      acceptLocalPodcastDelta: () => true,
      findLocalPodcastRecord: () => current,
      $db: { getLocalLibraryItem: () => assert.fail('A known podcast should not require a database read') },
      newLocalLibraryItem: (item) => {
        updated = item
      }
    },
    { id: current.id, episode: { id: 'episode', audioTrack: { localFileId: 'new-audio' } }, audioFile: { id: 'new-audio' } }
  )
  assert.deepEqual(
    updated.media.episodes.map((episode) => episode.id),
    ['episode', 'other']
  )
  assert.deepEqual(
    updated.localFiles.map((file) => file.id),
    ['other-audio', 'cover', 'new-audio']
  )
})

test('concurrent compact deltas hydrate an unseen podcast with one database read', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../mixins/localPodcastDownloads.js'), 'utf8').replace('export default', 'return')
  const mixin = new Function(source)()
  let resolveRecord
  let reads = 0
  let updated
  const pendingRecord = new Promise((resolve) => {
    resolveRecord = resolve
  })
  const vm = {
    ...mixin.data(),
    acceptLocalPodcastDelta: () => true,
    findLocalPodcastRecord: () => null,
    $db: {
      getLocalLibraryItem: () => {
        reads++
        return pendingRecord
      }
    },
    newLocalLibraryItem: (item) => {
      updated = item
    }
  }
  const delta = mixin.methods.onLocalPodcastDownloadDelta.bind(vm)
  const first = delta({ id: 'local_pod', episode: { id: 'first' } })
  await delta({ id: 'local_pod', episode: { id: 'second' } })
  resolveRecord({ id: 'local_pod', localFiles: [], media: { episodes: [{ id: 'first' }] } })
  await first
  assert.equal(reads, 1)
  assert.deepEqual(
    updated.media.episodes.map((episode) => episode.id),
    ['first', 'second']
  )
})

test('an initial offline list read cannot overwrite a newer download notification', () => {
  const source = fs.readFileSync(path.join(__dirname, '../mixins/localPodcastDownloads.js'), 'utf8').replace('export default', 'return')
  const mixin = new Function(source)()
  const old = { id: 'podcast', episodes: ['first'] }
  const updated = { id: 'podcast', episodes: ['first', 'second'] }
  const before = new Map([[old.id, old]])
  const snapshot = [{ ...old }, { id: 'other' }]
  const merged = mixin.methods.mergeLocalDownloadSnapshot.call({ localLibraryItems: [updated] }, snapshot, before)
  assert.deepEqual(merged, [updated, { id: 'other' }])
})

test('latest page keeps a completion that arrives while its initial podcast read is pending', async () => {
  const mixinSource = fs.readFileSync(path.join(__dirname, '../mixins/localPodcastDownloads.js'), 'utf8').replace('export default', 'return')
  const mixin = new Function(mixinSource)()
  const source = fs
    .readFileSync(path.join(__dirname, '../pages/bookshelf/latest.vue'), 'utf8')
    .split('<script>')[1]
    .split('</script>')[0]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'return')
  const component = new Function('localPodcastDownloads', source)(mixin)
  let finishRead
  const older = { id: 'pod', mediaType: 'podcast', media: { episodes: [{ id: 'first' }] } }
  const newer = { ...older, media: { episodes: [{ id: 'first' }, { id: 'second' }] } }
  const vm = {
    localLibraryItems: [older],
    $db: {
      getLocalLibraryItems: () =>
        new Promise((resolve) => {
          finishRead = resolve
        })
    },
    mergeLocalDownloadSnapshot: mixin.methods.mergeLocalDownloadSnapshot
  }
  const reading = component.methods.loadLocalPodcastLibraryItems.call(vm)
  component.methods.newLocalLibraryItem.call(vm, newer)
  finishRead([{ ...older }])
  await reading
  assert.equal(vm.localLibraryItems[0], newer)
})

test('a playback sort cannot make the episode index overwrite another episode', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../mixins/localPodcastDownloads.js'), 'utf8').replace('export default', 'return')
  const mixin = new Function(source)()
  let current = { id: 'pod', localFiles: [], media: { episodes: [{ id: 'first' }, { id: 'second' }] } }
  const vm = {
    ...mixin.data(),
    acceptLocalPodcastDelta: () => true,
    findLocalPodcastRecord: () => current,
    newLocalLibraryItem: (item) => {
      current = item
    }
  }
  const update = mixin.methods.onLocalPodcastDownloadDelta.bind(vm)
  await update({ id: 'pod', episode: { id: 'first', title: 'Initial' } })
  current.media.episodes.reverse()
  await update({ id: 'pod', episode: { id: 'first', title: 'Replayed' } })
  assert.deepEqual(
    current.media.episodes.map((episode) => episode.id),
    ['second', 'first']
  )
  assert.equal(current.media.episodes[1].title, 'Replayed')
})
