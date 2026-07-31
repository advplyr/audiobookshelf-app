# Návrh: Nativní TTS přehrávač e-knih (s podporou Android Auto)

Stav: návrh k diskusi · Navazuje na: WebView předčítání ve čtečce (`mixins/ttsPlayer.js`)

## 1. Motivace

Současné předčítání (v1) běží ve WebView: JavaScript čtečky extrahuje text a po
větách volá nativní plugin `@capacitor-community/text-to-speech`. Mluvení samotné
je nativní, ale **řídicí smyčka je v JS**, což znamená:

- **Zhasnutá obrazovka / pozadí:** iOS suspenduje WKWebView po zamknutí — čtení
  skončí po aktuální větě. Android WebView zpravidla běží dál, ale bez foreground
  služby ho může systém kdykoli uspat (Doze, optimalizace baterie).
- **Žádná media session:** předčítání nemá notifikaci s ovládáním, nereaguje na
  bluetooth tlačítka a neexistuje pro Android Auto ani CarPlay.
- **Žádný výběr knihy v autě:** Android Auto browse tree (`BrowseTree.kt`) zná
  jen audio položky.

Cíl v2: přesunout řídicí smyčku TTS do nativní vrstvy jako plnohodnotný
"přehrávač", který žije ve stejné infrastruktuře jako přehrávač audioknih.

## 2. Co už v aplikaci existuje (na čem stavíme)

| Součást | Soubor | Role |
| --- | --- | --- |
| `PlayerNotificationService` | `android/.../player/PlayerNotificationService.kt` | `MediaBrowserServiceCompat`: ExoPlayer/Cast, MediaSession, notifikace, audio focus, Android Auto root |
| `BrowseTree` + `MediaManager` | `android/.../player/BrowseTree.kt`, `android/.../media/MediaManager.kt` | obsah pro Android Auto (knihovny, pokračovat v poslechu…) |
| `MediaProgressSyncer` | `android/.../media/MediaProgressSyncer.kt` | ukládání/sync průběhu na server |
| `AbsAudioPlayer` | `android/.../plugins/AbsAudioPlayer.kt`, `ios/App/Shared/plugins/AbsAudioPlayer.swift` | Capacitor most WebView ↔ nativní přehrávač |
| `AudioPlayer` (iOS) | `ios/App/Shared/player/AudioPlayer.swift` | AVPlayer, `MPNowPlayingInfoCenter`, remote commands, background audio (`UIBackgroundModes: audio` už je v Info.plist) |
| TTS mixin (v1) | `mixins/ttsPlayer.js` | extrakce textu z EPUB/MOBI/PDF, chunkování po větách |

Klíčové pozorování: **extrakci textu už umíme v JS** (epub.js, mobi parser,
pdf.js) a nedává smysl ji přepisovat nativně pro tři formáty. Nativní vrstva
proto dostane už hotový strukturovaný text.

## 3. Cílová architektura

```
┌────────────────────────── WebView (Nuxt) ──────────────────────────┐
│ čtečka (EpubReader/MobiReader/PdfReader)                           │
│   └─ extrakce textu (existující mixin hooky)                       │
│        └─ TTSBook payload ────────────┐                            │
│ UI čtečky poslouchá eventy (onParagraph, onStateChange)            │
└───────────────────────────────────────┼────────────────────────────┘
                                        ▼
                          Capacitor plugin AbsTTSPlayer
                                        │
        ┌───────────────────────────────┴───────────────────────────┐
        ▼                                                           ▼
  Android: TTS playback engine                          iOS: TTS playback engine
  v PlayerNotificationService                           v AudioPlayer vrstvě
  - android.speech.tts.TextToSpeech                     - AVSpeechSynthesizer
  - MediaSession (play/pause/seek/rate)                 - MPNowPlayingInfoCenter
  - foreground notifikace                               - MPRemoteCommandCenter
  - audio focus (sdílený s ExoPlayerem)                 - AVAudioSession (playback)
  - Android Auto: BrowseTree kategorie                  - výhledově CarPlay
  - cache TTSBook na disku (JSON)                       - cache TTSBook na disku
  - MediaProgressSyncer (průběh na server)              - sync průběhu (existující cesta)
```

### 3.1 Datový model `TTSBook`

