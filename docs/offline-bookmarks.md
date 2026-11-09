# Offline bookmarks

The app stores one bookmark snapshot and pending change queue per server connection and user in Capacitor Preferences. The visible list applies pending changes over the last server snapshot. A change is saved before the UI reports success, and a failed local write leaves the visible list unchanged.

On authenticated startup, network reconnection, socket reconnection, or app resume, the app reads `/api/me` and processes queued changes serially. It reads the user again after each write before removing that change from the queue. A lost response therefore leaves the change queued; the next read can confirm an exact match without repeating the write. Retry delays grow from 5 to 60 seconds. Changes to the same bookmark are coalesced while offline.

The server identifies a bookmark by library item and integer playback second. An exact server match with the same title confirms a pending create. A same-second bookmark with a different title is a conflict. A matching-title bookmark within five seconds is a possible duplicate and pauses that operation. Nearby bookmarks with different titles proceed normally. The app never deletes or merges bookmarks based on proximity, and it imposes no minimum spacing on user-created bookmarks.

When a remote edit or deletion conflicts with a pending local edit, the local version remains visible. The bookmark dialog offers **Use server version** (discard the local operation) or **Keep mine** (apply the local operation). For a same-second create collision, **Keep mine** replaces the server bookmark at that second. For a possible nearby duplicate, **Keep mine** creates a separate bookmark. Server rejections also remain queued for explicit resolution.

The existing server bookmark API supports `title` and `time`, but has no description field. Descriptions are saved only on this device, under the same account and bookmark key. They do not appear on other devices. If the server removes a bookmark, its local description is removed when the app refreshes server state, unless a pending local edit still refers to it.

Without a server idempotency key, exactly-once creation cannot be guaranteed if a server accepts a POST, its response is lost, and the subsequent server read does not show that bookmark. Reconciliation and the five-second ambiguity check reduce this risk while preserving the pending local change for review.

Run `npm run test:bookmarks` for the bookmark tests and `npm run build` for the Nuxt client build. Device testing should include an offline app restart, downloaded-book playback, account switching, a lost response, and a remote edit from another device.
