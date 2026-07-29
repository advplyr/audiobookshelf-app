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