Jednotný payload předávaný z JS do nativní vrstvy a cacheovaný na disku
(JSON v app storage, např. `tts-cache/<libraryItemId>.json`):

```ts
interface TTSBook {
  libraryItemId: string        // server nebo local id
  serverAddress?: string
  title: string
  author: string
  coverPath?: string           // lokální cesta/URL na obálku
  language: string             // výchozí jazyk předčítání ('cs-CZ' | 'en-US' | …)
  ebookFormat: 'epub' | 'mobi' | 'pdf'
  chapters: TTSChapter[]
  totalChars: number           // pro odhad "délky" a procenta
}

interface TTSChapter {
  title: string
  startLocation: string        // epub cfi / pdf stránka / mobi anchor – pro návrat čtečky
  paragraphs: TTSParagraph[]
}

interface TTSParagraph {
  text: string
  location?: string            // cfi / číslo stránky – pro progress a follow-along
  chars: number
}
```

- Chunkování po větách (dnes `splitTextChunks`) se přesune do nativní vrstvy —
  Kotlin/Swift verze stejného algoritmu; JS posílá celé odstavce.
- "Čas" v media session se odhaduje z počtu znaků a rychlosti (heuristika
  ~15 znaků/s při 1.0×). Nemusí být přesný — slouží pro progress bar
  v notifikaci/autě a pro relativní seek.

### 3.2 Capacitor plugin `AbsTTSPlayer`

```ts
interface AbsTTSPlayerPlugin {
  // příprava: uloží TTSBook do cache a připraví session (bez spuštění)
  prepareBook(book: TTSBook): Promise<void>
  // spustí/obnoví předčítání od pozice
  play(options?: { libraryItemId?: string, chapterIndex?: number, paragraphIndex?: number }): Promise<void>
  pause(): Promise<void>
  stop(): Promise<void>       // ukončí session, zruší notifikaci
  seekTo(options: { chapterIndex: number, paragraphIndex: number }): Promise<void>
  nextChapter(): Promise<void>
  prevChapter(): Promise<void>
  setRate(options: { rate: number }): Promise<void>
  setLanguage(options: { lang: string }): Promise<void>
  getState(): Promise<TTSPlayerState>
  removeCachedBook(options: { libraryItemId: string }): Promise<void>
  listCachedBooks(): Promise<{ books: TTSBookSummary[] }>

  // eventy do WebView
  addListener(event: 'onParagraph', cb: (p: { chapterIndex: number, paragraphIndex: number, location?: string }) => void)
  addListener(event: 'onStateChange', cb: (s: TTSPlayerState) => void)
}
```

Chování WebView čtečky:

- Otevřená čtečka poslouchá `onParagraph` → follow-along (otočení stránky /
  scroll) přes existující `ttsFollowParagraph` hooky. Ovládací lišta z v1
  zůstává, jen volá plugin místo lokální smyčky.
- Zavřená aplikace/čtečka: nativní služba jede dál sama; progress se
  synchronizuje nativně.

### 3.3 Android

**Kde:** rozšíření `PlayerNotificationService` (žádná druhá služba — jedna
media session na aplikaci je i požadavek Android Auto).

- **`TTSPlaybackEngine`** (nová třída v `player/`): drží `TextToSpeech`
  instanci, frontu chunků aktuálního odstavce, pozici (kapitola/odstavec)
  a rychlost. Mluví přes `TextToSpeech#speak` s `UtteranceProgressListener`
  pro posun na další chunk. Implementuje stejný "session guard" jako JS v1.
- **Přepínání zdrojů:** služba dostane interní režim `AUDIO | TTS`. Při startu
  TTS session se zastaví ExoPlayer (a naopak) — jedna media session, jeden
  audio focus (`AudioFocusRequest`, `AUDIOFOCUS_GAIN`), ducking beze změny.
- **MediaSession mapping:** play/pause → engine; seek forward/back → ±odstavec;
  next/prev → kapitola; `setPlaybackSpeed` → TTS rate; metadata z `TTSBook`
  (titul, autor, obálka, kapitola jako "track").
- **Notifikace:** existující `PlayerNotificationListener` cesta; jen jiný
  MediaDescription adaptér pro TTS režim.
