<template>
  <div
    v-show="playbackSession"
    id="streamContainer"
    :class="{
      fullscreen: showFullscreen,
      'ios-player': $platform === 'ios',
      'web-player': $platform === 'web',
      'fixed pointer-events-none': true
    }"
    :style="{
      zIndex: showFullscreen ? 2147483647 : 50,
      top: showFullscreen ? '0' : 'auto',
      bottom: showFullscreen ? '0' : playerBottomOffset,
      left: '0',
      right: '0',
      height: showFullscreen ? '100vh' : 'auto',
      width: showFullscreen ? '100vw' : 'auto',
      visibility: 'visible',
      backgroundColor: 'transparent',
      position: 'fixed',
      transition: showFullscreen ? 'none' : 'bottom 0.3s ease-in-out'
    }"
  >
    <!-- Full screen player with flexible layout for all screen sizes -->
    <div v-if="showFullscreen" class="fullscreen-container fixed inset-0 pointer-events-auto bg-surface-dynamic" :class="{ 'landscape-layout': isLandscape }" :style="{ top: fullscreenTopPadding, height: `calc(100vh - ${fullscreenTopPadding})`, zIndex: 9999 }">
      <!-- Background coverage -->
      <div class="absolute inset-0 pointer-events-none bg-surface-dynamic" :style="{ top: fullscreenTopPadding, height: `calc(100vh - ${fullscreenTopPadding})` }" />

      <div class="top-4 left-4 absolute">
        <button class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-2 transition-all duration-200 hover:shadow-elevation-3 active:scale-95" @click="collapseFullscreen">
          <span class="material-symbols text-2xl text-on-surface">keyboard_arrow_down</span>
        </button>
      </div>
      <div v-show="showCastBtn" class="top-4 right-36 absolute">
        <button class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-2 transition-all duration-200 hover:shadow-elevation-3 active:scale-95" @click="castClick">
          <span class="material-symbols text-xl text-on-surface">{{ isCasting ? 'cast_connected' : 'cast' }}</span>
        </button>
      </div>
      <div class="top-4 right-20 absolute">
        <button class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-2 transition-all duration-200 hover:shadow-elevation-3 active:scale-95" :disabled="!chapters.length" @click="clickChaptersBtn">
          <span class="material-symbols text-xl text-on-surface" :class="chapters.length ? '' : 'opacity-30'">format_list_bulleted</span>
        </button>
      </div>
      <div class="top-4 right-4 absolute">
        <button class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-2 transition-all duration-200 hover:shadow-elevation-3 active:scale-95" @click="showMoreMenuDialog = true">
          <span class="material-symbols text-xl text-on-surface">more_vert</span>
        </button>
      </div>
      <p v-if="!isLandscape" class="top-16 absolute left-0 right-0 mx-auto text-center uppercase tracking-widest text-on-surface-variant opacity-75" style="font-size: 10px">{{ isDirectPlayMethod ? $strings.LabelPlaybackDirect : isLocalPlayMethod ? $strings.LabelPlaybackLocal : $strings.LabelPlaybackTranscode }}</p>

      <!-- Portrait Layout (existing) -->
      <template v-if="!isLandscape">
        <!-- Fullscreen Cover Image with responsive sizing -->
        <div class="cover-wrapper-portrait flex justify-center items-start pointer-events-auto" @click="collapseFullscreen">
          <div class="cover-container relative">
            <covers-book-cover v-if="libraryItem || localLibraryItemCoverSrc" ref="cover" :library-item="libraryItem" :download-cover="localLibraryItemCoverSrc" :width="bookCoverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" raw @imageLoaded="coverImageLoaded" />

            <div v-if="syncStatus === $constants.SyncStatus.FAILED" class="absolute inset-0 flex items-center justify-center z-10" @click.stop="showSyncsFailedDialog">
              <span class="material-symbols text-error text-3xl">error</span>
            </div>
          </div>
        </div>

        <!-- Fullscreen Controls with responsive positioning -->
        <div id="playerControls" class="controls-container-portrait pointer-events-auto">
          <!-- Main playback controls row -->
          <div class="flex items-center max-w-full mb-4" :class="playerSettings.lockUi ? 'justify-center' : 'justify-between'">
            <button v-show="showFullscreen && !playerSettings.lockUi" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" :disabled="isLoading" @click.stop="jumpChapterStart">
              <span class="material-symbols text-xl text-on-surface" :class="isLoading ? 'opacity-30' : ''">first_page</span>
            </button>
            <button v-show="!playerSettings.lockUi" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" :disabled="isLoading" @click.stop="jumpBackwards">
              <span class="material-symbols text-xl text-on-surface" :class="isLoading ? 'opacity-30' : ''">{{ jumpBackwardsIcon }}</span>
            </button>
            <button class="w-16 h-16 rounded-full bg-primary text-on-primary flex items-center justify-center shadow-elevation-3 transition-all duration-200 hover:shadow-elevation-4 active:scale-95 mx-4 relative overflow-hidden" :class="{ 'animate-spin': seekLoading }" :disabled="isLoading" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
              <span v-if="!isLoading" class="material-symbols text-2xl text-on-primary">{{ seekLoading ? 'autorenew' : !isPlaying ? 'play_arrow' : 'pause' }}</span>
              <widgets-spinner-icon v-else class="h-6 w-6" />
            </button>
            <button v-show="!playerSettings.lockUi" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" :disabled="isLoading" @click.stop="jumpForward">
              <span class="material-symbols text-xl text-on-surface" :class="isLoading ? 'opacity-30' : ''">{{ jumpForwardIcon }}</span>
            </button>
            <button v-show="showFullscreen && !playerSettings.lockUi" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" :disabled="!nextChapter || isLoading" @click.stop="jumpNextChapter">
              <span class="material-symbols text-xl text-on-surface" :class="nextChapter && !isLoading ? '' : 'opacity-30'">last_page</span>
            </button>
          </div>

          <!-- Secondary controls row - Sleep Timer, Speed, and Bookmarks -->
          <div v-show="showFullscreen && !playerSettings.lockUi" class="flex items-center justify-center space-x-8">
            <!-- Sleep Timer Button (under and between back and play buttons) -->
            <button v-if="!sleepTimerRunning" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click.stop="$emit('showSleepTimer')">
              <span class="material-symbols text-xl text-on-surface">bedtime</span>
            </button>
            <button v-else class="px-3 py-2 rounded-full bg-tertiary-container text-on-tertiary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click.stop="$emit('showSleepTimer')">
              <span class="text-sm font-mono font-medium">{{ sleepTimeRemainingPretty }}</span>
            </button>

            <!-- Speed Button (under and between play and forward buttons) -->
            <button class="px-4 py-2 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="$emit('selectPlaybackSpeed')">
              <span class="font-mono text-sm font-medium">{{ currentPlaybackRate }}x</span>
            </button>

            <!-- Bookmarks Button -->
            <button class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="$emit('showBookmarks')">
              <span class="material-symbols text-xl text-on-surface" :class="{ fill: bookmarks.length }">bookmark</span>
            </button>
          </div>
        </div>

        <!-- Progress Bars Container - manages both tracks -->
        <div v-if="showFullscreen" id="progressBarsContainer" class="absolute left-0 right-0 mx-auto w-full px-6 bottom-56" style="max-width: 414px">
          <!-- Total Progress Track (shown when both tracks enabled) -->
          <div v-if="playerSettings.useChapterTrack && playerSettings.useTotalTrack" class="mb-6">
            <div class="flex mb-1">
              <p class="font-mono text-on-surface-variant text-xs">{{ currentTimePretty }}</p>
              <div class="flex-grow" />
              <p class="font-mono text-on-surface-variant text-xs">{{ totalTimeRemainingPretty }}</p>
            </div>
            <div class="w-full">
              <div class="h-1 w-full bg-surface-variant/50 relative rounded-full">
                <div ref="totalReadyTrack" class="h-full bg-outline/60 absolute top-0 left-0 pointer-events-none rounded-full" />
                <div ref="totalBufferedTrack" class="h-full bg-on-surface-variant/60 absolute top-0 left-0 pointer-events-none rounded-full" />
                <div ref="totalPlayedTrack" class="h-full bg-primary/80 absolute top-0 left-0 pointer-events-none rounded-full" />
              </div>
            </div>
          </div>

          <!-- Main Progress Track -->
          <div>
            <div class="flex pointer-events-none mb-2">
              <p class="font-mono text-on-surface text-sm" ref="currentTimestampFull">0:00</p>
              <div class="flex-grow" />
              <p class="font-mono text-on-surface text-sm">{{ timeRemainingPretty }}</p>
            </div>
            <div ref="trackFull" class="h-2 w-full relative rounded-full bg-surface-variant shadow-inner cursor-pointer transition-all duration-200 ease-expressive hover:bg-surface-variant/80 hover:shadow-md active:bg-surface-variant/90 select-none" :class="{ 'animate-pulse': isLoading }" @click.stop="seekToPosition" @mousedown="startDragSeek" @touchstart="startDragSeek">
              <div ref="readyTrackFull" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-outline transition-all duration-500 ease-expressive" />
              <div ref="bufferedTrackFull" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-on-surface-variant transition-all duration-500 ease-expressive" />
              <div ref="playedTrackFull" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-primary transition-all duration-300 ease-expressive hover:bg-primary/90" />
              <div
                ref="trackCursorFull"
                class="h-6 w-6 rounded-full absolute pointer-events-auto flex items-center justify-center shadow-elevation-2 bg-primary transition-all duration-200 ease-expressive hover:scale-110 hover:shadow-elevation-3 active:scale-95 active:shadow-elevation-1"
                :style="{ top: '-8px' }"
                :class="{ 'opacity-0': playerSettings.lockUi || !showFullscreen }"
                @touchstart.stop="touchstartCursor"
              >
                <div class="rounded-full w-3 h-3 pointer-events-none bg-on-primary transition-all duration-200 ease-expressive" />
              </div>
            </div>
          </div>
        </div>

        <!-- Fullscreen Title and Author - positioned below progress bars -->
        <div v-if="showFullscreen" class="title-author-texts absolute z-30 left-0 right-0 bottom-48 px-6 text-center overflow-hidden" @click="collapseFullscreen">
          <div ref="titlewrapper" class="overflow-hidden relative">
            <p class="title-text whitespace-nowrap text-on-surface text-lg font-medium">{{ title }}</p>
          </div>
          <p class="author-text text-on-surface-variant text-sm truncate">{{ authorName }}</p>
        </div>
      </template>

      <!-- Landscape Layout -->
      <template v-else>
        <!-- Top Action Bar for Landscape -->
        <div class="landscape-top-bar absolute top-0 left-0 right-0 z-40 flex items-center justify-between p-3" style="height: 60px">
          <!-- Left: Close Button -->
          <button class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="collapseFullscreen">
            <span class="material-symbols text-xl text-on-surface">keyboard_arrow_down</span>
          </button>

          <!-- Right: Action Buttons -->
          <div class="flex items-center space-x-2">
            <button v-show="showCastBtn" class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="castClick">
              <span class="material-symbols text-lg text-on-surface">{{ isCasting ? 'cast_connected' : 'cast' }}</span>
            </button>
            <button class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" :disabled="!chapters.length" @click="clickChaptersBtn">
              <span class="material-symbols text-lg text-on-surface" :class="chapters.length ? '' : 'opacity-30'">format_list_bulleted</span>
            </button>
            <button class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="showMoreMenuDialog = true">
              <span class="material-symbols text-lg text-on-surface">more_vert</span>
            </button>
          </div>
        </div>

        <!-- Landscape Content Container with flexible grid -->
        <div class="landscape-content-container flex" :style="{ top: '60px', height: `calc(100vh - 60px - ${fullscreenTopPadding})`, padding: '20px' }">
          <!-- Left Side: Cover Image -->
          <div class="landscape-cover-section flex items-center justify-center" style="flex: 0 0 45%; min-width: 0">
            <div class="cover-wrapper-landscape relative pointer-events-auto" @click="collapseFullscreen">
              <div class="cover-container-landscape">
                <covers-book-cover v-if="libraryItem || localLibraryItemCoverSrc" ref="cover" :library-item="libraryItem" :download-cover="localLibraryItemCoverSrc" :width="landscapeBookCoverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" raw @imageLoaded="coverImageLoaded" />

                <div v-if="syncStatus === $constants.SyncStatus.FAILED" class="absolute inset-0 flex items-center justify-center z-10" @click.stop="showSyncsFailedDialog">
                  <span class="material-symbols text-error text-3xl">error</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Right Side: Controls and Content -->
          <div class="landscape-controls-section flex flex-col justify-center overflow-hidden" style="flex: 1; min-width: 0; padding-left: 20px">
            <!-- Title and Author -->
            <div class="title-author-texts-landscape mb-4 text-left">
              <div ref="titlewrapper" class="overflow-hidden relative">
                <p class="title-text whitespace-nowrap text-on-surface text-xl font-medium truncate">{{ title }}</p>
              </div>
              <p class="author-text text-on-surface-variant text-base truncate mt-1">{{ authorName }}</p>
            </div>

            <!-- Progress Bars Container -->
            <div class="landscape-progress-container mb-4">
              <!-- Total Progress Track (shown when both tracks enabled) -->
              <div v-if="playerSettings.useChapterTrack && playerSettings.useTotalTrack" class="mb-3">
                <div class="flex mb-1">
                  <p class="font-mono text-on-surface-variant text-xs">{{ currentTimePretty }}</p>
                  <div class="flex-grow" />
                  <p class="font-mono text-on-surface-variant text-xs">{{ totalTimeRemainingPretty }}</p>
                </div>
                <div class="w-full">
                  <div class="h-1 w-full bg-surface-variant/50 relative rounded-full">
                    <div ref="totalReadyTrack" class="h-full bg-outline/60 absolute top-0 left-0 pointer-events-none rounded-full" />
                    <div ref="totalBufferedTrack" class="h-full bg-on-surface-variant/60 absolute top-0 left-0 pointer-events-none rounded-full" />
                    <div ref="totalPlayedTrack" class="h-full bg-primary/80 absolute top-0 left-0 pointer-events-none rounded-full" />
                  </div>
                </div>
              </div>

              <!-- Main Progress Track -->
              <div>
                <div class="flex pointer-events-none mb-1">
                  <p class="font-mono text-on-surface text-sm" ref="currentTimestampFull">0:00</p>
                  <div class="flex-grow" />
                  <p class="font-mono text-on-surface text-sm">{{ timeRemainingPretty }}</p>
                </div>
                <div ref="trackFull" class="h-2 w-full relative rounded-full bg-surface-variant shadow-inner cursor-pointer transition-all duration-200 ease-expressive hover:bg-surface-variant/80 hover:shadow-md active:bg-surface-variant/90 select-none" :class="{ 'animate-pulse': isLoading }" @click.stop="seekToPosition" @mousedown="startDragSeek" @touchstart="startDragSeek">
                  <div ref="readyTrackFull" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-outline transition-all duration-500 ease-expressive" />
                  <div ref="bufferedTrackFull" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-on-surface-variant transition-all duration-500 ease-expressive" />
                  <div ref="playedTrackFull" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-primary transition-all duration-300 ease-expressive hover:bg-primary/90" />
                  <div
                    ref="trackCursorFull"
                    class="h-6 w-6 rounded-full absolute pointer-events-auto flex items-center justify-center shadow-elevation-2 bg-primary transition-all duration-200 ease-expressive hover:scale-110 hover:shadow-elevation-3 active:scale-95 active:shadow-elevation-1"
                    :style="{ top: '-8px' }"
                    :class="{ 'opacity-0': playerSettings.lockUi || !showFullscreen }"
                    @touchstart.stop="touchstartCursor"
                  >
                    <div class="rounded-full w-3 h-3 pointer-events-none bg-on-primary transition-all duration-200 ease-expressive" />
                  </div>
                </div>
              </div>
            </div>

            <!-- Main playback controls -->
            <div class="landscape-main-controls flex items-center justify-center mb-3">
              <button v-show="!playerSettings.lockUi" class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95 mr-2" :disabled="isLoading" @click.stop="jumpChapterStart">
                <span class="material-symbols text-lg text-on-surface" :class="isLoading ? 'opacity-30' : ''">first_page</span>
              </button>
              <button v-show="!playerSettings.lockUi" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95 mr-2" :disabled="isLoading" @click.stop="jumpBackwards">
                <span class="material-symbols text-xl text-on-surface" :class="isLoading ? 'opacity-30' : ''">{{ jumpBackwardsIcon }}</span>
              </button>
              <button class="w-16 h-16 rounded-full bg-primary text-on-primary flex items-center justify-center shadow-elevation-3 transition-all duration-200 hover:shadow-elevation-4 active:scale-95 mx-3 relative overflow-hidden" :class="{ 'animate-spin': seekLoading }" :disabled="isLoading" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
                <span v-if="!isLoading" class="material-symbols text-2xl text-on-primary">{{ seekLoading ? 'autorenew' : !isPlaying ? 'play_arrow' : 'pause' }}</span>
                <widgets-spinner-icon v-else class="h-6 w-6" />
              </button>
              <button v-show="!playerSettings.lockUi" class="w-12 h-12 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95 ml-2" :disabled="isLoading" @click.stop="jumpForward">
                <span class="material-symbols text-xl text-on-surface" :class="isLoading ? 'opacity-30' : ''">{{ jumpForwardIcon }}</span>
              </button>
              <button v-show="!playerSettings.lockUi" class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95 ml-2" :disabled="!nextChapter || isLoading" @click.stop="jumpNextChapter">
                <span class="material-symbols text-lg text-on-surface" :class="nextChapter && !isLoading ? '' : 'opacity-30'">last_page</span>
              </button>
            </div>

            <!-- Secondary controls row - Sleep Timer, Speed, and Bookmarks -->
            <div v-show="!playerSettings.lockUi" class="landscape-secondary-controls flex items-center justify-center space-x-3 mt-2">
              <!-- Sleep Timer Button -->
              <button v-if="!sleepTimerRunning" class="w-9 h-9 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click.stop="$emit('showSleepTimer')">
                <span class="material-symbols text-base text-on-surface">bedtime</span>
              </button>
              <button v-else class="px-2 py-1 rounded-full bg-tertiary-container text-on-tertiary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click.stop="$emit('showSleepTimer')">
                <span class="text-xs font-mono font-medium">{{ sleepTimeRemainingPretty }}</span>
              </button>

              <!-- Speed Button -->
              <button class="px-3 py-1 rounded-full bg-primary-container text-on-primary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="$emit('selectPlaybackSpeed')">
                <span class="font-mono text-xs font-medium">{{ currentPlaybackRate }}x</span>
              </button>

              <!-- Bookmarks Button -->
              <button class="w-9 h-9 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95" @click="$emit('showBookmarks')">
                <span class="material-symbols text-base text-on-surface" :class="{ fill: bookmarks.length }">bookmark</span>
              </button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div
      id="playerContent"
      class="playerContainer w-full pointer-events-auto bg-player-overlay backdrop-blur-md shadow-elevation-3 border-t border-outline-variant border-opacity-20"
      :class="{ 'transition-all duration-500 ease-expressive': !isSwipeActive }"
      :style="{ backgroundColor: showFullscreen ? '' : '', transform: showFullscreen ? 'translateY(-100vh)' : `translateY(${swipeOffset}px) translateX(${swipeOffsetX}px)`, zIndex: showFullscreen ? '9999' : '50' }"
      @touchstart="handleTouchStart"
      @touchmove="handleTouchMove"
      @touchend="handleTouchEnd"
    >
      <!-- Collapsed player layout: Cover → Text → Controls -->
      <div v-if="!showFullscreen" class="flex items-center h-full px-2">
        <!-- Cover Image -->
        <div class="cover-wrapper-mini flex-shrink-0 mr-2" @click="expandFullscreen">
          <covers-book-cover v-if="libraryItem || localLibraryItemCoverSrc" ref="cover" :library-item="libraryItem" :download-cover="localLibraryItemCoverSrc" :width="bookCoverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" raw @imageLoaded="coverImageLoaded" />
        </div>

        <!-- Text Content -->
        <div class="flex-1 min-w-0 mr-2" @click="expandFullscreen">
          <div ref="titlewrapper" class="overflow-hidden relative">
            <p class="title-text whitespace-nowrap truncate text-on-surface text-sm font-medium">{{ title }}</p>
          </div>
          <p class="author-text text-on-surface-variant text-xs truncate">{{ authorName }}</p>
        </div>

        <!-- Controls -->
        <div class="flex items-center flex-shrink-0">
          <button v-show="!playerSettings.lockUi" class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95 mr-1" :disabled="isLoading" @click.stop="jumpBackwards">
            <span class="material-symbols text-lg text-on-surface" :class="isLoading ? 'opacity-30' : ''">{{ jumpBackwardsIcon }}</span>
          </button>
          <button class="w-12 h-12 rounded-full bg-primary text-on-primary flex items-center justify-center shadow-elevation-2 transition-all duration-200 hover:shadow-elevation-3 active:scale-95 mx-2 relative overflow-hidden" :class="{ 'animate-spin': seekLoading }" :disabled="isLoading" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
            <span v-if="!isLoading" class="material-symbols text-xl text-on-primary">{{ seekLoading ? 'autorenew' : !isPlaying ? 'play_arrow' : 'pause' }}</span>
            <widgets-spinner-icon v-else class="h-5 w-5" />
          </button>
          <button v-show="!playerSettings.lockUi" class="w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center shadow-elevation-1 transition-all duration-200 hover:shadow-elevation-2 active:scale-95 ml-1" :disabled="isLoading" @click.stop="jumpForward">
            <span class="material-symbols text-lg text-on-surface" :class="isLoading ? 'opacity-30' : ''">{{ jumpForwardIcon }}</span>
          </button>
        </div>
      </div>

      <!-- Progress Bar -->
      <div v-if="!showFullscreen" id="playerTrackMini" class="absolute bottom-2 left-0 w-full px-2">
        <div ref="trackMini" class="h-1 w-full relative rounded-full bg-surface-variant shadow-inner cursor-pointer transition-all duration-200 ease-expressive hover:bg-surface-variant/80 hover:shadow-md active:bg-surface-variant/90 select-none" :class="{ 'animate-pulse': isLoading }" @click.stop="seekToPosition" @mousedown="startDragSeek" @touchstart="startDragSeek">
          <div ref="readyTrackMini" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-outline transition-all duration-500 ease-expressive" />
          <div ref="bufferedTrackMini" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-on-surface-variant transition-all duration-500 ease-expressive" />
          <div ref="playedTrackMini" class="h-full absolute top-0 left-0 rounded-full pointer-events-none bg-primary transition-all duration-300 ease-expressive hover:bg-primary/90" />
        </div>
      </div>
    </div>

    <modals-chapters-modal v-model="showChapterModal" :current-chapter="currentChapter" :chapters="chapters" :playback-rate="currentPlaybackRate" @select="selectChapter" />
    <modals-dialog v-model="showMoreMenuDialog" :items="menuItems" width="80vw" @action="clickMenuAction" />
    <modals-cast-device-selection-modal ref="castDeviceModal" @cast-device-connected="onCastDeviceConnected" @cast-device-disconnected="onCastDeviceDisconnected" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
