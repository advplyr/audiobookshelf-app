const DownloadStatus = {
  PENDING: 0,
  READY: 1,
  EXPIRED: 2,
  FAILED: 3
}

const SyncStatus = {
  UNSET: 0,
  SUCCESS: 1,
  FAILED: 2
}

const CoverDestination = {
  METADATA: 0,
  AUDIOBOOK: 1
}

const BookCoverAspectRatio = {
  STANDARD: 0,
  SQUARE: 1
}

const PlayMethod = {
  DIRECTPLAY: 0,
  DIRECTSTREAM: 1,
  TRANSCODE: 2,
  LOCAL: 3
}

const PlayerState = {
  IDLE: 0,
  BUFFERING: 1,
  READY: 2,
  ENDED: 3
}

const Constants = {
  DownloadStatus,
  SyncStatus,
  CoverDestination,
  BookCoverAspectRatio,
  PlayMethod,
  PlayerState
}

export {
  PlayerState
}

export default ({ app }, inject) => {
  inject('constants', Constants)
}