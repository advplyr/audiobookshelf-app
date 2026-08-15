# Golden server response fixtures

Response bodies as the audiobookshelf **server** emits them, used by
`server/GoldenResponseFixtureTest` to check that the Android client's models still fit what the
server actually sends.

## Why these exist

Every other server-shaped fixture in this suite is hand-written inside the test that uses it, and
sized to whatever that test asserts. That makes those tests useful for request shape and for the
client's own branching, but blind to the thing most likely to break a release: the **server
changing a serializer**. A hand-written body stays green forever because it is written to match
the model, not the server.

These bodies are the other direction. They carry every field the server emits - including the ones
the client does not model - so that "the server added a field", "the server changed a type", and
"the server renamed a field" all become test failures here rather than a parse error on a user's
device.

## Provenance

Derived from the audiobookshelf server source at **v2.36.0**, from the serializers that produce
each payload:

| Fixture | Server source |
| --- | --- |
| `media-progress.json` | `server/models/MediaProgress.js` → `getOldMediaProgress()` |
| `library.json` | `server/models/Library.js` → `toOldJSON()` |
| `library-item-book.json` | `server/models/LibraryItem.js` → `toOldJSON()`, `server/models/Book.js` → `toOldJSON()` / `oldMetadataToJSON()` |
| `library-item-podcast.json` | as above, plus `server/models/Podcast.js` → `oldMetadataToJSON()` |
| `user.json` | `server/models/User.js` → `toOldJSONForBrowser()` |

Field *names, types and nullability* come from those serializers; the values are representative
rather than captured from a live instance. That distinction matters when reading a failure: a
mismatch here means the client and the 2.36.0 schema disagree, not that a particular server
returned something odd.

## Refreshing them

When targeting a newer server, add a sibling directory rather than editing these in place - the
version in the directory name is the point, and the old fixtures are what prove the client stayed
backward compatible:

```
fixtures/
  server-2.36.0/     <- keep
  server-2.40.0/     <- add
```

Then extend `GoldenResponseFixtureTest.serverVersions` with the new directory. Bodies can be
captured from a running server with the account's API token:

```bash
curl -s -H "Authorization: Bearer $ABS_TOKEN" \
  "$ABS_SERVER/api/items/$ITEM_ID?expanded=1" | jq . > library-item-book.json
```

Strip anything installation-specific (real ids are fine, real paths and tokens are not) before
committing.
