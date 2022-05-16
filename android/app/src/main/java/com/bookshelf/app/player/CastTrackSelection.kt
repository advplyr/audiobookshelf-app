/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bookshelf.app.player

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.trackselection.TrackSelection
import com.google.android.exoplayer2.util.Assertions

/**
 * [TrackSelection] that only selects the first track of the provided [TrackGroup].
 *
 *
 * This relies on [CastPlayer] track groups only having one track.
 */
/* package */
internal class CastTrackSelection
/** @param trackGroup The [TrackGroup] from which the first track will only be selected.
 */(private val trackGroup: TrackGroup) : TrackSelection {
  override fun getType(): Int {
    return TrackSelection.TYPE_UNSET
  }

  override fun getTrackGroup(): TrackGroup {
    return trackGroup
  }

  override fun length(): Int {
    return 1
  }

  override fun getFormat(index: Int): Format {
    Assertions.checkArgument(index == 0)
    return trackGroup.getFormat(0)
  }

  override fun getIndexInTrackGroup(index: Int): Int {
    return if (index == 0) 0 else C.INDEX_UNSET
  }

  override fun indexOf(format: Format): Int {
    return if (format === trackGroup.getFormat(0)) 0 else C.INDEX_UNSET
  }

  override fun indexOf(indexInTrackGroup: Int): Int {
    return if (indexInTrackGroup == 0) 0 else C.INDEX_UNSET
  }

  // Object overrides.
  override fun hashCode(): Int {
    return System.identityHashCode(trackGroup)
  }

  // Track groups are compared by identity not value, as distinct groups may have the same value.
  override fun equals(obj: Any?): Boolean {
    if (this === obj) {
      return true
    }
    if (obj == null || javaClass != obj.javaClass) {
      return false
    }
    val other = obj as CastTrackSelection
    return trackGroup === other.trackGroup
  }
}