import WrappingMarquee from '@/assets/WrappingMarquee.js'

export default {
  props: {
    bookmarks: {
      type: Array,
      default: () => []
    },
    sleepTimerRunning: Boolean,
    sleepTimeRemaining: Number,
    serverLibraryItemId: String
  },
  data() {
    return {
      windowHeight: 0,
      windowWidth: 0,
      playbackSession: null,
      showChapterModal: false,
      showFullscreen: false,
      totalDuration: 0,
      currentPlaybackRate: 1,
      currentTime: 0,
      bufferedTime: 0,
      playInterval: null,
      trackWidth: 0,
      isPlaying: false,
      isEnded: false,
      volume: 0.5,
      readyTrackWidth: 0,
      seekedTime: 0,
      seekLoading: false,
      touchStartY: 0,
      touchStartTime: 0,
      swipeOffset: 0,
      isSwipeActive: false,
      swipeStartY: 0,
      // Horizontal swipe state for closing mini player
      swipeStartX: 0,
      swipeOffsetX: 0,
      isHorizontalSwipeActive: false,
      horizontalSwipeThreshold: 80,
      // Gesture axis locking: 'none' | 'vertical' | 'horizontal'
      gestureAxis: 'none',
      // Minimum move (px) before deciding axis
      gestureDetectionThreshold: 8,
      swipeThreshold: 50, // pixels to trigger fullscreen
      playerSettings: {
        useChapterTrack: false,
        useTotalTrack: true,
        scaleElapsedTimeBySpeed: true,
        lockUi: false
      },
      isLoading: false,
      isDraggingCursor: false,
      draggingTouchStartX: 0,
      draggingTouchStartTime: 0,
      draggingCurrentTime: 0,
      // New drag-to-seek properties
      isDraggingSeek: false,
      draggingTrackElement: null,
      draggingStartTime: 0,
      draggingStartX: 0,
      draggingTrackRect: null,
      syncStatus: 0,
      showMoreMenuDialog: false,
      titleMarquee: null,
      isRefreshingUI: false,
      fullscreenTopPadding: '0px',
      miniPlayerPositionsReady: false,
      _safeAreaObserver: null
    }
  },
  watch: {
    showFullscreen(val) {
      this.updateScreenSize()
      this.$store.commit('setPlayerFullscreen', !!val)
    },
    bookCoverAspectRatio() {
      this.updateScreenSize()
    },
    title(val) {
      if (this.titleMarquee) this.titleMarquee.init(val)
    }
  },
  computed: {
    theme() {
      return document.documentElement.dataset.theme || 'dark'
    },
    menuItems() {
      const items = []
      // TODO: Implement on iOS
      if (this.$platform !== 'ios' && !this.isPodcast && this.mediaId) {
        items.push({
          text: this.$strings.ButtonHistory,
          value: 'history',
          icon: 'history'
        })
      }

      items.push(
        ...[
          {
            text: this.$strings.LabelTotalTrack,
            value: 'total_track',
            icon: this.playerSettings.useTotalTrack ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.$strings.LabelChapterTrack,
            value: 'chapter_track',
            icon: this.playerSettings.useChapterTrack ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.$strings.LabelScaleElapsedTimeBySpeed,
            value: 'scale_elapsed_time',
            icon: this.playerSettings.scaleElapsedTimeBySpeed ? 'check_box' : 'check_box_outline_blank'
          },
          {
            text: this.playerSettings.lockUi ? this.$strings.LabelUnlockPlayer : this.$strings.LabelLockPlayer,
            value: 'lock',
            icon: this.playerSettings.lockUi ? 'lock' : 'lock_open'
          },
          {
            text: this.$strings.LabelClosePlayer,
            value: 'close',
            icon: 'close'
          }
        ]
      )

      return items
    },
    jumpForwardIcon() {
      return this.$store.getters['globals/getJumpForwardIcon'](this.jumpForwardTime)
    },
    jumpBackwardsIcon() {
      return this.$store.getters['globals/getJumpBackwardsIcon'](this.jumpBackwardsTime)
    },
    jumpForwardTime() {
      return this.$store.getters['getJumpForwardTime']
    },
    jumpBackwardsTime() {
      return this.$store.getters['getJumpBackwardsTime']
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    isLandscape() {
      const result = this.windowWidth > this.windowHeight
      if (this.showFullscreen) {
        console.log('[AudioPlayer] Landscape check:', {
          windowWidth: this.windowWidth,
          windowHeight: this.windowHeight,
          isLandscape: result,
          coverWidth: this.fullscreenBookCoverWidth
        })
      }
      return result
    },
    bookCoverWidth() {
      if (this.showFullscreen) return this.fullscreenBookCoverWidth
      return 48 / this.bookCoverAspectRatio
    },
    fullscreenBookCoverWidth() {
      if (this.windowWidth < this.windowHeight) {
        // Portrait
        let sideSpace = 20
        if (this.bookCoverAspectRatio === 1.6) sideSpace += (this.windowWidth - sideSpace) * 0.375

        const availableHeight = this.windowHeight - 400
        let width = this.windowWidth - sideSpace
        const totalHeight = width * this.bookCoverAspectRatio
        if (totalHeight > availableHeight) {
          width = availableHeight / this.bookCoverAspectRatio
        }
        return width
      } else {
        // Landscape - proper height calculation accounting for all UI elements
        const topButtonsHeight = 70 // Space for top buttons
        const titleHeight = 80 // Space for title and author
        const progressHeight = 80 // Space for progress bars
        const controlsHeight = 100 // Space for main controls
        const bottomControlsHeight = 120 // Space for bottom controls row
        const padding = 40 // General padding

        const availableHeight = this.windowHeight - topButtonsHeight - titleHeight - progressHeight - controlsHeight - bottomControlsHeight - padding
        const availableWidth = this.windowWidth * 0.45 // 45% of screen width for cover area

        // Calculate based on both constraints
        let widthBasedOnHeight = availableHeight / this.bookCoverAspectRatio
        let heightBasedOnWidth = availableWidth * this.bookCoverAspectRatio

        // Use the constraint that gives us the smaller size to ensure it fits
        let finalWidth
        if (heightBasedOnWidth <= availableHeight) {
          // Width is the limiting factor
          finalWidth = availableWidth
        } else {
          // Height is the limiting factor
          finalWidth = widthBasedOnHeight
        }

        // Ensure minimum reasonable size but max out to prevent overflow
        const minWidth = Math.min(200, availableWidth * 0.7)
        const maxWidth = Math.min(300, availableWidth * 0.9)
        finalWidth = Math.max(Math.min(finalWidth, maxWidth), minWidth)

        console.log('AudioPlayer: landscape cover size - available height:', availableHeight, 'final width:', finalWidth)
        return finalWidth
      }
    },
    landscapeBookCoverWidth() {
      if (!this.isLandscape) return this.bookCoverWidth

      // Use much more aggressive sizing for landscape
      const availableHeight = this.windowHeight - 120 // Account for top bar and padding
      const availableWidth = this.windowWidth * 0.45 - 40 // 45% of width minus padding

      // Calculate based on aspect ratio and available space
      const aspectRatio = this.bookCoverAspectRatio
      let finalWidth = Math.min(availableWidth, availableHeight / aspectRatio)

      // Ensure good minimum size for landscape
      const minWidth = Math.min(250, availableWidth * 0.8)
      const maxWidth = Math.min(400, availableWidth)
      finalWidth = Math.max(Math.min(finalWidth, maxWidth), minWidth)

      console.log('AudioPlayer: landscape cover width - available space:', availableWidth, 'x', availableHeight, 'final width:', finalWidth)
      return finalWidth
    },
    showCastBtn() {
      return this.$store.state.isCastAvailable
    },
    isCasting() {
      return this.mediaPlayer === 'cast-player'
    },
    mediaPlayer() {
      return this.playbackSession?.mediaPlayer || null
    },
    mediaType() {
      return this.playbackSession?.mediaType || null
    },
    isPodcast() {
      return this.mediaType === 'podcast'
    },
    mediaMetadata() {
      return this.playbackSession?.mediaMetadata || null
    },
    libraryItem() {
      return this.playbackSession?.libraryItem || null
    },
    localLibraryItem() {
      return this.playbackSession?.localLibraryItem || null
    },
    localLibraryItemCoverSrc() {
      var localItemCover = this.localLibraryItem?.coverContentUrl || null
      if (localItemCover) return Capacitor.convertFileSrc(localItemCover)
      return null
    },
    playMethod() {
      return this.playbackSession?.playMethod || 0
    },
    isLocalPlayMethod() {
      return this.playMethod == this.$constants.PlayMethod.LOCAL
    },
    isDirectPlayMethod() {
      return this.playMethod == this.$constants.PlayMethod.DIRECTPLAY
    },
    title() {
      const mediaItemTitle = this.playbackSession?.displayTitle || this.mediaMetadata?.title || 'Title'
      if (this.currentChapterTitle) {
        if (this.showFullscreen) return this.currentChapterTitle
        return `${mediaItemTitle} | ${this.currentChapterTitle}`
      }
      return mediaItemTitle
    },
    authorName() {
      if (this.playbackSession) return this.playbackSession.displayAuthor
      return this.mediaMetadata?.authorName || 'Author'
    },
    chapters() {
      return this.playbackSession?.chapters || []
    },
    currentChapter() {
      if (!this.chapters.length) return null
      return this.chapters.find((ch) => Number(Number(ch.start).toFixed(2)) <= this.currentTime && Number(Number(ch.end).toFixed(2)) > this.currentTime)
    },
    nextChapter() {
      if (!this.chapters.length) return
      return this.chapters.find((c) => Number(Number(c.start).toFixed(2)) > this.currentTime)
    },
    currentChapterTitle() {
      return this.currentChapter?.title || ''
    },
    currentChapterDuration() {
      return this.currentChapter ? this.currentChapter.end - this.currentChapter.start : this.totalDuration
    },
    totalDurationPretty() {
      return this.$secondsToTimestamp(this.totalDuration)
    },
    currentTimePretty() {
      let currentTimeToUse = this.isDraggingCursor ? this.draggingCurrentTime : this.currentTime
      if (this.playerSettings.scaleElapsedTimeBySpeed) {
        currentTimeToUse = currentTimeToUse / this.currentPlaybackRate
      }
      return this.$secondsToTimestamp(currentTimeToUse)
    },
    timeRemaining() {
      let currentTimeToUse = this.isDraggingCursor ? this.draggingCurrentTime : this.currentTime
      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        var currChapTime = currentTimeToUse - this.currentChapter.start
        return (this.currentChapterDuration - currChapTime) / this.currentPlaybackRate
      }
      return this.totalTimeRemaining
    },
    totalTimeRemaining() {
      let currentTimeToUse = this.isDraggingCursor ? this.draggingCurrentTime : this.currentTime
      return (this.totalDuration - currentTimeToUse) / this.currentPlaybackRate
    },
    totalTimeRemainingPretty() {
      if (this.totalTimeRemaining < 0) {
        return this.$secondsToTimestamp(this.totalTimeRemaining * -1)
      }
      return '-' + this.$secondsToTimestamp(this.totalTimeRemaining)
    },
    timeRemainingPretty() {
      if (this.timeRemaining < 0) {
        return this.$secondsToTimestamp(this.timeRemaining * -1)
      }
      return '-' + this.$secondsToTimestamp(this.timeRemaining)
    },
    sleepTimeRemainingPretty() {
      if (!this.sleepTimeRemaining) return '0s'
      var secondsRemaining = Math.round(this.sleepTimeRemaining)
      if (secondsRemaining > 91) {
        return Math.ceil(secondsRemaining / 60) + 'm'
      } else {
        return secondsRemaining + 's'
      }
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    mediaId() {
      if (this.isPodcast || !this.playbackSession) return null
      if (this.playbackSession.libraryItemId) {
        return this.playbackSession.episodeId ? `${this.playbackSession.libraryItemId}-${this.playbackSession.episodeId}` : this.playbackSession.libraryItemId
      }
      const localLibraryItem = this.playbackSession.localLibraryItem
      if (!localLibraryItem) return null

      return this.playbackSession.localEpisodeId ? `${localLibraryItem.id}-${this.playbackSession.localEpisodeId}` : localLibraryItem.id
    },
    isInBookshelfContext() {
      // Check if current route is bookshelf-related which has bottom navigation
      return this.$route && this.$route.name && this.$route.name.startsWith('bookshelf')
    },
    playerBottomOffset() {
      if (this.showFullscreen) return '0px'

      // Use pre-calculated positions from init.client.js
      // Force reactivity by checking this.miniPlayerPositionsReady
      if (this.miniPlayerPositionsReady && window.MINI_PLAYER_POSITIONS) {
        const position = this.isInBookshelfContext ? window.MINI_PLAYER_POSITIONS.withTabBar : window.MINI_PLAYER_POSITIONS.withoutTabBar
        console.log('[AudioPlayer] Using calculated position:', position, 'bookshelf context:', this.isInBookshelfContext)
        return position
      }

      // Fallback if positions haven't been calculated yet
      const fallback = this.isInBookshelfContext ? '88px' : '8px'
      console.log('[AudioPlayer] Using fallback position:', fallback, 'positions ready:', this.miniPlayerPositionsReady, 'global positions:', !!window.MINI_PLAYER_POSITIONS)
      return fallback
    },
    fullscreenTopPadding() {
      // Only apply status bar padding when in fullscreen mode
      if (!this.showFullscreen) return '0px'
      try {
        const raw = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-top') || ''
        const px = parseFloat(raw.replace('px', '')) || 0
        const cap = Math.min(Math.max(px, 0), 64) // cap at 64px to avoid excessive spacing
        return `${cap}px`
      } catch (e) {
        return '0px'
      }
    }
  },
  methods: {
    handleTouchStart(event) {
      if (this.showFullscreen) return

      this.isSwipeActive = true
      this.swipeStartY = event.touches[0].clientY
      this.swipeOffset = 0
      // init horizontal swipe
      this.swipeStartX = event.touches[0].clientX
      this.swipeOffsetX = 0
      this.isHorizontalSwipeActive = false
      this.gestureAxis = 'none'
    },
    handleTouchMove(event) {
      if (!this.isSwipeActive || this.showFullscreen) return

      event.preventDefault()
      const currentY = event.touches[0].clientY
      const deltaY = this.swipeStartY - currentY // Negative for upward swipe
      const currentX = event.touches[0].clientX
      const deltaX = currentX - this.swipeStartX

      // Decide gesture axis if not yet decided and movement exceeds threshold
      if (this.gestureAxis === 'none') {
        if (Math.abs(deltaX) > this.gestureDetectionThreshold || Math.abs(deltaY) > this.gestureDetectionThreshold) {
          // pick the dominant axis
          this.gestureAxis = Math.abs(deltaX) > Math.abs(deltaY) ? 'horizontal' : 'vertical'
        }
      }

      // Only act on the locked axis
      if (this.gestureAxis === 'vertical') {
        // vertical gesture: ignore horizontal movement and update vertical offset
        if (deltaY > 0) {
          // Only allow upward swipes
          this.swipeOffset = -Math.min(deltaY, window.innerHeight)
        } else {
          this.swipeOffset = 0
        }
        // ensure horizontal offset stays centered
        this.swipeOffsetX = 0
      } else if (this.gestureAxis === 'horizontal') {
        // horizontal gesture: ignore vertical movement and update horizontal offset
        this.swipeOffset = 0
        this.isHorizontalSwipeActive = true
        this.swipeOffsetX = Math.max(Math.min(deltaX, window.innerWidth), -window.innerWidth)
      } else {
        // No axis decided yet: don't move UI
        this.swipeOffset = 0
        this.swipeOffsetX = 0
      }
    },
    handleTouchEnd(event) {
      if (!this.isSwipeActive || this.showFullscreen) return

      this.isSwipeActive = false
      this.isHorizontalSwipeActive = false
      const currentY = event.changedTouches[0].clientY
      const deltaY = this.swipeStartY - currentY

      // Act based on the decided axis
      if (this.gestureAxis === 'horizontal') {
        try {
          const endX = event.changedTouches[0].clientX
          const deltaX = endX - this.swipeStartX
          if (Math.abs(deltaX) > this.horizontalSwipeThreshold) {
            // Close playback and nudge UI
            this.swipeOffsetX = deltaX > 0 ? window.innerWidth : -window.innerWidth
            // Small delay so user sees the swipe animation before closing
            setTimeout(() => {
              this.closePlayback()
            }, 120)
            return
          }
        } catch (e) {}
        // Not far enough: snap back
        this.swipeOffsetX = 0
      } else if (this.gestureAxis === 'vertical') {
        if (deltaY > this.swipeThreshold) {
          // Swipe was far enough, expand to fullscreen
          this.expandFullscreen()
        } else {
          // Snap back to mini player
          this.swipeOffset = 0
        }
      } else {
        // No decisive gesture: reset offsets
        this.swipeOffset = 0
        this.swipeOffsetX = 0
      }

      // reset axis
      this.gestureAxis = 'none'
    },
    showSyncsFailedDialog() {
      Dialog.alert({
        title: this.$strings.HeaderProgressSyncFailed,
        message: this.$strings.MessageProgressSyncFailed,
        cancelText: this.$strings.ButtonOk
      })
    },
    clickChaptersBtn() {
      if (!this.chapters.length) return
      this.showChapterModal = true
    },
    async coverImageLoaded(fullCoverUrl) {
      // Image loaded, no color extraction needed for solid background
    },
    clickTitleAndAuthor() {
      if (!this.showFullscreen) return
      const llid = this.serverLibraryItemId || this.libraryItem?.id || this.localLibraryItem?.id
      if (llid) {
        this.$router.push(`/item/${llid}`)
        this.showFullscreen = false
      }
    },
    async selectChapter(chapter) {
      await this.$hapticsImpact()
      this.seek(chapter.start)
      this.showChapterModal = false
    },
    async castClick() {
      await this.$hapticsImpact()

      // Always show the Cast device selection modal when clicking the cast button
      // This allows users to connect to a new device, disconnect from current device,
      // or switch between devices regardless of current state
      this.$refs.castDeviceModal.init()

      // For local items, also emit the cast-local-item event for any additional handling
      if (this.isLocalPlayMethod) {
        this.$eventBus.$emit('cast-local-item')
      }
    },
    clickContainer() {
      this.expandToFullscreen()
    },
    expandFullscreen() {
      this.expandToFullscreen()
    },
    expandToFullscreen() {
      this.swipeOffset = 0
      this.isSwipeActive = false
      this.showFullscreen = true
      this.trackWidth = 0 // Reset track width so it gets recalculated for full view
      if (this.titleMarquee) this.titleMarquee.reset()

      // Update track for total time bar if useChapterTrack is set
      this.$nextTick(() => {
        this.updateTrack()
      })
    },
    collapseFullscreen() {
      this.swipeOffset = 0
      this.isSwipeActive = false
      this.showFullscreen = false
      this.trackWidth = 0 // Reset track width so it gets recalculated for mini view
      if (this.titleMarquee) this.titleMarquee.reset()

      this.forceCloseDropdownMenu()

      // Update track immediately for mini view
      this.$nextTick(() => {
        this.updateTrack()
      })
    },
    async jumpNextChapter() {
      console.log('[NUXT_SKIP_DEBUG] jumpNextChapter called', {
        isLoading: this.isLoading,
        hasNextChapter: !!this.nextChapter,
        nextChapter: this.nextChapter,
        currentChapter: this.currentChapter,
        currentTime: this.currentTime,
        chapters: this.chapters.length
      })

      await this.$hapticsImpact()
      if (this.isLoading) {
        console.log('[NUXT_SKIP_DEBUG] jumpNextChapter: Skipping due to isLoading=true')
        return
      }
      if (!this.nextChapter) {
        console.log('[NUXT_SKIP_DEBUG] jumpNextChapter: No next chapter available')
        return
      }

      console.log('[NUXT_SKIP_DEBUG] jumpNextChapter: Seeking to next chapter start:', this.nextChapter.start)
      this.seek(this.nextChapter.start)
    },
    async jumpChapterStart() {
      console.log('[NUXT_SKIP_DEBUG] jumpChapterStart called', {
        isLoading: this.isLoading,
        hasCurrentChapter: !!this.currentChapter,
        currentChapter: this.currentChapter,
        currentTime: this.currentTime,
        chapters: this.chapters.length
      })

      await this.$hapticsImpact()
      if (this.isLoading) {
        console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: Skipping due to isLoading=true')
        return
      }
      if (!this.currentChapter) {
        console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: No current chapter, calling restart()')
        return this.restart()
      }

      // If 4 seconds or less into current chapter, then go to previous
      const timeSinceChapterStart = this.currentTime - this.currentChapter.start
      console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: Time since chapter start:', timeSinceChapterStart)

      if (timeSinceChapterStart <= 4) {
        console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: Within 4 seconds, seeking to previous chapter')
        const currChapterIndex = this.chapters.findIndex((ch) => Number(ch.start) <= this.currentTime && Number(ch.end) >= this.currentTime)
        console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: Current chapter index:', currChapterIndex)
        if (currChapterIndex > 0) {
          const prevChapter = this.chapters[currChapterIndex - 1]
          console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: Seeking to previous chapter:', prevChapter)
          this.seek(prevChapter.start)
        } else {
          console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: Already at first chapter')
        }
      } else {
        console.log('[NUXT_SKIP_DEBUG] jumpChapterStart: More than 4 seconds, seeking to current chapter start:', this.currentChapter.start)
        this.seek(this.currentChapter.start)
      }
    },
    showSleepTimerModal() {
      this.$emit('showSleepTimer')
    },
    async setPlaybackSpeed(speed) {
      console.log(`[AudioPlayer] Set Playback Rate: ${speed}`)
      this.currentPlaybackRate = speed
      this.updateTimestamp()
      AbsAudioPlayer.setPlaybackSpeed({ value: speed })
    },
    restart() {
      this.seek(0)
    },
    async jumpBackwards() {
      console.log('[NUXT_SKIP_DEBUG] jumpBackwards called', {
        isLoading: this.isLoading,
        jumpBackwardsTime: this.jumpBackwardsTime,
        currentTime: this.currentTime,
        totalDuration: this.totalDuration
      })

      await this.$hapticsImpact()
      if (this.isLoading) {
        console.log('[NUXT_SKIP_DEBUG] jumpBackwards: Skipping due to isLoading=true')
        return
      }

      console.log('[NUXT_SKIP_DEBUG] jumpBackwards: Calling AbsAudioPlayer.seekBackward with value:', this.jumpBackwardsTime)
      try {
        const result = await AbsAudioPlayer.seekBackward({ value: this.jumpBackwardsTime })
        console.log('[NUXT_SKIP_DEBUG] jumpBackwards: AbsAudioPlayer.seekBackward result:', result)
      } catch (error) {
        console.error('[NUXT_SKIP_DEBUG] jumpBackwards: Error calling AbsAudioPlayer.seekBackward:', error)
      }
    },
    async jumpForward() {
      console.log('[NUXT_SKIP_DEBUG] jumpForward called', {
        isLoading: this.isLoading,
        jumpForwardTime: this.jumpForwardTime,
        currentTime: this.currentTime,
        totalDuration: this.totalDuration
      })

      await this.$hapticsImpact()
      if (this.isLoading) {
        console.log('[NUXT_SKIP_DEBUG] jumpForward: Skipping due to isLoading=true')
        return
      }

      console.log('[NUXT_SKIP_DEBUG] jumpForward: Calling AbsAudioPlayer.seekForward with value:', this.jumpForwardTime)
      try {
        const result = await AbsAudioPlayer.seekForward({ value: this.jumpForwardTime })
        console.log('[NUXT_SKIP_DEBUG] jumpForward: AbsAudioPlayer.seekForward result:', result)
      } catch (error) {
        console.error('[NUXT_SKIP_DEBUG] jumpForward: Error calling AbsAudioPlayer.seekForward:', error)
      }
    },
    setStreamReady() {
      this.readyTrackWidth = this.trackWidth
      this.updateReadyTrack()
    },
    setChunksReady(chunks, numSegments) {
      let largestSeg = 0
      for (let i = 0; i < chunks.length; i++) {
        const chunk = chunks[i]
        if (typeof chunk === 'string') {
          const chunkRange = chunk.split('-').map((c) => Number(c))
          if (chunkRange.length < 2) continue
          if (chunkRange[1] > largestSeg) largestSeg = chunkRange[1]
        } else if (chunk > largestSeg) {
          largestSeg = chunk
        }
      }
      const percentageReady = largestSeg / numSegments
      const widthReady = Math.round(this.trackWidth * percentageReady)
      if (this.readyTrackWidth === widthReady) {
        return
      }
      this.readyTrackWidth = widthReady
      this.updateReadyTrack()
    },
    updateReadyTrack() {
      // Update both full and mini ready tracks where present
      if (this.playerSettings.useChapterTrack) {
        if (this.$refs.totalReadyTrack) this.$refs.totalReadyTrack.style.width = this.readyTrackWidth + 'px'
        if (this.$refs.readyTrackFull) this.$refs.readyTrackFull.style.width = this.trackWidth + 'px'
        if (this.$refs.readyTrack) this.$refs.readyTrack.style.width = this.trackWidth + 'px'
        if (this.$refs.readyTrackMini) this.$refs.readyTrackMini.style.width = this.trackWidth + 'px'
      } else {
        if (this.$refs.readyTrackFull) this.$refs.readyTrackFull.style.width = this.readyTrackWidth + 'px'
        if (this.$refs.readyTrack) this.$refs.readyTrack.style.width = this.readyTrackWidth + 'px'
        if (this.$refs.readyTrackMini) this.$refs.readyTrackMini.style.width = this.readyTrackWidth + 'px'
      }
    },
    updateTimestamp() {
      const tsFull = this.$refs.currentTimestampFull
      const tsMini = this.$refs.currentTimestamp
      // Only require at least one timestamp element to exist
      if (!tsFull && !tsMini) {
        // Skip error if neither element exists (may be during component lifecycle)
        return
      }

      let currentTime = this.isDraggingCursor ? this.draggingCurrentTime : this.currentTime
      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        currentTime = Math.max(0, currentTime - this.currentChapter.start)
      }
      if (this.playerSettings.scaleElapsedTimeBySpeed) {
        currentTime = currentTime / this.currentPlaybackRate
      }

      const rounded = this.$secondsToTimestamp(currentTime)
      if (tsFull) tsFull.innerText = rounded
      if (tsMini) tsMini.innerText = rounded
    },
    timeupdate() {
      console.log('[NUXT_SKIP_DEBUG] timeupdate called', {
        currentTime: this.currentTime,
        totalDuration: this.totalDuration,
        isPlaying: this.isPlaying,
        seekLoading: this.seekLoading,
        isDraggingCursor: this.isDraggingCursor
      })

      // Ensure at least one played track exists
      if (!this.$refs.playedTrackFull && !this.$refs.playedTrack && !this.$refs.playedTrackMini) {
        console.error('[NUXT_SKIP_DEBUG] timeupdate: Invalid no played track ref')
        return
      }
      this.$emit('updateTime', this.currentTime)

      if (this.seekLoading) {
        console.log('[NUXT_SKIP_DEBUG] timeupdate: Seek loading completed, resetting track colors')
        this.seekLoading = false
        // Restore original colors for all progress tracks after seek completes
        if (this.$refs.playedTrack) {
          this.$refs.playedTrack.classList.remove('bg-yellow-300')
          this.$refs.playedTrack.classList.add('bg-primary')
        }
        if (this.$refs.playedTrackFull) {
          this.$refs.playedTrackFull.classList.remove('bg-yellow-300')
          this.$refs.playedTrackFull.classList.add('bg-primary')
        }
        if (this.$refs.playedTrackMini) {
          this.$refs.playedTrackMini.classList.remove('bg-yellow-300')
          this.$refs.playedTrackMini.classList.add('bg-primary')
        }
      }

      this.updateTimestamp()
      this.updateTrack()
    },
    updateTrack() {
      // Update progress track UI
      // Ensure trackWidth is valid; attempt to re-measure if it's not set yet
      if (!this.trackWidth || this.trackWidth === 0) {
        const el = this.getTrackElement()
        if (el) this.trackWidth = el.clientWidth
        if (!this.trackWidth) {
          // Can't compute widths yet; this may happen if DOM hasn't finished layout. Skip for now.
          console.warn('[AudioPlayer] updateTrack skipped, trackWidth not ready')
          return
        }
      }
      let currentTimeToUse = this.isDraggingCursor ? this.draggingCurrentTime : this.currentTime
      let percentDone = currentTimeToUse / this.totalDuration
      const totalPercentDone = percentDone
      let bufferedPercent = this.bufferedTime / this.totalDuration
      const totalBufferedPercent = bufferedPercent

      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        const currChapTime = currentTimeToUse - this.currentChapter.start
        percentDone = currChapTime / this.currentChapterDuration
        bufferedPercent = Math.max(0, Math.min(1, (this.bufferedTime - this.currentChapter.start) / this.currentChapterDuration))
      }

      const ptWidth = Math.max(0, Math.min(Math.round(percentDone * this.trackWidth), this.trackWidth))
      const bufferedWidth = Math.max(0, Math.min(Math.round(bufferedPercent * this.trackWidth), this.trackWidth))

      // Log first timeupdate to help debug initial sizing issues
      if (!this._firstTimeUpdateLogged) {
        console.log('[AudioPlayer] timeupdate init', {
          currentTime: this.currentTime,
          trackWidth: this.trackWidth,
          percentDone: percentDone,
          ptWidth: ptWidth,
          bufferedWidth: bufferedWidth
        })
        this._firstTimeUpdateLogged = true
      }
      // Full view
      if (this.$refs.playedTrackFull) this.$refs.playedTrackFull.style.width = ptWidth + 'px'
      if (this.$refs.bufferedTrackFull) this.$refs.bufferedTrackFull.style.width = bufferedWidth + 'px'
      if (this.$refs.trackCursorFull && !this.isDraggingSeek) this.$refs.trackCursorFull.style.left = Math.max(0, Math.min(ptWidth - 14, this.trackWidth - 28)) + 'px'
      // Mini view
      if (this.$refs.playedTrackMini) this.$refs.playedTrackMini.style.width = ptWidth + 'px'
      if (this.$refs.bufferedTrackMini) this.$refs.bufferedTrackMini.style.width = bufferedWidth + 'px'

      if (this.playerSettings.useChapterTrack) {
        if (this.$refs.totalPlayedTrack) this.$refs.totalPlayedTrack.style.width = Math.round(totalPercentDone * this.trackWidth) + 'px'
        if (this.$refs.totalBufferedTrack) this.$refs.totalBufferedTrack.style.width = Math.round(totalBufferedPercent * this.trackWidth) + 'px'
      }
    },
    seek(time) {
      console.log('[NUXT_SKIP_DEBUG] seek called', {
        time: time,
        isLoading: this.isLoading,
        seekLoading: this.seekLoading,
        currentTime: this.currentTime,
        totalDuration: this.totalDuration
      })

      if (this.isLoading) {
        console.log('[NUXT_SKIP_DEBUG] seek: Skipping due to isLoading=true')
        return
      }
      if (this.seekLoading) {
        console.error('[NUXT_SKIP_DEBUG] seek: Already seek loading', this.seekedTime)
        return
      }

      this.seekedTime = time
      this.seekLoading = true

      console.log('[NUXT_SKIP_DEBUG] seek: Calling AbsAudioPlayer.seek with value:', Math.floor(time))
      try {
        AbsAudioPlayer.seek({ value: Math.floor(time) })
      } catch (error) {
        console.error('[NUXT_SKIP_DEBUG] seek: Error calling AbsAudioPlayer.seek:', error)
      }

      const perc = time / this.totalDuration
      const ptWidth = Math.max(0, Math.min(Math.round(perc * this.trackWidth), this.trackWidth))
      if (this.$refs.playedTrackFull) {
        this.$refs.playedTrackFull.style.width = ptWidth + 'px'
        this.$refs.playedTrackFull.classList.remove('bg-primary')
        this.$refs.playedTrackFull.classList.add('bg-yellow-300')
      }
      if (this.$refs.playedTrackMini) {
        this.$refs.playedTrackMini.style.width = ptWidth + 'px'
        this.$refs.playedTrackMini.classList.remove('bg-primary')
        this.$refs.playedTrackMini.classList.add('bg-yellow-300')
      }
    },
    async touchstartCursor(e) {
      if (!e || !e.touches || !this.$refs.trackFull || !this.showFullscreen || this.playerSettings.lockUi) return

      await this.$hapticsImpact()
      this.isDraggingCursor = true
      this.draggingTouchStartX = e.touches[0].pageX
      this.draggingTouchStartTime = this.currentTime
      this.draggingCurrentTime = this.currentTime

      // Also set up seek drag mechanism for cursor dragging
      this.isDraggingSeek = true
      this.draggingTrackElement = this.$refs.trackFull
      this.draggingStartTime = this.currentTime

      // Get initial position
      const rect = this.draggingTrackElement.getBoundingClientRect()
      const clientX = e.touches[0].clientX
      this.draggingStartX = clientX - rect.left
      this.draggingTrackRect = rect

      // Add global event listeners for seek drag
      document.addEventListener('touchmove', this.handleDragSeek, { passive: false })
      document.addEventListener('touchend', this.endDragSeek)

      // Prevent text selection during drag
      document.body.style.userSelect = 'none'

      this.updateTrack()
    },
    async playPauseClick() {
      await this.$hapticsImpact()
      if (this.isLoading) return

      this.isPlaying = !!((await AbsAudioPlayer.playPause()) || {}).playing
      this.isEnded = false
    },
    play() {
      AbsAudioPlayer.playPlayer()
      this.startPlayInterval()
      this.isPlaying = true
    },
    pause() {
      AbsAudioPlayer.pausePlayer()
      this.stopPlayInterval()
      this.isPlaying = false
    },
    startPlayInterval() {
      console.log('[NUXT_SKIP_DEBUG] startPlayInterval called')
      clearInterval(this.playInterval)
      this.playInterval = setInterval(async () => {
        try {
          var data = await AbsAudioPlayer.getCurrentTime()
          const newCurrentTime = Number(data.value.toFixed(2))
          const newBufferedTime = Number(data.bufferedTime.toFixed(2))

          // Only log if time actually changed to avoid spam
          if (newCurrentTime !== this.currentTime) {
            console.log('[NUXT_SKIP_DEBUG] Progress update:', {
              oldTime: this.currentTime,
              newTime: newCurrentTime,
              bufferedTime: newBufferedTime,
              totalDuration: this.totalDuration
            })
          }

          this.currentTime = newCurrentTime
          this.bufferedTime = newBufferedTime
          this.timeupdate()
        } catch (error) {
          console.error('[NUXT_SKIP_DEBUG] Error in playInterval getCurrentTime:', error)
        }
      }, 1000)
    },
    stopPlayInterval() {
      clearInterval(this.playInterval)
    },
    resetStream() {
      this.closePlayback()
    },
    touchstart(e) {
      if (!e.changedTouches || this.$store.state.globals.isModalOpen) return
      const touchPosY = e.changedTouches[0].pageY
      // when minimized only listen to touchstart on the player
      if (!this.showFullscreen && touchPosY < window.innerHeight - 120) return

      // for ios
      if (!this.showFullscreen && e.pageX < 20) {
        e.preventDefault()
        e.stopImmediatePropagation()
      }

      this.touchStartY = touchPosY
      this.touchStartTime = Date.now()
    },
    touchend(e) {
      if (!e.changedTouches) return
      const touchDuration = Date.now() - this.touchStartTime
      const touchEndY = e.changedTouches[0].pageY
      const touchDistanceY = touchEndY - this.touchStartY

      // reset touch start data
      this.touchStartTime = 0
      this.touchStartY = 0

      if (this.isDraggingCursor) {
        if (this.draggingCurrentTime !== this.currentTime) {
          this.seek(this.draggingCurrentTime)
        }
        this.isDraggingCursor = false

        // Also clean up seek drag state
        this.isDraggingSeek = false
        this.draggingTrackElement = null
        this.draggingStartTime = 0
        this.draggingCurrentTime = 0
        this.draggingStartX = 0
        this.draggingTrackRect = null

        // Remove global event listeners
        document.removeEventListener('touchmove', this.handleDragSeek)
        document.removeEventListener('touchend', this.endDragSeek)

        // Restore text selection
        document.body.style.userSelect = ''
      } else {
        if (touchDuration > 1200) {
          // console.log('touch too long', touchDuration)
          return
        }
        if (this.showFullscreen) {
          // Touch start higher than touchend
          if (touchDistanceY > 100) {
            this.collapseFullscreen()
          }
        } else if (touchDistanceY < -100) {
          this.expandToFullscreen()
        }
      }
    },
    touchmove(e) {
      if (!this.isDraggingCursor || !e.touches || this.isDraggingSeek) return

      const distanceMoved = e.touches[0].pageX - this.draggingTouchStartX
      let duration = this.totalDuration
      let minTime = 0
      let maxTime = duration
      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        duration = this.currentChapterDuration
        minTime = this.currentChapter.start
        maxTime = minTime + duration
      }

      const timePerPixel = duration / this.trackWidth
      const newTime = this.draggingTouchStartTime + timePerPixel * distanceMoved
      this.draggingCurrentTime = Math.min(maxTime, Math.max(minTime, newTime))

      this.updateTimestamp()
      this.updateTrack()
    },
    async seekToPosition(event) {
      if (this.isLoading || this.playerSettings.lockUi) return

      await this.$hapticsImpact()

      // Get the track element - use currentTarget if available, otherwise target
      const trackElement = event.currentTarget || event.target
      if (!trackElement) {
        console.warn('[AudioPlayer] seekToPosition: No track element found')
        return
      }

      const rect = trackElement.getBoundingClientRect()
      if (!rect) {
        console.warn('[AudioPlayer] seekToPosition: Could not get bounding rect')
        return
      }

      const clickX = event.clientX - rect.left
      const percentage = Math.max(0, Math.min(1, clickX / rect.width))

      let duration = this.totalDuration
      let minTime = 0
      let maxTime = duration

      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        duration = this.currentChapterDuration
        minTime = this.currentChapter.start
        maxTime = minTime + duration
      }

      const seekTime = minTime + percentage * duration
      const clampedSeekTime = Math.min(maxTime, Math.max(minTime, seekTime))

      this.seek(clampedSeekTime)
    },
    async startDragSeek(event) {
      if (this.isLoading || this.playerSettings.lockUi) return

      event.preventDefault()
      await this.$hapticsImpact()

      this.isDraggingSeek = true
      this.draggingTrackElement = event.currentTarget || event.target
      this.draggingStartTime = this.currentTime

      // Get initial position
      const rect = this.draggingTrackElement.getBoundingClientRect()
      const clientX = event.clientX || (event.touches && event.touches[0] ? event.touches[0].clientX : 0)
      this.draggingStartX = clientX - rect.left
      this.draggingTrackRect = rect

      // Add global event listeners
      document.addEventListener('mousemove', this.handleDragSeek)
      document.addEventListener('mouseup', this.endDragSeek)
      document.addEventListener('touchmove', this.handleDragSeek, { passive: false })
      document.addEventListener('touchend', this.endDragSeek)

      // Prevent text selection during drag
      document.body.style.userSelect = 'none'
    },
    handleDragSeek(event) {
      if (!this.isDraggingSeek || !this.draggingTrackElement) return

      event.preventDefault()

      const clientX = event.clientX || (event.touches && event.touches[0] ? event.touches[0].clientX : 0)
      const rect = this.draggingTrackRect
      const clickX = clientX - rect.left
      const percentage = Math.max(0, Math.min(1, clickX / rect.width))

      let duration = this.totalDuration
      let minTime = 0
      let maxTime = duration

      if (this.playerSettings.useChapterTrack && this.currentChapter) {
        duration = this.currentChapterDuration
        minTime = this.currentChapter.start
        maxTime = minTime + duration
      }

      const seekTime = minTime + percentage * duration
      this.draggingCurrentTime = Math.min(maxTime, Math.max(minTime, seekTime))

      // Update cursor position directly for smooth dragging - center the 24px cursor on the touch position
      if (this.$refs.trackCursorFull) {
        const cursorLeft = Math.max(0, Math.min(clickX - 12, rect.width - 24))
        this.$refs.trackCursorFull.style.left = cursorLeft + 'px'
      }

      this.updateTimestamp()
      this.updateTrack()
    },
    endDragSeek(event) {
      if (!this.isDraggingSeek) return

      event.preventDefault()

      // Remove global event listeners
      document.removeEventListener('mousemove', this.handleDragSeek)
      document.removeEventListener('mouseup', this.endDragSeek)
      document.removeEventListener('touchmove', this.handleDragSeek)
      document.removeEventListener('touchend', this.endDragSeek)

      // Restore text selection
      document.body.style.userSelect = ''

      // Perform the actual seek if the time changed
      if (this.draggingCurrentTime !== this.draggingStartTime) {
        this.seek(this.draggingCurrentTime)
      }

      // Reset drag state
      this.isDraggingSeek = false
      this.draggingTrackElement = null
      this.draggingStartTime = 0
      this.draggingCurrentTime = 0
      this.draggingStartX = 0
      this.draggingTrackRect = null
    },
    async clickMenuAction(action) {
      await this.$hapticsImpact()
      this.showMoreMenuDialog = false
      this.$nextTick(() => {
        if (action === 'history') {
          this.$router.push(`/media/${this.mediaId}/history?title=${this.title}`)
          this.showFullscreen = false
        } else if (action === 'scale_elapsed_time') {
          this.playerSettings.scaleElapsedTimeBySpeed = !this.playerSettings.scaleElapsedTimeBySpeed
          this.updateTimestamp()
          this.savePlayerSettings()
        } else if (action === 'lock') {
          this.playerSettings.lockUi = !this.playerSettings.lockUi
          this.savePlayerSettings()
        } else if (action === 'chapter_track') {
          this.playerSettings.useChapterTrack = !this.playerSettings.useChapterTrack
          this.playerSettings.useTotalTrack = !this.playerSettings.useChapterTrack || this.playerSettings.useTotalTrack

          this.updateTimestamp()
          this.updateTrack()
          this.updateReadyTrack()
          this.updateUseChapterTrack()
          this.savePlayerSettings()
        } else if (action === 'total_track') {
          this.playerSettings.useTotalTrack = !this.playerSettings.useTotalTrack
          this.playerSettings.useChapterTrack = !this.playerSettings.useTotalTrack || this.playerSettings.useChapterTrack

          this.updateTimestamp()
          this.updateTrack()
          this.updateReadyTrack()
          this.updateUseChapterTrack()
          this.savePlayerSettings()
        } else if (action === 'close') {
          this.closePlayback()
        }
      })
    },
    updateUseChapterTrack() {
      // Chapter track in NowPlaying only supported on iOS for now
      if (this.$platform === 'ios') {
        AbsAudioPlayer.setChapterTrack({ enabled: this.playerSettings.useChapterTrack })
      }
    },
    // Return the currently-visible track DOM element (prefer fullscreen when active)
    getTrackElement() {
      if (this.showFullscreen && this.$refs.trackFull) return this.$refs.trackFull
      if (!this.showFullscreen && this.$refs.trackMini) return this.$refs.trackMini
      // fallbacks for older refs
      if (this.$refs.track) return this.$refs.track
      if (this.$refs.trackFull) return this.$refs.trackFull
      if (this.$refs.trackMini) return this.$refs.trackMini
      return null
    },
    forceCloseDropdownMenu() {
      if (this.$refs.dropdownMenu && this.$refs.dropdownMenu.closeMenu) {
        this.$refs.dropdownMenu.closeMenu()
      }
    },
    closePlayback() {
      // Reset swipe offsets to avoid leaving UI translated
      this.swipeOffset = 0
      this.swipeOffsetX = 0
      this.isSwipeActive = false
      this.isHorizontalSwipeActive = false
      this.endPlayback()
      AbsAudioPlayer.closePlayback()
    },
    endPlayback() {
      this.$store.commit('setPlaybackSession', null)
      this.showFullscreen = false
      this.isEnded = false
      this.isLoading = false
      this.playbackSession = null
    },
    async loadPlayerSettings() {
      const savedPlayerSettings = await this.$localStore.getPlayerSettings()
      if (!savedPlayerSettings) {
        // In 0.9.72-beta 'useChapterTrack', 'useTotalTrack' and 'playerLock' was replaced with 'playerSettings' JSON object
        // Check if this old key was set and if so migrate them over to 'playerSettings'
        const chapterTrackPref = await this.$localStore.getPreferenceByKey('useChapterTrack')
        if (chapterTrackPref) {
          this.playerSettings.useChapterTrack = chapterTrackPref === '1'
          const totalTrackPref = await this.$localStore.getPreferenceByKey('useTotalTrack')
          this.playerSettings.useTotalTrack = totalTrackPref === '1'
          const playerLockPref = await this.$localStore.getPreferenceByKey('playerLock')
          this.playerSettings.lockUi = playerLockPref === '1'
        }
        this.savePlayerSettings()
      } else {
        this.playerSettings.useChapterTrack = !!savedPlayerSettings.useChapterTrack
        this.playerSettings.useTotalTrack = !!savedPlayerSettings.useTotalTrack
        this.playerSettings.lockUi = !!savedPlayerSettings.lockUi
        this.playerSettings.scaleElapsedTimeBySpeed = !!savedPlayerSettings.scaleElapsedTimeBySpeed
      }
    },
    savePlayerSettings() {
      return this.$localStore.setPlayerSettings({ ...this.playerSettings })
    },
    //
    // Listeners from audio AbsAudioPlayer
    //
    onPlayingUpdate(data) {
      console.log('onPlayingUpdate', JSON.stringify(data))
      this.isPlaying = !!data.value
      this.$store.commit('setPlayerPlaying', this.isPlaying)
      if (this.isPlaying) {
        this.startPlayInterval()
      } else {
        this.stopPlayInterval()
      }
    },
    onMetadata(data) {
      console.log('onMetadata', JSON.stringify(data))
      this.totalDuration = Number(data.duration.toFixed(2))
      this.currentTime = Number(data.currentTime.toFixed(2))

      // Done loading
      if (data.playerState !== 'BUFFERING' && data.playerState !== 'IDLE') {
        this.isLoading = false
      }

      if (data.playerState === 'ENDED') {
        console.log('[AudioPlayer] Playback ended')
      }
      this.isEnded = data.playerState === 'ENDED'

      console.log('received metadata update', data)

      this.timeupdate()
    },
    // When a playback session is started the native android/ios will send the session
    onPlaybackSession(playbackSession) {
      console.log('onPlaybackSession received', JSON.stringify(playbackSession))
      this.playbackSession = playbackSession

      this.isEnded = false
      this.isLoading = true
      this.syncStatus = 0
      this.$store.commit('setPlaybackSession', this.playbackSession)

      // Set track width
      this.$nextTick(() => {
        if (this.titleMarquee) this.titleMarquee.reset()
        this.titleMarquee = new WrappingMarquee(this.$refs.titlewrapper)
        this.titleMarquee.init(this.title)

        const el = this.getTrackElement()
        if (el) {
          this.trackWidth = el.clientWidth
        } else {
          console.error('Track not loaded', this.$refs)
        }
      })
    },
    onPlaybackClosed() {
      this.endPlayback()
    },
    onPlaybackFailed(data) {
      console.log('Received onPlaybackFailed evt')
      var errorMessage = data.value || 'Unknown Error'
      this.$toast.error(`Playback Failed: ${errorMessage}`)
      this.endPlayback()
    },
    onPlaybackSpeedChanged(data) {
      if (!data.value || isNaN(data.value)) return
      this.currentPlaybackRate = Number(data.value)
      this.updateTimestamp()
    },
    onCastSessionConnected(data) {
      console.log('Cast session connected:', data)
      const deviceName = data.deviceName || 'Unknown Device'
      this.$toast.success(`Connected to ${deviceName}`)
      // Update store to reflect casting state
      this.$store.commit('setMediaPlayer', 'cast-player')
    },
    onCastSessionDisconnected(data) {
      console.log('Cast session disconnected:', data)
      this.$toast.info('Cast session disconnected')
      // Update store to reflect local playback
      this.$store.commit('setMediaPlayer', 'local-player')
    },
    onCastSessionFailed(data) {
      console.log('Cast session failed:', data)
      this.$toast.error('Failed to connect to cast device')
    },
    onCastSessionRequested(data) {
      console.log('Cast session requested:', data)
      // Show the Cast device selection modal
      this.$refs.castDeviceModal.init()
    },
    onCastDeviceConnected(device) {
      console.log('Cast device connected from modal:', device)
      // Device connection is handled by the modal and native layer
      // The onCastSessionConnected method will be called by the native layer
    },
    onCastDeviceDisconnected(device) {
      console.log('Cast device disconnected from modal:', device)
      // Device disconnection is handled by the modal and native layer
      // The onCastSessionDisconnected method will be called by the native layer
    },
    async init() {
      await this.loadPlayerSettings()

      // Check if there's already a playback session in the store (from native sync)
      if (this.$store.state.currentPlaybackSession && !this.playbackSession) {
        console.log('[AudioPlayer] Found existing playback session in store, setting it')
        this.onPlaybackSession(this.$store.state.currentPlaybackSession)
      }

      // Check for last playback session on app start
      await this.checkForLastPlaybackSession()
    },
    async checkForLastPlaybackSession() {
      try {
        // Only check on first app load and if no current session
        if (!this.$store.state.isFirstAudioLoad || this.$store.state.currentPlaybackSession) {
          console.log('[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Skipping check - isFirstAudioLoad:', this.$store.state.isFirstAudioLoad, 'currentSession:', !!this.$store.state.currentPlaybackSession)
          return
        }

        console.log('[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Checking for last playback session to resume')
        const lastSession = await this.$store.dispatch('loadLastPlaybackSession')

        if (lastSession) {
          // Check if this session is worth resuming (not at the very beginning)
          const progress = lastSession.currentTime / lastSession.duration
          if (progress > 0.01) {
            console.log(`[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Found resumable session: ${lastSession.displayTitle} at ${Math.floor(progress * 100)}%`)

            // Resume the session
            await this.resumeFromLastSession()
          } else {
            console.log(`[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Session found but progress too low: ${Math.floor(progress * 100)}%`)
          }
        } else {
          console.log('[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: No last session found')
        }
      } catch (error) {
        console.error('[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Failed to check for last playback session:', error)
        console.error('[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Error type:', error.constructor.name)
        console.error('[NUXT_SKIP_DEBUG] AudioPlayer.checkForLastPlaybackSession: Error message:', error.message)
      }
    },
    async resumeFromLastSession() {
      try {
        console.log('[NUXT_SKIP_DEBUG] AudioPlayer.resumeFromLastSession: Attempting to resume from last session')
        await AbsAudioPlayer.resumeLastPlaybackSession()
        console.log('[NUXT_SKIP_DEBUG] AudioPlayer.resumeFromLastSession: Successfully resumed from last session')
      } catch (error) {
        console.error('[NUXT_SKIP_DEBUG] AudioPlayer.resumeFromLastSession: Failed to resume from last session:', error)
        console.error('[NUXT_SKIP_DEBUG] AudioPlayer.resumeFromLastSession: Error type:', error.constructor.name)
        console.error('[NUXT_SKIP_DEBUG] AudioPlayer.resumeFromLastSession: Error message:', error.message)
        throw error
      }
    },
    async screenOrientationChange() {
      if (this.isRefreshingUI) return
      this.isRefreshingUI = true
      const windowWidth = window.innerWidth
      this.refreshUI()

      // Window width does not always change right away. Wait up to 250ms for a change.
      // iPhone 10 on iOS 16 took between 100 - 200ms to update when going from portrait to landscape
      //   but landscape to portrait was immediate
      for (let i = 0; i < 5; i++) {
        await new Promise((resolve) => setTimeout(resolve, 50))
        if (window.innerWidth !== windowWidth) {
          this.refreshUI()
          break
        }
      }

      this.isRefreshingUI = false
    },
    refreshUI() {
      this.updateScreenSize()
      const el = this.getTrackElement()
      if (el) {
        this.trackWidth = el.clientWidth
        this.updateTrack()
        this.updateReadyTrack()
        this.updateTimestamp()
      }
    },
    updateScreenSize() {
      setTimeout(() => {
        if (this.titleMarquee) this.titleMarquee.init(this.title)
      }, 500)

      this.windowHeight = window.innerHeight
      this.windowWidth = window.innerWidth
      const coverHeight = this.fullscreenBookCoverWidth * this.bookCoverAspectRatio
      const coverImageWidthCollapsed = 46 / this.bookCoverAspectRatio
      const titleAuthorLeftOffsetCollapsed = 30 + coverImageWidthCollapsed
      const titleAuthorWidthCollapsed = this.windowWidth - 128 - titleAuthorLeftOffsetCollapsed - 10

      document.documentElement.style.setProperty('--cover-image-width', this.fullscreenBookCoverWidth + 'px')
      document.documentElement.style.setProperty('--cover-image-height', coverHeight + 'px')
      document.documentElement.style.setProperty('--cover-image-width-collapsed', coverImageWidthCollapsed + 'px')
      document.documentElement.style.setProperty('--cover-image-height-collapsed', 46 + 'px')
      document.documentElement.style.setProperty('--title-author-left-offset-collapsed', titleAuthorLeftOffsetCollapsed + 'px')
      document.documentElement.style.setProperty('--title-author-width-collapsed', titleAuthorWidthCollapsed + 'px')
    },
    minimizePlayerEvt() {
      this.collapseFullscreen()
    },
    onAbsUiReady() {
      // Called when app layout/CSS variables are ready. Force a UI refresh so
      // playerBottomOffset calculates against the correct navbar height.
      console.log('[AudioPlayer] abs-ui-ready received, refreshing UI')
      this.$nextTick(() => {
        this.refreshUI()
        // Also nudge DOM and recalc bottom offset
        setTimeout(() => {
          this.refreshUI()
          // Mini player positioning is now handled by global positions
          // No need to force update anymore
        }, 50)
      })
    },
    showProgressSyncIsFailing() {
      this.syncStatus = this.$constants.SyncStatus.FAILED
    },
    showProgressSyncSuccess() {
      this.syncStatus = this.$constants.SyncStatus.SUCCESS
    }
  },
  created() {
    // Add listeners early to ensure they're available when Android syncs playback state
    AbsAudioPlayer.addListener('onPlaybackSession', this.onPlaybackSession)
    AbsAudioPlayer.addListener('onPlaybackClosed', this.onPlaybackClosed)
    AbsAudioPlayer.addListener('onPlaybackFailed', this.onPlaybackFailed)
    AbsAudioPlayer.addListener('onPlayingUpdate', this.onPlayingUpdate)
    AbsAudioPlayer.addListener('onMetadata', this.onMetadata)
    AbsAudioPlayer.addListener('onProgressSyncFailing', this.showProgressSyncIsFailing)
    AbsAudioPlayer.addListener('onProgressSyncSuccess', this.hideProgressSyncIsFailing)
    AbsAudioPlayer.addListener('onPlaybackSpeedChanged', this.onPlaybackSpeedChanged)

    // Cast event listeners
    AbsAudioPlayer.addListener('onCastSessionConnected', this.onCastSessionConnected)
    AbsAudioPlayer.addListener('onCastSessionDisconnected', this.onCastSessionDisconnected)
    AbsAudioPlayer.addListener('onCastSessionFailed', this.onCastSessionFailed)
    AbsAudioPlayer.addListener('onCastSessionRequested', this.onCastSessionRequested)
  },
  mounted() {
    this.updateScreenSize()
    if (screen.orientation) {
      // Not available on ios
      screen.orientation.addEventListener('change', this.screenOrientationChange)
    } else {
      document.addEventListener('orientationchange', this.screenOrientationChange)
    }
    window.addEventListener('resize', this.screenOrientationChange)

    this.$eventBus.$on('minimize-player', this.minimizePlayerEvt)
    // Ensure we recalculate offsets after app UI is ready (CSS vars / nav mounted)
    this.$eventBus.$on('abs-ui-ready', this.onAbsUiReady)
    document.body.addEventListener('touchstart', this.touchstart, { passive: false })
    document.body.addEventListener('touchend', this.touchend)
    document.body.addEventListener('touchmove', this.touchmove)

    // Listen for mini player positions being ready
    this.handlePositionsReady = () => {
      console.log('[AudioPlayer] Mini player positions are ready')
      this.miniPlayerPositionsReady = true
    }

    if (window.MINI_PLAYER_POSITIONS) {
      // Positions already calculated
      this.miniPlayerPositionsReady = true
    } else {
      // Wait for positions to be calculated
      window.addEventListener('miniPlayerPositionsReady', this.handlePositionsReady)
    }

    // Set up safe area observer for fullscreen status bar padding
    const updateFullscreenTopPadding = () => {
      try {
        const raw = getComputedStyle(document.documentElement).getPropertyValue('--safe-area-inset-top') || ''
        const px = parseFloat(raw.replace('px', '')) || 0
        const cap = Math.min(Math.max(px, 0), 64) // cap at 64px to avoid excessive spacing
        this.fullscreenTopPadding = `${cap}px`
      } catch (e) {
        this.fullscreenTopPadding = '0px'
      }
    }

    // Run immediately and when the safe-area-ready attribute toggles
    updateFullscreenTopPadding()
    // Observe attribute set by plugin to know when CSS vars are injected
    this._safeAreaObserver = new MutationObserver((mutations) => {
      for (const m of mutations) {
        if (m.type === 'attributes' && m.attributeName === 'data-safe-area-ready') {
          updateFullscreenTopPadding()
        }
      }
    })
    this._safeAreaObserver.observe(document.documentElement, { attributes: true })
    window.addEventListener('resize', updateFullscreenTopPadding)

    this.$nextTick(this.init)
  },
  beforeDestroy() {
    if (screen.orientation) {
      // Not available on ios
      screen.orientation.removeEventListener('change', this.screenOrientationChange)
    } else {
      document.removeEventListener('orientationchange', this.screenOrientationChange)
    }
    window.removeEventListener('resize', this.screenOrientationChange)
    this.$eventBus.$off('abs-ui-ready', this.onAbsUiReady)

    if (this.playbackSession) {
      console.log('[AudioPlayer] Before destroy closing playback')
      this.closePlayback()
    }

    this.forceCloseDropdownMenu()
    this.$eventBus.$off('minimize-player', this.minimizePlayerEvt)
    document.body.removeEventListener('touchstart', this.touchstart)
    document.body.removeEventListener('touchend', this.touchend)
    document.body.removeEventListener('touchmove', this.touchmove)

    if (AbsAudioPlayer.removeAllListeners) {
      AbsAudioPlayer.removeAllListeners()
    }
    clearInterval(this.playInterval)

    // Clean up safe area observer
    if (this._safeAreaObserver) {
      this._safeAreaObserver.disconnect()
    }

    // Clean up mini player positions event listener
    window.removeEventListener('miniPlayerPositionsReady', this.handlePositionsReady)
  }
}
</script>