- **Android Auto:** v `BrowseTree` nová kategorie **„E-knihy"** naplněná
  z `listCachedBooks()` (tj. knihy, které uživatel aspoň jednou otevřel
  ve čtečce / explicitně "připravil pro poslech"). Výběr v autě →
  `prepareBook` z cache → `play`. Vyžaduje, aby cache obsahovala i metadata
  a obálku — proto se cache plní při `prepareBook`, ne až při `play`.
- **Doze/battery:** foreground služba s typem `mediaPlayback` (už existuje) —
  tím zmizí problém uspávání z v1.

### 3.4 iOS

- **`TTSPlayer`** (nová třída vedle `AudioPlayer.swift`): `AVSpeechSynthesizer`
  + `AVSpeechSynthesisVoice(language:)`, audio session `.playback` /
  `.spokenAudio` (stejně jako audioknihy). Background audio mód už je zapnutý —
  syntéza poběží i se zamknutou obrazovkou, pokud session zůstane aktivní.
- **Lock screen / ovládání:** `MPNowPlayingInfoCenter` (titul, autor, obálka,
  odhad času) + `MPRemoteCommandCenter` (play/pause, skip ±odstavec, rate).
- **CarPlay (samostatná fáze):** vyžaduje CarPlay audio entitlement od Apple
  (schvalovací proces!) a `CPTemplateApplicationSceneDelegate` +
  `CPListTemplate` pro browse. Aplikace dnes CarPlay nemá vůbec — dává smysl
  udělat CarPlay nejdřív pro audioknihy a e-knihy přidat jako kategorii.

### 3.5 Synchronizace průběhu

- Nativní engine po každém odstavci zná `location` (cfi/stránka) a kumulativní
  `chars` → `ebookLocation` + `ebookProgress` (poměr znaků) na existující
  endpoint `PATCH /api/me/progress/:id` (Android přes `MediaProgressSyncer`,
  iOS přes existující API vrstvu). Stejný formát, jaký dnes ukládá čtečka —
  po otevření čtečky se pokračuje tam, kde skončilo předčítání, a naopak.

## 4. Fáze implementace

| Fáze | Obsah | Odhad |
| --- | --- | --- |
| **F1** | Plugin `AbsTTSPlayer` + Android `TTSPlaybackEngine` v `PlayerNotificationService`, notifikace, media session, cache, progress sync. Čtečka přepnuta z v1 smyčky na plugin (mixin hooky zůstávají pro extrakci a follow-along). | největší kus práce |
| **F2** | Android Auto: kategorie „E-knihy" v `BrowseTree`, výběr a ovládání z auta. | malá až střední (staví na F1) |
| **F3** | iOS `TTSPlayer` + Now Playing + remote commands (background/zamčená obrazovka na iOS). | střední |
| **F4** | CarPlay: entitlement, scéna, šablony (ideálně vč. audioknih). | střední + externí závislost na Apple |

Fallback: WebView smyčka z v1 zůstane v kódu jako záloha pro případ, že
nativní vrstva není dostupná (např. starý build), a pro okamžité čtení bez
`prepareBook`.

## 5. Rizika a otevřené otázky

- **Latence a dostupnost hlasů:** `TextToSpeech` init je asynchronní a engine
  nemusí mít český hlas (řeší se `isLanguageSupported` + toast, případně
  odkaz na instalaci hlasů — plugin má `openInstall()`).
- **Sémantika času:** media session vyžaduje duration/position — odhad ze
  znaků je nepřesný; UI v autě může ukazovat "přibližný" čas. Alternativa:
  ukazovat pozici jako "kapitola X, odstavec Y/Z".
- **Souběh s audioknihou:** je třeba jasně definovat, že start TTS zastaví
  audio přehrávání (a naopak) — jedna session, žádné dva zdroje zvuku.
- **Velikost cache:** plný text knihy je stovky KB až jednotky MB JSON —
  limit počtu cacheovaných knih + LRU mazání (obdoba epub locations cache).
- **PDF bez textové vrstvy** dál nepůjdou (OCR je mimo rozsah).
- **CarPlay entitlement** může trvat týdny a Apple ho nemusí udělit pro
  TTS obsah — proto je CarPlay poslední fáze a žádná dřívější na něm nezávisí.
