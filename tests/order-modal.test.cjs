const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { resolve } = require('node:path')
const { runInNewContext } = require('node:vm')
const test = require('node:test')
const source = readFileSync(resolve(__dirname, '../components/modals/OrderModal.vue'), 'utf8').split('<script>')[1].split('</script>')[0]
const context = { module: { exports: {} } }
runInNewContext(source.replace('export default', 'module.exports ='), context)
const component = context.module.exports
function state(overrides = {}) {
  return { selected: 'name', selectedDesc: false, resolvedItems: [{ value: 'name', defaultDesc: false }, { value: 'numBooks', defaultDesc: true }], $hapticsImpact: async () => {}, $nextTick: (fn) => fn(), $emit() {}, ...overrides }
}
test('custom sort choices use their default direction, then toggle on reselection', async () => {
  const s = state()
  await component.methods.clickedOption.call(s, 'numBooks')
  assert.equal(s.selectedDesc, true)
  await component.methods.clickedOption.call(s, 'numBooks')
  assert.equal(s.selectedDesc, false)
  await component.methods.clickedOption.call(s, 'name')
  assert.equal(s.selectedDesc, false)
})
test('existing book sorting preserves direction and defaults dates to descending', async () => {
  const s = state({ resolvedItems: [], selectedDesc: true })
  await component.methods.clickedOption.call(s, 'media.metadata.title')
  assert.equal(s.selectedDesc, true)
  s.selectedDesc = false
  await component.methods.clickedOption.call(s, 'addedAt')
  assert.equal(s.selectedDesc, true)
})
test('custom choices take precedence while existing episode and book choices remain available', () => {
  const custom = [{ value: 'name' }]
  assert.equal(component.computed.resolvedItems.call({ items: custom }), custom)
  const episodes = []
  assert.equal(component.computed.resolvedItems.call({ episodes: true, episodeItems: episodes }), episodes)
  const books = []
  assert.equal(component.computed.resolvedItems.call({ bookItems: books }), books)
})