<style>
:root {
  --cover-image-width: 0px;
  --cover-image-height: 0px;
  --cover-image-width-collapsed: 48px;
  --cover-image-height-collapsed: 48px;
  --title-author-left-offset-collapsed: 80px;
  --title-author-width-collapsed: 40%;
}

/* Mini player components */
.cover-wrapper-mini {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: var(--md-sys-elevation-surface-container-low);
  flex-shrink: 0;
}

.play-btn-mini {
  width: 40px;
  height: 40px;
  background: var(--md-sys-color-primary);
  box-shadow: var(--md-sys-elevation-fab-primary);
}

.play-btn-mini .material-symbols {
  font-size: 1.5rem;
  color: var(--md-sys-color-on-primary);
}

.playerContainer {
  height: 80px;
  background: rgba(var(--md-sys-color-surface-container-rgb), 0.85);
  backdrop-filter: blur(20px);
  border-radius: 16px;
  box-shadow: var(--md-sys-elevation-surface-container-high);
  margin: 0; /* Remove all margins - positioning handled by parent container */
}
.fullscreen .playerContainer {
  height: 200px;
}
#playerContent {
  box-shadow: var(--md-sys-elevation-surface-container-high);
  border-radius: 16px;
  background: rgba(var(--md-sys-color-surface-container-rgb), 0.85);
  backdrop-filter: blur(20px);
  margin: 0; /* Remove all margins - positioning handled by container */
}
.fullscreen #playerContent {
  box-shadow: none;
}