- **Upstream:** pokud má jít o příspěvek do upstream `advplyr/audiobookshelf-app`,
  je vhodné návrh probrat s maintainerem předem (touch points:
  `PlayerNotificationService`, `BrowseTree` — místa s aktivním vývojem).

---

## Příloha A: Implementační specifikace fáze F1 (+ F2)

Cíl F1: předčítání běží v nativní službě na Androidu — přežije zhasnutou
obrazovku, má notifikaci s ovládáním a media session. F2 na to navazuje
kategorií v Android Auto.

### A.1 Seznam změn po souborech

**JavaScript (sdílené pro obě platformy):**

| Soubor | Změna |
| --- | --- |
| `plugins/capacitor/AbsTTSPlayer.js` | **nový** — `registerPlugin('AbsTTSPlayer')` + web/fallback implementace: pokud nativní plugin není dostupný, deleguje na dnešní JS smyčku z mixinu (zachová funkčnost na starých buildech) |
| `plugins/capacitor/index.js` | export nového pluginu |
| `mixins/ttsPlayer.js` | řídicí smyčka se nahradí voláními `AbsTTSPlayer`; hooky pro extrakci a follow-along zůstávají; přibude `ttsExtractBook()` — extrakce **celé knihy** (ne jen aktuální jednotky) do `TTSBook` payloadu |
| `components/readers/EpubReader.vue` | `ttsExtractBook()`: průchod spine přes `book.spine.each` + `section.load()` (bez renderování — netřeba zobrazovat, stačí DOM), odstavce + cfi per sekce |
| `components/readers/MobiReader.vue` | `ttsExtractBook()`: celý dokument = jedna kapitola (případně dělení podle `h1/h2`) |
| `components/readers/PdfReader.vue` | `ttsExtractBook()`: `getTextContent()` všech stránek; kapitola = stránka |
| `components/readers/Reader.vue` | beze změn UI; lišta volá stejné metody (mixin je přesměruje na plugin) |

**Android:**

| Soubor | Změna |
| --- | --- |
| `plugins/AbsTTSPlayer.kt` | **nový** — Capacitor bridge: `prepareBook`, `play`, `pause`, `stop`, `seekTo`, `nextChapter`, `prevChapter`, `setRate`, `setLanguage`, `getState`, `listCachedBooks`, `removeCachedBook`; eventy `onParagraph`, `onStateChange` přes `notifyListeners` |
| `player/TTSPlaybackEngine.kt` | **nový** — vlastní engine (viz A.2) |
| `player/TTSBookCache.kt` | **nový** — JSON cache + LRU (viz A.4) |
| `data/TTSBook.kt` | **nový** — Jackson data classes `TTSBook/TTSChapter/TTSParagraph` (stejný styl jako `DeviceClasses.kt`) |
| `player/PlayerNotificationService.kt` | režim `AUDIO / TTS`; start TTS zastaví ExoPlayer a naopak; playback state + metadata z enginu |
| `player/MediaSessionCallback.kt` | routing callbacků do enginu v TTS režimu; `onPlayFromMediaId` pro `ebook__` id (F2) |
| `player/BrowseTree.kt` | (F2) kategorie „E-knihy“ z `TTSBookCache.list()` |
| `MainActivity.kt` | `registerPlugin(AbsTTSPlayer::class.java)` |

**iOS (F3, mimo rozsah F1):** `App/plugins/AbsTTSPlayer.swift` (`CAPBridgedPlugin`),
`Shared/player/TTSPlayer.swift` — stejný kontrakt pluginu, do té doby na iOS
běží web fallback z `AbsTTSPlayer.js` (= dnešní chování v1).

### A.2 `TTSPlaybackEngine` (Kotlin) — návrh třídy

