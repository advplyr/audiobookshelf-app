package com.audiobookshelf.app.managers

/**
 * Download URLs carry the access token in their query string (`?token=...`), so logging one verbatim
 * writes a live credential into logcat — and into any log the user exports or attaches to a bug
 * report. Log the endpoint, never the query.
 */
internal fun redactUrl(url: String?): String {
  if (url.isNullOrEmpty()) return "<no url>"
  val queryStart = url.indexOf('?')
  return if (queryStart >= 0) url.substring(0, queryStart) else url
}