#playerTrack {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: margin;
  bottom: 43px;
}
#progressBarsContainer {
  bottom: 260px; /* More space above the buttons that are now at the bottom */
  left: 0;
  right: 0;
  z-index: 20;
}

.cover-wrapper {
  bottom: 76px;
  left: 16px;
  height: var(--cover-image-height-collapsed);
  width: var(--cover-image-width-collapsed);
  transition: all 0.25s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: left, bottom, width, height;
  transform-origin: left bottom;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: var(--md-sys-elevation-surface-container-low);
}

.title-author-texts {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: left, bottom, width, height;
  transform-origin: left bottom;

  width: var(--title-author-width-collapsed);
  bottom: 84px;
  left: var(--title-author-left-offset-collapsed);
  text-align: left;
}
.title-author-texts .title-text {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;
  font-size: 0.85rem;
  line-height: 1.5;
  color: var(--md-sys-color-on-surface);
  font-weight: 500;
}
.title-author-texts .author-text {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;
  font-size: 0.75rem;
  line-height: 1.2;
  color: var(--md-sys-color-on-surface-variant);
}

.fullscreen .title-author-texts {
  bottom: 180px; /* Position below progress bars (260px) */
  width: 80%;
  left: 10%;
  text-align: center;
  padding-bottom: 0;
  pointer-events: auto;
}
.fullscreen .title-author-texts .title-text {
  font-size: clamp(0.8rem, calc(var(--cover-image-height) / 260 * 20), 1.3rem);
}
.fullscreen .title-author-texts .author-text {
  font-size: clamp(0.6rem, calc(var(--cover-image-height) / 260 * 16), 1rem);
}