```kotlin
class TTSPlaybackEngine(
  val context: Context,
  val listener: Listener            // implementuje PlayerNotificationService
) : TextToSpeech.OnInitListener {

  interface Listener {
    fun onTTSStateChange(state: TTSState)          // → media session + notifikace + JS event
    fun onTTSParagraph(chapterIdx: Int, paragraphIdx: Int, location: String?)
  }

  private var tts: TextToSpeech? = null            // lazy init, onInit -> READY
  private var book: TTSBook? = null
  private var chapterIndex = 0
  private var paragraphIndex = 0
  private var chunkQueue: ArrayDeque<String> = ArrayDeque()  // věty aktuálního odstavce
  private var sessionId = 0                        // stejný „session guard“ jako v JS v1
  var rate: Float = 1f
  var language: String = "en-US"
  var state: TTSState = STOPPED                    // STOPPED | PLAYING | PAUSED

  fun prepare(book: TTSBook, startChapter: Int, startParagraph: Int)
  fun play(); fun pause(); fun stop()
  fun seekTo(chapter: Int, paragraph: Int)
  fun seekParagraph(delta: Int)                    // pro skip ±  z notifikace/BT
  fun setPlaybackRate(r: Float)                    // tts.setSpeechRate + přepočet času

  // interní tok:
  // speakNextChunk(): utteranceId = "$sessionId-$chapterIndex-$paragraphIndex-$chunkIdx"
  //   tts.speak(chunk, QUEUE_FLUSH, params, utteranceId)
  // UtteranceProgressListener.onDone(id):
  //   - id nepatří aktuální session -> ignoruj (guard)
  //   - další chunk / další odstavec (emit onTTSParagraph) / další kapitola / konec -> stop
  // chunkování: port splitTextChunks() z mixins/ttsPlayer.js (~300 znaků, hranice vět)

  // odhad času pro media session (A.3):
  // positionMs = (charsSpokenBefore / CHARS_PER_SEC / rate) * 1000
  // durationMs = (book.totalChars / CHARS_PER_SEC / rate) * 1000, CHARS_PER_SEC ≈ 15
}
```

Zásady:

- `TextToSpeech` init je async — volání `play()` před `onInit` se zařadí a
  provede po READY; chybový stav initu → JS event + toast.
- Jazyk: `tts.setLanguage(Locale.forLanguageTag(language))`; návratový kód
  `LANG_MISSING_DATA / LANG_NOT_SUPPORTED` → event `onStateChange(error=…)`,
  JS ukáže existující toast `MessageReadAloudNoVoice`.
- Engine sám nic nekreslí ani nesynchronizuje — jen mluví a hlásí pozici.

### A.3 Media session mapping (TTS režim)

| MediaSession callback | Akce enginu |
| --- | --- |
| `onPlay` / `onPause` | `play()` / `pause()` |
| `onStop` | `stop()` + ukončení TTS session (zpět do AUDIO režimu) |
| `onSkipToNext` / `onSkipToPrevious` | `seekTo(chapter±1, 0)` |
| `onFastForward` / `onRewind` | `seekParagraph(+1)` / `seekParagraph(-1)` |
| `onSeekTo(pos)` | pos → znaky → nejbližší odstavec → `seekTo` |
| `onSetPlaybackSpeed(speed)` | `setPlaybackRate` |

Metadata: `METADATA_KEY_TITLE` = titul knihy, `ARTIST` = autor,
`ALBUM` = název kapitoly, `ART` = obálka z cache, `DURATION` = odhad (A.2).
`PlaybackState` přepíná `STATE_PLAYING/PAUSED/STOPPED` podle enginu.

### A.4 `TTSBookCache`

- Adresář `filesDir/tts-cache/`, soubor `<libraryItemId>.json`
  (serializovaný `TTSBook`) + `<libraryItemId>.meta.json` (titul, autor,
  cesta k obálce, totalChars, lastAccessed — kvůli rychlému listování bez
  načítání celé knihy).
- `prepareBook` přepíše obě části a aktualizuje `lastAccessed`.
- LRU limit: max ~20 knih nebo 50 MB (konfigurovatelné konstanty) — při
  překročení se maže nejstarší `lastAccessed` (stejný princip jako epub
  locations cache v JS).
- Obálka: zkopíruje se do cache (Android Auto ji potřebuje i bez serveru).

### A.5 Klíčové toky

**Start ze čtečky:** čtečka `ttsExtractBook()` → `prepareBook(book)`
(uloží cache, připraví session) → `play({chapterIndex, paragraphIndex})` →
služba přejde do TTS režimu (zastaví případné audio), foreground notifikace,
engine mluví → `onParagraph` eventy → otevřená čtečka listuje follow-along.

**Zhasnutá obrazovka:** WebView se suspenduje, engine ve foreground službě
jede dál; po odemknutí čtečka z `getState()` dorovná pozici.