#playerControls {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: width, bottom;
  width: 128px;
  padding-right: 16px;
  bottom: 78px;
}
#playerControls .jump-icon {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;

  margin: 0px 0px;
  font-size: 1.6rem;
  color: var(--md-sys-color-on-surface-variant);
}
#playerControls .play-btn {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: padding, margin, height, width, min-width, min-height;

  height: 48px;
  width: 48px;
  min-width: 48px;
  min-height: 48px;
  margin: 0px 8px;
  background: var(--md-sys-color-primary) !important;
  box-shadow: var(--md-sys-elevation-fab-primary);
}
#playerControls .play-btn .material-symbols {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;

  font-size: 1.75rem;
  color: var(--md-sys-color-on-primary);
}

.fullscreen .cover-wrapper {
  margin: 0 auto;
  height: var(--cover-image-height);
  width: var(--cover-image-width);
  left: calc(50% - (calc(var(--cover-image-width)) / 2));
  bottom: calc(50% + 120px - (calc(var(--cover-image-height)) / 2));
  border-radius: 16px;
  overflow: hidden;
}

.fullscreen #playerControls {
  width: 100%;
  padding-left: 24px;
  padding-right: 24px;
  bottom: 24px; /* Move controls to very bottom with standard padding */
  left: 0;
}
.fullscreen #playerControls .jump-icon {
  font-size: 2.4rem;
}
.fullscreen #playerControls .next-icon {
  font-size: 2rem;
}
.fullscreen #playerControls .play-btn {
  height: 65px;
  width: 65px;
  min-width: 65px;
  min-height: 65px;
}
.fullscreen #playerControls .play-btn .material-symbols {
  font-size: 2.1rem;
}

/* Fullscreen Layout Styles */
.fullscreen-container {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Portrait Layout Styles */
.cover-wrapper-portrait {
  flex: 1;
  padding: 100px 20px 10px;
  min-height: 0;
  display: flex;
  align-items: flex-start;
  justify-content: center;
}

.cover-container {
  max-height: 50vh;
  max-width: 85vw;
  display: flex;
  align-items: center;
  justify-content: center;
}

.controls-container-portrait {
  flex: 0 0 auto;
  padding: 10px 20px 20px;
  max-width: 500px;
  margin: 0 auto;
  z-index: 10;
  position: relative;
}

/* Landscape Layout Styles */
.landscape-layout {
  overflow: hidden;
}

.landscape-layout .cover-wrapper-portrait,
.landscape-layout .controls-container-portrait,
.landscape-layout #progressBarsContainer,
.landscape-layout .title-author-texts {
  display: none !important;
}

/* Hide the original positioned top buttons in landscape mode */
.landscape-layout .top-4.left-4.absolute,
.landscape-layout .top-4.right-36.absolute,
.landscape-layout .top-4.right-20.absolute,
.landscape-layout .top-4.right-4.absolute {
  display: none !important;
}

.landscape-content-container {
  position: absolute;
  inset: 0;
  max-height: 100vh;
  align-items: center;
}

.landscape-cover-section {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 0;
  padding-bottom: 10px;
}

.cover-wrapper-landscape {
  border-radius: 20px;
  overflow: hidden;
  box-shadow: var(--md-sys-elevation-surface-container-high);
  transition: all 0.3s cubic-bezier(0.39, 0.575, 0.565, 1);
  max-height: 75vh;
  max-width: 100%;
  width: fit-content;
  height: fit-content;
}

.cover-container-landscape {
  display: flex;
  align-items: center;
  justify-content: center;
}

.cover-wrapper-landscape:active {
  transform: scale(0.98);
}

.landscape-controls-section {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 0;
  overflow: hidden;
}

.title-author-texts-landscape {
  margin-bottom: 1.5rem;
}

.title-author-texts-landscape .title-text {
  font-size: clamp(1.125rem, 2.5vw, 1.75rem);
  line-height: 1.2;
  font-weight: 600;
  margin-bottom: 0.25rem;
}

.title-author-texts-landscape .author-text {
  font-size: clamp(0.875rem, 2vw, 1.25rem);
  line-height: 1.3;
  opacity: 0.85;
}

.landscape-progress-container {
  margin-bottom: 1.5rem;
}

.landscape-main-controls {
  margin-bottom: 1rem;
}

.landscape-secondary-controls {
  flex-wrap: wrap;
  gap: 0.75rem;
}

/* Responsive adjustments for smaller landscape screens */
@media screen and (max-height: 500px) {
  .landscape-content-container {
    padding-top: 15px !important;
  }

  .title-author-texts-landscape {
    margin-bottom: 1rem;
  }

  .title-author-texts-landscape .title-text {
    font-size: clamp(1rem, 2.5vw, 1.5rem);
    margin-bottom: 0.125rem;
  }

  .title-author-texts-landscape .author-text {
    font-size: clamp(0.75rem, 2vw, 1.125rem);
  }

  .landscape-progress-container {
    margin-bottom: 1rem;
  }

  .landscape-main-controls {
    margin-bottom: 0.75rem;
  }

  .landscape-main-controls button {
    transform: scale(0.9);
  }

  .landscape-secondary-controls {
    margin-top: 0.25rem;
  }

  .landscape-secondary-controls button {
    transform: scale(0.9);
  }
}

/* Very small landscape screens (phones in landscape) */
@media screen and (max-height: 400px) {
  .landscape-content-container {
    padding-top: 10px !important;
  }

  .landscape-cover-section {
    width: 45% !important;
    padding-left: 0.75rem;
    padding-right: 0.75rem;
  }

  .landscape-controls-section {
    max-width: 55% !important;
    padding-left: 0.75rem;
    padding-right: 1rem;
  }

  .title-author-texts-landscape {
    margin-bottom: 0.75rem;
  }

  .title-author-texts-landscape .title-text {
    font-size: clamp(0.875rem, 2.5vw, 1.25rem);
    margin-bottom: 0.125rem;
  }

  .title-author-texts-landscape .author-text {
    font-size: clamp(0.75rem, 2vw, 1rem);
  }

  .landscape-progress-container {
    margin-bottom: 0.75rem;
  }

  .landscape-main-controls {
    margin-bottom: 0.5rem;
  }

  .landscape-main-controls button {
    transform: scale(0.8);
  }

  .landscape-secondary-controls button {
    transform: scale(0.85);
  }
}

/* Portrait responsive adjustments */
@media screen and (max-width: 480px) {
  .cover-wrapper-portrait {
    padding: 90px 15px 5px;
  }

  .controls-container-portrait {
    padding: 5px 15px 15px;
  }
}

@media screen and (max-height: 667px) {
  .cover-wrapper-portrait {
    padding: 80px 20px 5px;
  }

  .cover-container {
    max-height: 45vh;
  }

  .controls-container-portrait {
    padding: 5px 20px 15px;
  }
}

@media screen and (max-height: 568px) {
  .cover-wrapper-portrait {
    padding: 70px 15px 5px;
  }

  .cover-container {
    max-height: 40vh;
  }

  .controls-container-portrait {
    padding: 5px 15px 10px;
  }
}

@media screen and (max-height: 480px) {
  .cover-wrapper-portrait {
    padding: 60px 15px 5px;
  }

  .cover-container {
    max-height: 35vh;
  }

  .controls-container-portrait {
    padding: 5px 15px 8px;
  }
}

/* Fix button visibility issues */
.controls-container-portrait button,
.landscape-controls-section button {
  pointer-events: auto;
  z-index: 10;
  position: relative;
}

/* Ensure all control elements are visible and interactive */
.controls-container-portrait *,
.landscape-controls-section * {
  pointer-events: auto;
}
</style>