**Start z Android Auto (F2):** browse „E-knihy“ → `onPlayFromMediaId("ebook__<id>")`
→ `TTSBookCache.load(id)` → `prepare` od poslední pozice (z uloženého
progressu) → `play`. Aplikace nemusí být otevřená.

**Konec knihy:** engine `stop()` + `onStateChange(STOPPED, endOfBook=true)` →
progress 100 %, notifikace zmizí, služba se vrátí do AUDIO režimu.

### A.6 Synchronizace průběhu

Po každém odstavci engine spočítá `ebookLocation` (location odstavce) a
`ebookProgress = charsSpokenTotal / totalChars`; zápis lokálně (Realm/DB
stejně jako `updateLocalEbookProgress`) a na server `PATCH /api/me/progress/:id`
— **throttling 15 s** jako u audia (`MediaProgressSyncer` vzor). Formát je
identický s tím, co ukládá čtečka → obousměrná návaznost čtení/poslech.

### A.7 Pravidla fallbacku v JS

```js
const useNative = Capacitor.getPlatform() === 'android'   // F1
  && Capacitor.isPluginAvailable('AbsTTSPlayer')
// jinak: dnešní WebView smyčka (mixin) — iOS do F3, staré buildy, web
```

Mixin API vůči čtečkám i `Reader.vue` liště se nemění — přepnutí je
transparentní.

### A.8 Stav implementace F1

První řez F1 je v kódu (commit „Implement F1 slice…“):

- [x] JS: `plugins/capacitor/AbsTTSPlayer.js`, `ttsExtractBook()` ve všech třech
  čtečkách, mixin deleguje na nativní plugin, follow-along z `onParagraph`
  eventů, re-sync stavu při otevření čtečky (`getState`)
- [x] Android: `TTSBook.kt`, `TTSBookCache.kt` (LRU), `TTSPlaybackEngine.kt`
  (TextToSpeech + session guard + chunker), `AbsTTSPlayer.kt` bridge,
  TTS sekce v `PlayerNotificationService` (pauza audia, foreground
  MediaStyle notifikace s play/pause/stop akcemi), registrace v `MainActivity`
- [x] Build a základní scénáře ověřeny na zařízení (Pixel 8 Pro): přehrávání,
  zhasnutá obrazovka, ovládání ze zamykací obrazovky; **plná manuální
  matice A.9 (Doze 30+ min, přerušení hovorem, …) zatím neproběhla**
- [x] Media session takeover — při aktivní TTS session se sdílená media
  session přepne na engine (odpojený MediaSessionConnector, PlaybackState
  a metadata z TTSBook, routing dle A.3 vč. BT/headset tlačítek a seek
  lišty); ověřeno na zařízení (zamykací obrazovka, Android 14+)
- [x] Nativní sync průběhu dle A.6 — `TTSProgressSyncer` (15s timer, flush při
  pauze/stopu/konci knihy): lokální položky přes `DbManager` + event do
  WebView, server `PATCH /api/me/progress/:id` (lokální navázané na server
  i streamované; na metered síti po 60 s jako `MediaProgressSyncer`);
  konec knihy hlásí 100 % (`endOfBookReached`); **ověření na zařízení zatím
  neproběhlo**
- [ ] F2: kategorie „E-knihy“ v `BrowseTree` + `onPlayFromMediaId`
- [ ] F3/F4: iOS engine, CarPlay

### A.9 Testovací plán F1

- **Unit (Kotlin):** chunker (parita s JS `splitTextChunks` na sadě českých
  a anglických textů vč. zkratek a dlouhých vět), výpočet pozice/odhadu času,
  LRU cache.
- **Manuální matice:** zhasnutá obrazovka 30+ min / Doze (`adb shell dumpsys
  deviceidle force-idle`) / BT ovládání / přepnutí audio↔TTS / změna rychlosti
  a jazyka za běhu / kniha bez českého hlasu / prázdné kapitoly / restart
  služby systémem (`onStartCommand` recovery).
- **Android Auto (F2):** Desktop Head Unit (DHU) — browse, výběr, ovládání,
  metadata, obnovení po odpojení.
- **Regrese:** přehrávání audioknih (focus, notifikace, Cast) nesmí být
  TTS režimem dotčeno.
