# Menyarkitektur – inventering och förslag (etapp 1)

Underlag för att göra Klipps gränssnitt mer sammanhängande på telefon. **Bara inventering och förslag – inget är
ändrat i koden.** Implementation väntar på godkännande.

- Gren: `ui/menu-architecture` (från `design-test` 034c077; den frysta betan 0.19.3-beta.1 och kandidaten
  0.19.3-beta.2 är orörda).
- Kodbas: 0.19.3-beta.2 (versionCode 99). Inventerad 2026-11-17.
- Metod: varje kontroll följd från sin `onClick`/gest till den funktion eller det motorkommando den anropar
  (inte bara strängsökning). Etiketter lösta mot `res/values` (EN) och `res/values-sv` (SV). Fyra delgenomgångar
  (skal/lägg till/projekt/inställningar, tidslinje/egenskaper/nyckelrutor, text, färg/ljud) och stickprov i koden av de
  viktigaste påståendena (markerade ✓).
- Filreferenser är relativa till `app/src/main/java/se/gendut/klipp/`; `PC` = `engine/ProjectController.kt`.

---

## 1. Hur appen är byggd i dag

```
Toppfält:  [Projekt]  [Redigera | Färg | Ljud | Text]  [Exportera]
           (lägen: tryck, dra längs raden eller tre fingrar)
Bild:      Programmonitor + transport (◀ ▶ · föregående klippkant · Spela · nästa · tidkod → Gå till tid)
           ersätts av källmonitorn när en källa är öppen (på alla sidor)
Sidor:     0 Redigera  – verktygsrad [Verktyg ▾][Magnet][Länk][Markör][⋯][Kugghjul] + tidslinje
           1 Färg      – egen sida (klippremsa, noder, verktyg, rutnät: Galleri | Klippets färg | Tidslinje)
           2 Ljud      – Mixer | Ljudstyrka | Duckning (flikarna i dockan)
           3 Text      – Titlar | Undertexter | Klipp med text (flikarna i dockan)
Botten:    Markeringschip · [Ångra|Gör om] · Docka (byter innehåll efter läge och markering)
           Redigera, inget markerat → Media · Ljud · Titel · Textning · Röst   (= dagens "Lägg till")
           ett klipp → video: Ta bort · Bild · Kopiera · Dela · Hastighet · Mer
                       ljud:  Ta bort · Dela · Volym · Effekter · Hastighet · Mer
                       titel: Ta bort · Redigera · Dela · Etikett · Stäng av
           flera     → Ta bort · Rippla · Gruppera · Etikett · Av/på · Synka
           undertext → Ta bort · Redigera · Dela · Ny       övergång → Ta bort · Klar
Överlägg:  Projektsidan · Appinställningar · Exportdialog · Projektinställningar (via ⋯ i tidslinjen)
```

Appen har alltså redan en "Lägg till"-rad (dockan när inget är markerat) och ett "markera → egenskaper"-mönster.
Det som spretar är framför allt **Text-läget**, **namnen**, **omfattningen** och **Tillbaka**.

---

## 2. Inventering

Kolumner: Åtgärd · Nuvarande ingång · Villkor/markering · Omfattning · Destination · Kodkälla (kontroll → hanterare).

### 2.1 Lägen, transport och monitorer

| Åtgärd | Nuvarande ingång | Villkor | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|---|
| Byt läge | Toppfältets lägesknappar; dra längs raden; tre fingrar | – | app-UI | `goTo(i)` | Shell.kt:176, 139-168 → App.kt:638, 708-746 |
| Ångra / gör om | Botten [Ångra\|Gör om]; två-/trefingertryck | något att ångra | projekt (inkl. färgsteg) | `state.undo/redo` | Shell.kt:473-481, App.kt:747-749 |
| Ruta fram/bak | Transport ◀ ▶; håll ett finger + tryck med ett andra på bilden | ej källa | spelhuvud | `snapFrame(±FRAME)` | Shell.kt:219-224; Scrub.kt:128-137 |
| Föregående/nästa klippkant | Transport (stannar även på markörer) | – | spelhuvud | – | Shell.kt:227-257 |
| Gå till tid | Tryck på tidkoden → dialog | – | spelhuvud | `TimecodeDialog` | Shell.kt:262, 281 |
| Skrubba program | Dra i bilden (lägre finger = långsammare) | ej Egenskaper/källa | spelhuvud | `monitorTouch` | App.kt:780-788; Scrub.kt:94-170 |
| Helskärm | Dubbeltryck på bilden; Färg: knapp | ej källa | vy | `FullViewer` | App.kt:784-793 |
| Monitorhöjd | Dra greppet under transporten | stående | app-inställning | `AppPrefs.setMonitorHeight` | App.kt:915-919 |
| Öppna källa | Media-arket → tryck på kort; klippdockan "Kopiera" | – | källobjekt | `sourceMedia` / `PC.copyToSource` (skriver in/ut på mediaobjektet) | MediaLibrary.kt:233; Shell.kt:764 → App.kt:623-636, PC:357 |
| Markera in/ut | Källa: knappar, handtag under skrubblisten | källa öppen | källobjektets märken (sparas) | `PC.setMarks` | RealMode.kt:626-647 |
| Infoga / Skriv över | Källa: knappar | märken | sekvens vid spelhuvudet, målmarkerade spår | `PC.editFromSource` → `source.insert/overwrite` | RealMode.kt:652-653 → PC:337-353 |
| Målspår för infogning | Tryck på spårhuvud | – | infogningsmål | `timeline.setTargeting` | Timeline.kt:505-512 → PC:1474 |
| Stäng källa | ← i källhuvudet; systemets Tillbaka | – | – | `sourceMedia = null` | RealMode.kt:595; App.kt:662 |

### 2.2 Lägg till och import

| Åtgärd | Nuvarande ingång | Villkor | Hamnar | Destination | Kodkälla |
|---|---|---|---|---|---|
| Media-arket | Redigera, inget markerat → "Media" | projekt; tom mediepool öppnar väljaren direkt | – | `MediaSheet` | Shell.kt:640 → App.kt:648, 977 |
| Importera video | Media-arket → "Importera" | – | **mediepoolen** (bara video: `VideoOnly`) | `PC.import` → `file.import` | MediaLibrary.kt:184; RealMode.kt:529-532 → PC:175 |
| Mappar, flytta, byt namn, ta bort media, hitta saknade filer | Media-arket (långtryck = markera) | – | pool/mappar (ta bort tar även klippen) | `moveToBin`, `renameMedia/Bin`, `deleteMedia`, `relinkFromFolder` | MediaLibrary.kt:130-313 → PC:120-333 |
| Ljud-arket | Redigera → "Ljud" → Paket · Freesound · Tal · Filer | projekt | **tidslinjen** vid spelhuvudet, första lediga A1–A3, skriv över (+ poolen) | `PC.placeSound` | Shell.kt:641 → App.kt:649, 969 → SoundFxUi.kt:59-245 → PC:1578 |
| Text till tal | Ljud-arket → "Tal" | – | som ovan | `SpeechFile.make` → `placeSound` | SoundFxUi.kt:81 → Panels.kt:537 |
| Berättarröst | Redigera → "Röst" → arket "Berättarröst": välj A1–A3, Spela in/Stopp | mikrofon | valt A-spår vid inspelningsstart | `VoiceOverRecorder` → `PC.placeRecording` | Shell.kt:644 → AddSheets.kt:53 → Panels.kt:519 → PC:1573 |
| Titel | Redigera → "Titel" → mall, entré, text → "Lägg till vid …" | projekt | ny titel vid spelhuvudet (videospår ovanför det översta upptagna) | `TitlesTab` → `addTitle` → `graphics.newText` | Shell.kt:642 → AddSheets.kt:61 → TitleUi.kt:284-311 → PC:1964 |
| Titel (2) | Text-läget → "Titlar" | – | samma | **samma `TitlesTab`** | TitleUi.kt:275 ✓ |
| Undertexter | Redigera → "**Textning**" → arket | projekt | – | `CaptionsTab` | Shell.kt:643 → AddSheets.kt:70 ✓ |
| Undertexter (2) | Text-läget → "**Undertexter**" | – | – | **samma `CaptionsTab`** | TitleUi.kt:276 ✓ |
| Importera LUT | Färg › nod › LUT "+ Importera .cube"; Färg › rutnät › Tidslinje | Färg | nod resp. tidslinjens look (biblioteket appvitt) | `lutPicker` → `LutStore.import` | App.kt:189-197; CT:106; Panels.kt:239 |

### 2.3 Tidslinjen: klipp, spår, markering, markörer

| Åtgärd | Nuvarande ingång | Villkor | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|---|
| Dela vid spelhuvudet | Docka "Dela" | ett klipp | klippet (länkat ljud enligt motorn) | `timeline.razor{clip}` | Shell.kt:744 → Model.kt:963 → PC:482 |
| Dela där man trycker / över spår | Rakbladsverktyget; snabbt svep nedåt på klipp | verktyg | klippet / alla olåsta klipp på spåren man drar över | `timeline.razor{clip}` / `razorTracks` | Timeline.kt:1162-1184, 1458 → PC:590 |
| Trimma, rippeltrimma, slip, roll | Dra kant (Flytta/Rippla); Trimma-verktyget: dra kropp/klippkant | verktyg | klipp (+ länkad partner) | `timeline.trim/slip/roll` | Timeline.kt:629-658, 1204-1317 → PC:445, 636, 640 |
| Flytta / infoga-flytta | Dra klippet (Flytta = skriv över, Rippla = infoga) | ej låst spår | klipp + partner / gruppen | `timeline.move` | Timeline.kt:1019-1035, 1234-1265 → PC:440, 548 |
| Ta bort (lämna lucka) | Docka "Radera" | ett klipp | klippet | `edit.clear` | Shell.kt:743 → PC:544 |
| Ripple-radera ett klipp | **Bara gest**: dra upp ur tidslinjen | – | klippet | `edit.rippleDelete` | Timeline.kt:1257-1264, 1501-1521 |
| Ta bort / rippla flera | Flera-dockan "Radera" / "Rippla" | lasso | markerade klipp + undertexter | `edit.clear` / ripple + `captions.delete` | Shell.kt:794-795 → PC:576-584 |
| Hastighet, ramp | Docka "Hastighet" → 25–400 % / Ramp | video/ljud | klipp (ramp delar klippet) | `clip.speedDuration` | Shell.kt:673-695 → PC:1392, 1428 |
| Baklänges, frys | Docka "Mer" | video (frys), video/ljud | klipp | `clip.speedDuration{reverse}`, `clip.insertFrameHoldSegment` | Shell.kt:712-731 → PC:493, 506 |
| Stäng av/slå på | Docka "Stäng av" / flera: "Av/på" | – | klipp | `clip.enable` | Shell.kt:666, 804 → PC:650 |
| Etikett | Docka/flera "Etikett" | – | klipp **+ länkade partner även med länkad markering av** | `klipp.setLabel` | Shell.kt:665, 803 → Model.kt:986 |
| Gruppera/dela upp, synka på ljud | Flera-dockan | ≥2 | markeringen | `clip.group/ungroup`, `clip.synchronize` | Shell.kt:797-806 → PC:521, 534 |
| Takt-markörer | Docka "Mer" → "Takter" | ej titel | **sekvensens markörer** från klippets ljud | `markers.add` ×n | Shell.kt:733 → PC:1455 |
| Toning in/ut | Dra hörnhandtaget på markerat klipp; Egenskaper "Övertoning" (**bara titlar**) | – | klippkant | `setTransition` | Timeline.kt:598-612; Properties.kt:341 |
| Övergång | Tryck på klippkant → kort (typ, längd); markera övergång → kort + docka | – | klippkant/övergång | `addCutTransition`, `resizeTransition`, `setTransitionType` | Shell.kt:890-1016 → PC:1875-1956 |
| Markera | Tryck (igen = avmarkera); lasso: håll stilla på tomt spår och dra; grupperat klipp = hela gruppen | – | klipp/undertexter | `tapSelect`, `lassoSelectAll` | Timeline.kt:1036-1103, 1467 → Model.kt:562-619 |
| Verktyg | Verktygsrad: Flytta/Rippla/Trimma/Rakblad (sparas inte) | – | gester | `tool` | Shell.kt:330-356; App.kt:158 |
| Magnet, länkad markering | Verktygsrad | – | session / sekvens | `state.magnet`, `sequence.linkedSelection` | Shell.kt:358-367 → PC:1383 |
| Markör lägg till/ta bort, ta bort alla | Verktygsrad markör; ⋯ → "Ta bort alla markörer" | – | sekvens | `markers.add/delete/clearAll` | Shell.kt:369-409 → PC:1436-1452 |
| Låst spelhuvud, volymlinjer | ⋯ "Tidslinjelägen" | – | session | `centerPlayhead`, `volLines` | Shell.kt:396-397 |
| Spår: dölj/tysta, lås/solo/synklås | Spårhuvudets öga/högtalare; långtryck → spårmeny | – | spår | `timeline.setTrack` | Timeline.kt:507-540, 2235-2253 → PC:646 |
| Zoom, scrolla, skrubba linjal | Nyp; dra tomt spår/minikarta; dra linjalen | – | vy/spelhuvud | – | Timeline.kt:436-459, 1118-1152, 1354-1374 |
| Finns inte | duplicera, klistra in klipp, kopiera/klistra in attribut, lossa ljud, länka, ersätt, nästla, markera allt, lägga till/byta namn på spår, ny sekvens | – | – | – | grep i UI och PC |

### 2.4 Egenskaper och nyckelrutor

| Åtgärd | Nuvarande ingång | Villkor | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|---|
| Bild (video/bild) | Docka "Bild" → Position · Beskär · Blandning · Nyckling · Mask · Stabilisera · Oskärpa | videoklipp | klipp; **Beskär/Blandning/Nyckling/Mask/Stab/Oskärpa lagras i klippets färg (`ClipGrade`)** | `setProps` / `gs.set(layer/stab/mblur)` | Shell.kt:763 → Properties.kt:283-312, 467-540 |
| Titel | Docka "Redigera"; Text-läget/Titel-arket listrad | titel | titeln | Egenskaper grupp 0: Text · Stil · Animera · Följ + Rörelse · Övertoning | Shell.kt:748; TitleUi.kt:313-324; Properties.kt:292 |
| Ljud | Docka "Effekter" (öppnar fliken Ljudeffekter) | ljudklipp | klipp | Egenskaper: Volym (nyckelrutor) · Ljudeffekter | Shell.kt:757; Properties.kt:261-288 |
| Dra/nyp/rotera i bilden | Egenskaper öppna | alla flikar utom Beskär/Fäst/Ruta | klipp | `MotionOverlay` → `setProps` | RealMode.kt:255-258 |
| Nyckelrutor | Stoppur, ◀ ◆ ▶, spår under reglaget, långtryck = easing-meny, graf | animerbara reglage | egenskap | `effects.toggleAnimation/setKeyframe/moveKeyframe/setInterpolation` | KeyframeUi.kt:231-243 → PC:1662-1790 |
| Mask-/beskärningsnycklar | samma knappar, annan modell (källtid, Linjär→Mjuk→Håll) | – | – | `boxAnim` i färgen | Properties.kt:516-550 |
| Volymlinje | ⋯ "Volymlinjer" → tryck/dra/dubbeltryck på linjen | – | klipp | `addLevelKey/moveLevelKey/setLevelLine/deleteLevelKey` | Timeline.kt:891-908 → PC:1789-1827 |

### 2.5 Titlar, undertexter och transkription

| Åtgärd | Nuvarande ingång | Villkor | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|---|
| Skapa titel | Redigera → Titel; Text → Titlar | projekt | ny titel | `addTitle` | se 2.2 |
| Ändra titel | Markera titel → "Redigera"; listrad | – | **en** titel (ingen gemensam titelstil) | Egenskaper | se 2.4 |
| Ny undertext | Undertexter-arket "Ny undertext"; undertextdockan "Ny" | – | en undertext på första aktiva undertextspåret | `captions.add` → inspektören öppnas | TitleUi.kt:334; CaptionUi.kt:79 → PC:2032 |
| Importera SRT/VTT | Undertexter-arket | – | **ett nytt undertextspår per fil** | `captions.import` | TitleUi.kt:337 → PC:2084-2105 |
| Skapa från tal | Undertexter-arket → kortet "Skapa undertexter automatiskt": språk, kvalitet, **ett** ljudspår, Generera | sekvens | ersätter spåret "Automatiska undertexter" **inklusive manuella rättelser** ✓ | `AutoCaptions.start` → `importCaptionFile` | TitleUi.kt:571-612 → Transcribe.kt:522 → PC:2111 ✓ |
| Visa/dölj | Arket; "TXT"-banans öga | spår finns | **alla** undertextspår | `captions.setTrack{enabled}` | TitleUi.kt:350; Timeline.kt:477 → PC:2208 |
| Stil | Arket → "Typsnitt & stil"; inspektören | – | **hela spåret** – arket tar spåret för `captions.first()`, inspektören den markerades spår ✓ | `captions.setStyle` (+ ordmarkering i spårnamnet) | TitleUi.kt:356; CaptionUi.kt:106 → PC:2052-2080 |
| Rätta text (1) | Arkets lista → inline-redigerare | – | en undertext; **kräver Spara, tappar text när raden stängs** ✓ | `captions.setText` | TitleUi.kt:357-366, 549-564 ✓ |
| Rätta text (2) | Tryck i TXT-banan → docka "Redigera" → inspektör | – | en undertext; sparar efter 700 ms och vid stängning | `captions.setText` | CaptionUi.kt:73, 89-135 |
| Ändra tid | **Bara** dra blocket/kanterna i TXT-banan | – | en undertext/grupp | `captions.move/trim` | Timeline.kt:686-823 → PC:2016-2024 |
| Dela/ta bort undertext | Undertextdockan | – | en | `captions.split/delete` | CaptionUi.kt:69-78 |
| Sekvensens transkription | Text-läget → "Klipp med text" → kort → "Öppna utskriften" → ark | – | sekvens (härledd ur mediernas transkript) | `transcript.inspect` | TitleUi.kt:277 → TranscriptUi.kt:47-75 |
| Transkribera sekvensen | Arket (bara om inga ord finns; annars "Skriv ut igen" = allt om) | – | alla objekt på alla ljudspår | `transcript.set` per objekt | TranscriptUi.kt:83-113 → Transcribe.kt:562 |
| Ta bort ord | Arket: tryck ord, utöka → "Radera N ord" | markering | **målmarkerade spår**; undertexter förskjuts men **behåller orden** | `transcript.extract` | TranscriptUi.kt:100-106 → PC:2175 ✓ |
| Ta bort pauser / utfyllnadsord | Arket | – | alla olåsta spår | `transcript.removePauses/removeFillers` | TranscriptUi.kt:108-111 → PC:2187-2203 |
| Källans transkription | Källa → ikonen "Text" → transkribera; tryck meningar = in/ut | källa | ett mediaobjekt | `transcript.set`; `setMarks` | RealMode.kt:604-624; SourceTextUi.kt:122-180 |
| Standardlängd titel/undertext | Appinställningar › Tidslinje | – | app | `AppPrefs` | Settings.kt:199-206 |

Datamodell (verifierad i kod och motorn): titel = grafikklipp på ett **videospår**, stil per titel i lagrets namn;
undertexter = egna **undertextspår** på sekvensen (inte klipp), stil **per spår**; transkript lagras **per mediaobjekt**
och återanvänds av källvyn, sekvensvyn och "Skapa från tal". Undertexter är en engångskopia av orden.

### 2.6 Färg

| Åtgärd | Nuvarande ingång | Villkor | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|---|
| Vilket klipp | Markerat klipp, annars översta under spelhuvudet; klippremsan | V1–V3 | **global markering + spelhuvud** | `currentColorClip` | CP:315-365; App.kt:762-770 |
| Noder | "+", långtryck: byt namn, förbigå, cacha, återställ, ta bort | – | nod | `gs.set` | CP:370-456 |
| Verktyg | Hjul · Kurvor · Justera · LUT · Kvalificerare · Fönster · Film | nod | nod | `commitNode` | CP:631-696; CT |
| Kopiera/klistra in färg (1) | Hjul → "Kopiera färg"/"Klistra in färg" (en gång per klipp) | – | klipp | `copyOrPaste` | CP:617-629, 960 |
| Kopiera/klistra in färg (2) | Rutnät → "Klippets färg" → Kopiera / Klistra in | – | klipp | `gs.set(forOtherClip)` | CP:751-761 ✓ |
| Alla klipp | Rutnät → Klippets färg → "Alla klipp" | – | **alla videoklipp V1–V3** | `gs.setAll` | CP:762-766 ✓ |
| Återställ | Rutnät → Klippets färg → Återställ (ingen bekräftelse) | – | **hela `ClipGrade` – även Bild-inställningar** ✓ | `gs.set(ClipGrade())` | CP:767 ✓ |
| Stillbild: ta, jämför, använd färgen | Rutnät → Galleri | – | appvitt galleri; klipp | `StillStore`, `gs.set` | CT:912-925; CP:396-405 |
| Tidslinjens look | Rutnät → fliken "Tidslinje" | – | **sekvensens utsignal** | `ColorGrade` → `playback.look` | Panels.kt:196-260 |
| "Original" | Rutnät → Tidslinje | – | **appvid proxyinställning** (omvänd mot Inställningar) | `Proxies.switch` | CP:773 |
| Jämför, scope, helskärm | Chips över bilden | Färg | vy | – | CP:817-847 |

### 2.7 Ljud

| Åtgärd | Nuvarande ingång | Villkor | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|---|
| Klippvolym (1) | Markera ljudklipp → "Volym" → reglage | ljudklipp | klipp (**skriver nyckelruta** om nivån är animerad) | `setParam volume/level` | Shell.kt:696-709 → PC:1853 |
| Klippvolym (2) | Markera ljudklipp → "Effekter" → Egenskaper → Volym | ljudklipp | klipp + nyckelrutor | samma parameter | Shell.kt:757; Properties.kt:349 |
| Klippvolym (3) | ⋯ → Volymlinjer → dra linjen | – | klipp | samma parameter | Timeline.kt:891-908 |
| Spårvolym | Ljud → Mixer → reglage A1–A3 (**skriver nyckelruta** om spåret har automation; "AUTO") | – | spår | `timeline.setTrack` / `mixer.setKeyframe` | Panels.kt:575-612 → App.kt:569-586 |
| Mix (master) | Ljud → Mixer → "Mix" | – | hela mixen (app-sidan) | `playback.master` | App.kt:565-578 |
| Tysta/solo | Mixer M/S; spårhuvud; spårmeny | – | spår | `timeline.setTrack` | Panels.kt:641; Timeline.kt:538, 2248 |
| Spårautomation | Mixer → "Volymautomation" ◆ / Rensa | – | spår | `mixer.setKeyframe/clearLane` | Panels.kt:437-449 |
| Ljudstyrka, normalisera | Ljud → Ljudstyrka (målvärdet sparas inte) | – | mixen | `measureLoudness`, `normDb` | Panels.kt:458-505 |
| Duckning | Ljud → Duckning | – | **alla klipp på spåret** (ersätter deras nivånycklar) | `essentialSound.*` | Panels.kt:305-333 → PC:1518-1552 |
| Ljudeffekter på klipp | Egenskaper → Ljudeffekter | ljudklipp | klipp | `effects.apply/...` | AudioUi.kt:30-86 |

### 2.8 Rendering, proxy, cache, projekt, export, inställningar

| Åtgärd | Nuvarande ingång | Omfattning | Destination | Kodkälla |
|---|---|---|---|---|
| Rendera / Rendera val / stoppa | Chip uppe till höger i tidslinjen (bara när tunga delar saknas) | tidslinjen / markeringens tid | `RenderCache.renderAll/renderRange` | App.kt:1108-1147 |
| Rendera tidslinjen, Rendera automatiskt, Ta bort renderingar | ⋯ → **Projekt**inställningar | rendera = projekt; **automatiskt och ta bort = alla projekt** | `RenderCache` | App.kt:1012-1021 |
| Proxy, nodcache, renderingar, lagring | Appinställningar › Uppspelning | app | `Proxies`, `NodeCache`, `RenderCache` | Settings.kt:236-262 |
| Projektinställningar (namn, format, fps, samplingsfrekvens) | Tidslinjens ⋯ (test-ID `projectTitle`) | projekt/sekvens | `sequence.settings` | Shell.kt:410-416 |
| Projekt: öppna, nytt, byt namn, duplicera, ta bort, sök | Toppfältet [Projekt] | app | `PC.*Project` | ProjectsScreen.kt |
| Säkerhetskopiera nu / välj mapp | Projektmeny | projekt (med media) | `PC.backupProject` | ProjectsScreen.kt:318-334 |
| "Exportera med media till fil…", "Exportera bara projektfilen…" | Projektmeny | projektpaket | `PC.exportProject` | ProjectsScreen.kt:331-332 |
| Tidigare versioner | Projektmeny (texten säger "öppnas", men den skapas bara) | ny projektkopia | `PC.restoreVersion` | ProjectsScreen.kt:333; PC:1155 |
| Importera (projektpaket) | Projektsidan | nytt projekt (öppnas inte) | `PC.importProject` | ProjectsScreen.kt:548-567 |
| Exportera film | Toppfältet [Exportera] → dialog | sekvens; inställningar sparas per projekt | `ExportJobs.start` | Shell.kt:121 → App.kt:229-261 |
| Appinställningar | Kugghjul i tidslinjen, på projektsidan och i projektinställningarna | app | `SettingsScreen` | Shell.kt:420; ProjectsScreen.kt:257 |
| Hjälp | Appinställningar › Hjälp (gestlista, visa tips igen) | app | – | Settings.kt:283-297 |

---

## 3. Klassning av fynden

**Bra genvägar till samma redigerare** (behålls): Titel-arket = Text › Titlar och Undertexter-arket = Text › Undertexter
(samma komponenter); listrad och docka "Redigera" öppnar samma titelegenskaper; tom programmonitor = Media; dra i
bilden = Rörelse-reglagen; hörnhandtag = Övertoning; övergångens kant = Längd-reglaget; kugghjulen öppnar samma
inställningar; ett transkript per mediaobjekt används av källvy, sekvensvy och "Skapa från tal".

**Rimliga skillnader mellan källa och sekvens** (behålls): källvyn väljer **meningar** och sätter in/ut;
sekvensvyn väljer **ord** och tar bort; egna transporter för källa och program; klippfärg mot tidslinjens look; klipp-,
spår- och mixvolym är tre verkliga nivåer.

**Verkliga parallella redigeringsvägar** (bör samlas):
- Två redigerare för undertexttext: listans inline-redigerare (Spara krävs, tappar text) och inspektören (sparar själv).
- Två mål för undertextstil: arket redigerar det första spåret, inspektören den markerades spår – utan att visa vilket.
- Tre implementationer av "kopiera färg" med olika regler (hjulknappen, rutnätet, stillbild).
- Tre igenkänningskort med egna kopior av hämta/starta/avbryt (Skapa från tal, sekvensarket, källvyn).
- Två klippvolymreglage med samma hanterare: dockans saknar nyckelrutor, Egenskapers nås via "Effekter".
- Två nyckelrutesystem (motorns för rörelse/opacitet/nivå; färgens för mask/beskärning) med olika namn på kurvorna.
- "Ta bort renderingar" på två ställen med egen kod.

**Olika namn för samma sak:**
- Undertexter: "Textning" (Lägg till och arkets rubrik) mot "Undertexter" (överallt annars).
- Transkription: "Klipp med text", "utskrift", "Skriv ut talet", "transkript", "Transkribera", "Text"
  (källknappen).
- Spelhuvud: "Låst spelhuvud" (menyn) mot "Spelhuvudet i mitten" (Inställningar), som är samma växel.
- Ta bort: "Radera" mot "Ta bort".
- Klipp på/av: "Stäng av/Slå på" mot "Av/på".
- Ljudeffekter: "Effekter" mot "Ljudeffekter".
- Röst: "Röst" mot "Berättarröst".
- Proxy: "Original" (Färg) mot "Proxy av/på" (Inställningar), med omvänd betydelse.
- Rörelsefliken: "Rörelse" och "Position" (samma flik).
- Tint: "Ton" mot "Nyans".

**Samma namn för olika saker:**
- **"Text"** betyder minst fem saker:
  - läget;
  - titelns flik;
  - undertextstilens flik;
  - källans transkriptionsknapp;
  - Fri text-mallens fält.

  Dessutom betyder "TXT" undertexter och "Radera text" ord.
- **"Ljud"** är både läget (mixern) och Lägg till-knappen (ljudbiblioteket).
- **"Ljudeffekter"** är både klippets ljudbehandling och ljudbiblioteket.
- **"Kopiera"** betyder "öppna klippets del i källan" i dockan men kopiera färg på färgsidan.
- **"Redigera"** är läget, titeldockans och undertextdockans knapp.
- **"Rippla"** är både verktyg och radering.
- **"Övertoning"** är både toning och korstoning.
- **"Oskärpa"** är både rörelseoskärpa och fönstrets oskärpa.
- **"Opacitet"** finns två gånger på en titel (lagrets och klippets).
- **"Återställ"** gäller allt från en kurva till hela klippets färg.
- **"Importera" och "Exportera"** gäller både film och projektpaket.

**Oklar placering eller omfattning:**
- Sekvensens transkription nås bara i fyra steg via Text-läget, och Text-läget saknar tidslinje.
- Stiländringars omfattning (spår) syns inte.
- "Alla klipp" betyder V1–V3.
- Återställ färg raderar Bild-inställningar.
- Reglage skriver nyckelrutor i det tysta: dockans Volym och mixerns reglage med AUTO.
- Duckning ersätter handgjorda nycklar.
- Appvida inställningar ligger i projektdialogen (Rendera automatiskt, Ta bort renderingar för alla projekt) och på
  färgsidan ("Original").
- Projektinställningar nås via tidslinjens ⋯.
- Ljudklipp och videoklipp saknar fliken Övertoning (bara handtag).
- Ripple-radera för ett klipp finns bara som gest.
- Verktygsvalet ändrar vad en dragning gör och sparas inte.

---

## 4. Viktigaste problemen

1. **Text-läget dubblerar Lägg till och är enda vägen till sekvensens transkription.** Två av tre flikar är samma
   komponenter som Lägg till › Titel och Lägg till › Undertexter, och läget saknar tidslinje. Skapad titel hoppar
   tillbaka till Redigera.
2. **Undertexter har två namn, två textredigerare och två stilmål**, och stilens omfattning syns inte.
3. **Transkription heter sex olika saker.** Att ta bort ord visar inte vilka klipp, spår eller undertexter som
   påverkas, och undertexter behåller borttagna ord.
4. **Tillbaka lämnar appen** från undertextinspektören, dockans underrader (Hastighet, Volym, Etikett, Frys, Mer),
   klippkantskortet, övergångskortet och en markering. Färgsidans rutnät stängs inte först.
5. **Omfattning som inte syns:** färgåterställning (raderar Bild), "Alla klipp", stil per spår, appvida rader i
   projektinställningarna, reglage som skriver nyckelrutor.
6. **Ord som betyder flera saker:** "Text", "Ljud", "Kopiera", "Redigera" (se avsnitt 3).

Fel som hittades under inventeringen och bör rättas oavsett meny (egna commits, inte UI-omstrukturering):
- **F1.** Färg › Klippets färg › Återställ raderar nyckling, mask, blandning, beskärning, stabilisering och
  rörelseoskärpa (CP:767). ✓
- **F2.** Tillbaka lämnar appen med undertextinspektören eller en dockunderrad öppen (App.kt:659). ✓
- **F3.** Listans undertextredigerare tappar text som inte sparats (TitleUi.kt:549-564). ✓
- **F4.** Media-arkets namnkort ligger kvar efter Tillbaka (MediaLibrary.kt:254).
- **F5.** "Tidigare versioner" säger att versionen öppnas, men den skapas bara (PC:1155).
- **F6.** En titel som motorn lägger på V4 syns inte i tidslinjen (bara V1–V3 visas). Detta är inte provat på telefon.

---

## 5. Alternativ A – små förändringar

Strukturen behålls med fyra lägen. Det som ändras:
- **Namn**, enligt ordlistan i 6.1: "Textning" blir "Undertexter", transkription får ett enda ord, "Kopiera" blir
  "Visa i källan", "Ljud" i Lägg till blir "Ljudbibliotek", och "Ta bort" används överallt.
- **Undertexter:** listraden öppnar inspektören, och inline-redigeraren tas bort. Stilknappen visar omfattningen
  ("Stil · alla undertexter i "Automatiska undertexter"").
- **Tillbaka** stänger det innersta: underrad, kort, inspektör, rutnät och markering.
- **Felen F1–F5** rättas.
- **Text-lägets transkriptionsflik** öppnar arket direkt, utan kort emellan.
- **Appvida rader flyttas** ur projektinställningarna till Inställningar › Uppspelning.

Fördel: liten risk, och inga test-ID:n för lägen ändras. Nackdel: Text-läget finns kvar som en dubblett utan
tidslinje, och transkriptionen ligger fortfarande långt från klippningen.

---

## 6. Alternativ B (rekommenderat) – tre lägen och en sammanhängande Redigera

Lägena blir **Redigera · Färg · Ljud**. Text-lägets tre flikar får tydligare hem i Redigera:
- **Titlar** är redigerbara objekt: de skapas via Lägg till och redigeras genom att markera dem.
- **Undertexter** får en egen översikt som öppnas via Lägg till och från undertextbanan.
- **Transkription** blir en alternativ vy av tidslinjen, med samma bild och samma spelhuvud.

**Motivering mot att behålla Text-läget:** inventeringen visar att läget inte tillför något eget arbetsflöde.
- Två av tre flikar är identiska med Lägg till-arken.
- Den tredje är en ingång till ett ark.
- Läget döljer tidslinjen, så att skapa en titel slutar med ett hopp tillbaka till Redigera.
- Transkriptionsklippning ändrar tidslinjen och hör därför till Redigera.

Ingen funktion tas bort. Det som försvinner är en extra väg till samma saker.

### 6.1 Ordlista (EN / SV), en betydelse per ord

| Begrepp | EN | SV | Ersätter |
|---|---|---|---|
| Titel (textgrafik) | Title | Titel | – |
| Undertexter (tidsatt text till tal) | Captions | Undertexter | "Textning" |
| Transkription (talet som text, för att söka/välja/klippa) | Transcript | Transkription | "Klipp med text", "utskrift", "Skriv ut", "transkript", "Text" (källknappen) |
| Transkribera (köra taligenkänning) | Transcribe | Transkribera | "Skriv ut talet" |
| Ljudbibliotek (Lägg till) | Sound library | Ljudbibliotek | "Ljud" i Lägg till |
| Ljudeffekter på klippet | Audio effects | Ljudeffekter | dockans "Effekter" |
| Text till tal | Text to speech | Text till tal | fliken "Tal" |
| Berättarröst | Voice-over | Berättarröst | "Röst" |
| Visa i källan | Open in Source | Visa i källan | "Kopiera" (klippdockan) |
| Ta bort / Ripple-ta bort | Delete / Ripple delete | Ta bort / Ta bort och stäng luckan | "Radera", "Rippla" (åtgärden) |
| Klippvolym / Spårvolym / Mix | Clip volume / Track volume / Mix | Klippvolym / Spårvolym / Mix | "Volym" utan omfattning |
| Klippets färg | Clip grade | Klippets färg | – |
| Tidslinjens look | Timeline look | Tidslinjens look | fliken "Tidslinje" på färgsidan |
| Spelhuvudet i mitten | Centered playhead | Spelhuvudet i mitten | "Låst spelhuvud" |
| Slå på / Stäng av klipp | Enable / Disable | Slå på / Stäng av | "Av/på" |
| Projektpaket | Project package | Projektpaket | "Exportera med media till fil…" |
| Exportera film | Export video | Exportera film | toppfältets "Exportera" (samma funktion) |

### 6.2 Menyträd

```
Toppfält:  [Projekt]  [Redigera | Färg | Ljud]  [Exportera]

Redigera
├─ Verktygsrad: [Tidslinje | Transkription]  [Verktyg ▾] [Magnet] [Länk] [Markör] [⋯]
│     ⋯: Spelhuvudet i mitten · Volymlinjer · Ta bort alla markörer · Undertexter… · Projektinställningar · Appinställningar
├─ Tidslinje (som i dag) eller Transkription · sekvens (se 6.5)
└─ Docka
   ├─ inget markerat = Lägg till (6.3)
   ├─ klipp/flera/övergång/undertext = som i dag, med nya namn
   └─ titel: Ta bort · Redigera titel · Dela · Etikett · Stäng av

Lägg till (dockan, inget markerat)
├─ Media ……… Media-arket (importera video → poolen → källan → Infoga/Skriv över)
├─ Ljud ………… Ljudbibliotek-arket: Paket · Freesound · Text till tal · Filer
├─ Titel ……… Titel-arket (mall, entré, text → Lägg till vid spelhuvudet)
├─ Undertexter … Undertexter-arket (6.4)
└─ Berättarröst … inspelningsarket

Färg (som i dag) – rutnätets "Klippets färg": Kopiera · Klistra in · Alla videoklipp (V1–V3) · Återställ färg…
      (en enda kopiera/klistra in-funktion; hjulens knapp och klippremsans långtryck anropar den)
Ljud (som i dag) – rubriker: Spårvolym · Mix; en rad: "Klippvolym ställs på klippet i Redigera"
Inställningar – Uppspelning samlar proxy, nodcache, renderingar (+ Rendera automatiskt), lagring
Projekt – projektmenyn: … · Säkerhetskopiera nu · Spara projektpaket (med media)… · Spara projektfilen… · Tidigare versioner…
```

### 6.3 Lägg till

Dagens docka har redan rätt innehåll. Det som ändras:
- **Namn:** "Textning" blir "Undertexter" och "Röst" blir "Berättarröst". "Ljud" står kvar som knapp men öppnar arket
  "Ljudbibliotek".
- **Ordning:** Media · Ljud · Titel · Undertexter · Berättarröst, i grupperna Media · Text · Inspelning.

Inga nya grupper eller funktioner tillkommer. Bildimport finns inte (väljaren tar bara video), så knappen heter
"Media" och inte "Video och bilder". Transkription finns inte i Lägg till, eftersom den är en vy av befintligt
material.

```
┌──────────────────────────────────────────────┐
│  ▢ Media   ♪ Ljud │ T Titel  ≡ Undertexter │ 🎙 Berättarröst │   ← dockan, inget markerat
└──────────────────────────────────────────────┘
```

### 6.4 Undertexter: en översikt, en redigerare och synlig omfattning

```
┌ Undertexter ──────────────────────────── Klar ┐
│ Skapa                                          │
│  [Skapa från tal]  [Ny vid spelhuvudet]  [Importera SRT/VTT] │
│ ─ Spår: Automatiska undertexter ▾ (visas)  ── │   ← spårets namn syns; bara om fler än ett: välj
│ [Stil – alla undertexter i spåret]            │   ← omfattningen står på knappen
│ 00:00:01:10  Hej och välkommen …          ›   │   ← raden öppnar samma inspektör som tidslinjen
│ 00:00:03:02  I dag ska vi …               ›   │
└────────────────────────────────────────────────┘

Inspektör (markerad undertext)
┌ Undertext 12 · Automatiska undertexter ─ Klar ┐
│ Text   [ Hej och välkommen …              ]   │   ← sparar själv (som i dag)
│ Tid    00:00:01:10 – 00:00:03:02             │
│        [Start vid spelhuvudet] [Slut vid spelhuvudet] [◀ ruta ▶] │   ← knappar till befintliga captions.trim/move
│ Stil – alla undertexter i spåret  ›          │
└────────────────────────────────────────────────┘
```

- **"Skapa från tal"** är samma kort som i dag. En varning visas innan det befintliga automatiska spåret ersätts:
  "Ersätter N undertexter i "Automatiska undertexter", även ändringar du gjort".
- **Stilen** gäller per spår, som motorn redan har den. Inga lokala stilundantag läggs till. Omfattningen står
  alltid i rubriken.
- **Tidsknapparna** anropar de befintliga `captions.trim` och `captions.move`. Det är ett knappalternativ till
  dragningen i banan, inte en ny funktion.

### 6.5 Transkription: en vy i Redigera

```
[ Tidslinje | Transkription ]                   ← vyväxlare i verktygsraden; bild och spelhuvud kvar
┌ Transkription · sekvens ── Språk ▾  Transkribera ┐
│ Hej och välkommen. I dag ska vi [titta på hur] │   ← tryck = spelhuvudet dit; tryck igen = område
│ man klipper …                                   │
├─────────────────────────────────────────────────┤
│ Markerat: 1,8 s · påverkar V1, A1 (2 klipp)     │   ← räknas från ordens tider och målspåren
│ Undertexter i området kortas men behåller orden │
│ [Ta bort och stäng luckan]  [Avmarkera]         │
│ [Ta bort pauser] [Ta bort utfyllnadsord] [Skapa undertexter ›] │
└─────────────────────────────────────────────────┘
```

- **Komponenter:** samma ordlista och samma motorkommandon som dagens ark (`transcript.inspect`,
  `transcript.extract`, `transcript.removePauses`, `transcript.removeFillers`).
- **Påverkansraden:** beräknas i appen från ordens tidslinjetider och de målmarkerade spåren, utan något nytt
  motorkommando.
- **"Skapa undertexter"** öppnar Undertexter-arket på "Skapa från tal", som återanvänder transkripten.
- **Transkribera:** knappen visas även när sekvensen är delvis transkriberad. Igenkänningskortet blir en gemensam
  komponent för alla tre ingångarna.
- **Källmonitorn:** knappen heter "Transkription" och vyn har rubriken "Transkription · källklipp". Beteendet är
  oförändrat (meningar sätter in/ut).

### 6.6 Titelns egenskaper: Innehåll · Stil · Placering · Animation

| Ny grupp | Innehåll (från dagens flikar) |
|---|---|
| Innehåll | Texten (Text) |
| Stil | Mall, typsnitt, storlek, färg, fet/kursiv/versaler, spärrning, radavstånd, justering, **textens opacitet**, bakgrundsplatta, kontur, skugga (Text + Stil) |
| Placering | X, Y, skala, rotation, **klippets opacitet** (nyckelrutor) (Rörelse) · Följ objekt · Fäst i hörn (Följ) |
| Animation | Entré (Animera) · Tona in/ut (Övertoning) |

Samma redigerare och samma lagring. Bara grupperingen och de två opacitetsnamnen ändras.

### 6.7 Tillbaka (konsekvent ordning, innerst först)

1. Helskärm.
2. Inställningssida → inställningslistan → stäng.
3. Projektsök → projektsidan.
4. Dialog eller ark (systemet).
5. Fästhörn och rutval (som i dag).
6. Dockans underrad (Hastighet, Volym, Etikett, Frys, Mer).
7. Undertextinspektören.
8. Egenskaper.
9. Klippkants- och övergångskortet.
10. Färgsidans rutnät.
11. Media-arket och dess namnkort.
12. Källan.
13. Transkription → tidslinje.
14. Markering → avmarkera.
15. Annan sida → Redigera.
16. Lämna appen.

Osparad text:
- Undertexttext sparas som i dag (fördröjning och vid stängning).
- Titelutkastet i Titel-arket sparas i redigeringsläget, så att det finns kvar om arket stängs av misstag.
- Listans inline-redigerare försvinner.

Ordningen räknas fram av en ren funktion (`backTarget(state)`) som enhetstestas.

### 6.8 Färg, ljud och inställningar

- **Färg:**
  - Kopiera och klistra in färg samlas i en funktion; Klistra in går att använda flera gånger.
  - "Alla klipp" blir "Alla videoklipp (V1–V3)".
  - "Återställ färg" behåller Bild-inställningarna (F1) och visar "Återställer klippets färg (noderna)".
  - "Original" heter "Spela originalen (inte proxy) – gäller appen" och har samma polaritet som Inställningar.
- **Ljud:**
  - Dockans "Volym" blir "Klippvolym". Med nyckelrutor står det "Klippvolym · ställer nyckelrutan vid
    spelhuvudet".
  - Mixerns rubriker blir "Spårvolym" och "Mix". AUTO förklaras: "skriver nyckelruta vid spelhuvudet".
  - Duckning varnar innan befintliga nivånycklar ersätts.
  - "Effekter" blir "Ljudeffekter".
  - Fliken Övertoning, samma `setTransition`, visas även för video- och ljudklipp.
- **Inställningar:** Uppspelning samlar proxy, nodcache, Rendera automatiskt, Ta bort renderingar (alla projekt) och
  lagring. Projektinställningarna behåller bara projektets egna rader och "Rendera tidslinjen".
- **Rendering:** chippet visar varför ("3 delar behöver renderas för jämn uppspelning"). Funktionen är oförändrad.

---

## 7. Konkreta uppgifter – nu och föreslaget (B)

Tryck räknas från Redigera-sidan med inget markerat. Väljare och skrivande räknas som ett steg.

| # | Uppgift | Nu | Föreslaget | Bedömning |
|---|---|---|---|---|
| 1 | Importera och placera ett klipp | Media → (väljaren första gången) → kort → [in/ut] → Infoga (≈5) | samma (≈5); källhuvudet säger "Källa · namn" | oförändrat; flödet är redan bra |
| 2 | Namnskylt + utseende | Titel → Namnskylt → skriv → Lägg till → Egenskaper öppnas → Stil (≈5); alternativt via Text-läget | Titel → Namnskylt → skriv → Lägg till → Stil (≈5) | samma tryck; en väg i stället för två; Stil samlar allt utseende |
| 3 | Undertexter + stil på alla | "Textning" → kortet: språk/kvalitet/spår → Generera → "Typsnitt & stil" (≈5) – vilket spår som får stilen syns inte | Undertexter → Skapa från tal → Generera → "Stil – alla undertexter i spåret" (≈4) | ett namn; omfattningen syns; varning innan ersättning |
| 4 | Rätta en undertext och dess tid | Tryck i TXT → Redigera → text; tid **bara** genom att dra kanterna; listans redigerare kräver Spara | Tryck i TXT eller listrad → samma inspektör: text + "Start/Slut vid spelhuvudet" (≈3) | en redigerare; knappalternativ till gesten; inget textbortfall |
| 5 | Välja meningar ur källmaterial | Media → kort → "Text" → (transkribera) → meningar → Infoga (≈5) | samma, knappen heter "Transkription" | oförändrat, tydligare namn och kontext ("· källklipp") |
| 6 | Klippa bort en mening ur sekvensen | Text-läget → Klipp med text → Öppna utskriften → ord → Radera N ord (≈5); ingen förhandsvisning av vad som påverkas | [Transkription] → ord → läs påverkansraden → Ta bort och stäng luckan (≈3) | kortare, tidslinjen nära, påverkan syns före |
| 7 | Kopiera färg mellan klipp | Färg → klipp A → rutnät → Klippets färg → Kopiera → klipp B → Klistra in (≈6); eller hjulknappen (en gång per klipp) | samma eller klippremsan: långtryck A → Kopiera färg, långtryck B → Klistra in (≈4); en funktion | samma regler överallt; Återställ förstör inte Bild |
| 8 | Klippvolym resp. spårvolym | Klipp: markera ljudklipp → Volym; spår: Ljud → Mixer; båda heter "Volym" | samma vägar, "Klippvolym" / "Spårvolym A1"; Ljud-sidan säger var klippvolymen finns | begripligt vilket som ändras |
| 9 | Förstå och starta förrendering | Chippet "Rendera" dyker upp; ⋯ → Projektinställningar → Rendera tidslinjen | chippet säger varför; samma tryck | begripligt; appvida rader på ett ställe |
| 10 | Säkerhetskopiera och exportera film | Projekt → projektmeny → Säkerhetskopiera nu; Exportera → Exportera | samma; menyn skiljer "Projektpaket" från "Exportera film" | mindre risk att blanda ihop paket och film |

---

## 8. Flyttabell (nuvarande plats → ny plats)

| Nuvarande | Ny plats (B) |
|---|---|
| Läget Text (`modeTab:3`) | tas bort, innehållet nedan |
| Text › Titlar | Lägg till › Titel (samma `TitlesTab`) |
| Text › Undertexter | Lägg till › Undertexter, samt ⋯ › Undertexter… (samma ark) |
| Text › Klipp med text → kort → "Öppna utskriften" → ark | Redigera › [Tidslinje \| Transkription] |
| Lägg till "Textning" | Lägg till "Undertexter" |
| Lägg till "Röst" | Lägg till "Berättarröst" |
| Lägg till "Ljud" (arket "Ljud") | Lägg till "Ljud" → arket "Ljudbibliotek" (fliken "Tal" → "Text till tal") |
| Undertextlistans inline-redigerare | listraden öppnar undertextinspektören |
| Arkets "Typsnitt & stil" (första spåret) | "Stil – alla undertexter i spåret" (valt/namngivet spår) |
| Källans "Text"-knapp | "Transkription" |
| Klippdockan "Kopiera" | "Visa i källan" |
| Klippdockan "Volym" / "Effekter" | "Klippvolym" / "Ljudeffekter" |
| Färg: hjulens Kopiera/Klistra in + rutnätets Kopiera/Klistra in | samma platser + klippremsans långtryck, en gemensam funktion |
| Färg › Tidslinje › "Original" | Inställningar › Uppspelning (kvar som genväg med text "gäller appen") |
| Projektinställningar: Rendera automatiskt, Ta bort renderingar | Inställningar › Uppspelning |
| Projektmenyn "Exportera med media till fil…" / "Exportera bara projektfilen…" | "Spara projektpaket (med media)…" / "Spara projektfilen…" |
| ⋯ "Låst spelhuvud" | ⋯ "Spelhuvudet i mitten" |
| Egenskaper titel: Text · Stil · Animera · Följ + Rörelse · Övertoning | Innehåll · Stil · Placering · Animation |

---

## 9. Behålls oförändrat

- **Motorn och bilden:** motorns redigeringssemantik, färgmatematiken, filmkedjan, bildvalsregeln, uppspelningen,
  exportens livscykel och projektformatet. Inga nya data sparas, utom titelutkastet som bara ligger i minnet.
- **Redigera:** dockans mönster (innehållet följer markeringen), tidslinjens gester och verktyg, transporten,
  källmonitorns flöde (Infoga/Skriv över, målspår) och Lägg till-arkens innehåll.
- **Färg och ljud:** färgsidan med noder, verktyg och rutnät, samt Ljud-sidans Mixer, Ljudstyrka och Duckning.
- **Projekt och export:** projektsidan och exportdialogen.
- **Övrigt:** stabila test-ID:n där betydelsen är oförändrad (`add:*`, `ctx:*`, `cap:*`, `tx:*`, `properties`,
  `titleTab:*` med nya etiketter).

---

## 10. Implementeringssteg (B) – små, fungerande commits

0. **Buggrättningar först**, var och en med test: F1 Återställ färg, F2 Tillbaka, F3 listans textbortfall,
   F4 namnkortet, F5 versionstexten.
1. **Namn och omfattningstexter** (EN + SV samtidigt) enligt 6.1, utan strukturändring.
2. **Tillbaka:** `backTarget(state)` och en enda hanterare, plus titelutkastet i redigeringsläget.
3. **Undertexter:** listraden öppnar inspektören, stilens omfattning och spårnamn syns, tidsknappar, varning innan
   ersättning, gemensamt igenkänningskort.
4. **Titelegenskaperna:** Innehåll · Stil · Placering · Animation, och opacitetsnamnen.
5. **Transkription:** vyväxlaren i Redigera, påverkansraden, genvägen till undertexter, "Transkribera" även vid
   delvis transkriberad sekvens och källans namn.
6. **Text-läget tas bort:** tre lägen, hjälp och tips uppdateras, ⋯ › Undertexter….
7. **Färg, ljud och inställningar:** gemensam kopiera/klistra in, klippremsans långtryck, volymnamn, övertoning för
   video- och ljudklipp, och flytt av renderingsraderna.
8. **Rensning:** ersatta ingångar och döda prototypvägar, där de inte används av testerna.

## 11. Testplan

- **Enhetstester:**
  - `backTarget` för varje rad i 6.7;
  - påverkansraden (ord → tider → spår och klipp);
  - omfattningstexter;
  - F1 (Återställ behåller `layer`, `stab` och `mblur`).
- **Compose-tester (Robolectric, som `ShellTest`, `BackTest` och `KlippUiTest`):**
  - uppgifterna 2, 3, 4, 6 och 7 genomspelade;
  - Tillbaka med osparad undertext och titelutkast;
  - Lägg till skiljer Titel från Undertexter;
  - Transkription visar "· sekvens" och "· källklipp";
  - smal skärm (360 dp) och stor text (1,3×).
- **Tester som avsiktligt ändras:** `ShellTest` (`textTab:*`, dockan på sida 3), `KlippUiTest` (`modeTab:3`),
  `PropertiesUiTest` (`textTab:1`), `DesignRowsTest` och `ModeSwipeTest` (tre lägen). Ändringarna dokumenteras i
  commitarna.
- **MCP-skript:** `tools/regress` använder inte `modeTab:3` eller `textTab:*` (kontrollerat med grep). Nya och ändrade
  test-ID:n listas i rapporten. Dubbletten av `appSettings` (projektsidan över redigeraren) noteras.
- **Regression:** hela enhetssviten och R1–R6 på OnePlus i testpaketet. Bild, ljud och export ska vara identiska,
  eftersom UI-ändringen inte rör kedjan.
- **Telefonprov:** uppgifterna 1–10 spelas igenom i testpaketet med egna testprojekt. Utan telefon redovisas de som
  **ej verifierade**.

## 12. Frågor till dig före implementation

1. **Alternativ B (tre lägen) eller A (fyra lägen, små ändringar)?**
2. Ska buggrättningarna F1–F5 göras först även om du väljer A?
3. Ordlistan i 6.1: är "Transkription", "Undertexter", "Visa i källan", "Ljudbibliotek" och "Ta bort" de ord du vill
   ha?
4. Tidsknapparna i undertextinspektören ("Start/Slut vid spelhuvudet", ruta för ruta) är knappalternativ till en
   befintlig gest. Är de inom ramen?

---

# Etapp 2 – genomförd (2026-11-17)

Godkänt: alternativ B. Felen F1–F5 rättades först i ett eget rättningsbygge. Ordlistan med preciseringar och
knapparna för undertextens start och slut ingick. Inga ändringar i motorns redigeringssemantik, färgmatematiken,
filmkedjan, bildvalsregeln, uppspelningen, exportens livscykel eller projektformatet. All nytt tillstånd lever bara i
UI:t: vy, dockrad, valt undertextspår och färgrutnätet.

Gren: `ui/menu-architecture`. Testbyggen (testpaketet, release-nyckeln):
- **172-fix** (`f47ad64`): F1–F5, ingen ny navigering.
- **175-ui** (`dbfd579`): alternativ B (173-ui och 174-ui var mellanbyggen; deras fel är rättade, se nedan).

Den frysta betan och beta.2-kandidaten är orörda. Inget är pushat, taggat eller publicerat.

## 1. Före och efter

```
FÖRE                                              EFTER
[Projekt] [Redigera|Färg|Ljud|Text] [Exportera]   [Projekt] [Redigera|Färg|Ljud] [Exportera film]
Redigera, inget markerat:                         Redigera, inget markerat (Lägg till):
  Media · Ljud · Titel · Textning · Röst            Media · Ljudbibliotek · Titel · Undertexter · Spela in
Text-läget: Titlar | Undertexter | Klipp med text  (borttaget – allt har en ingång i Redigera)
  Klipp med text → kort → "Öppna utskriften" → ark Redigera: [Tidslinje | Transkription]-knappen i raden
                                                    → "Transkription · sekvens" i tidslinjens plats
Undertexter: två redigerare, stil på "första"     En översikt (Lägg till › Undertexter, ⋯ › Undertexter…)
  spåret, tid bara genom att dra                    och en redigerare: spårets namn, start/slut,
                                                    "Sätt start/slut vid spelhuvudet", "Stil – alla
                                                    undertexter i ”spår”" (spåret väljs om flera)
Titel: Text · Stil · Animera · Följ · Rörelse ·    Titel: Innehåll · Stil · Placering · Animation
  Övertoning; två "Opacitet"                        ("Textens opacitet" / "Klippets opacitet")
Tillbaka: lämnade appen från dockrader,            Tillbaka: ett lager per tryck, innerst först;
  undertextredigeraren, markering                   appen lämnas först när inget är öppet
Färg › Återställ: raderade Bild-inställningar      Färg › Återställ: bara färgsidans noder
Projektinställningar: appvida renderingsval        Inställningar › Uppspelning: "Rendera automatiskt
                                                    (alla projekt)", "Ta bort renderingar (alla projekt)"
```

## 2. Commits

| Commit | Innehåll |
|---|---|
| `3998b48` | F1 Färg › Återställ: bara noderna (`ClipGrade.colorReset`) – nyckling, mask, blandning, beskärning med nyckelrutor, stabilisering, rörelseoskärpa kvar; ett ångrasteg |
| `425b9f6` | F2 Tillbaka: `backLayer()` (Back.kt), dockrad och färgrutnät i tillståndet, export oförändrad |
| `9bdffe2` | F4 Media-arkets namnkort: Tillbaka stänger kortet först, kortet stängs med arket |
| `2f6c977` | F3 En undertextredigerare (`CaptionTextField`): sparar själv, ett ångrasteg per redigeringstillfälle, tom text sparas inte |
| `c5d7abd` | F5 Tidigare versioner: "Återställ som nytt projekt", meddelandet namnger projektet |
| `f47ad64` | Testbygge 172-fix |
| `f423873` | Steg 1 Namn (EN+SV) enligt ordlistan |
| `7c84881` | Steg 2 Undertexter: översikt, en redigerare, spårets namn och val, start/slut vid spelhuvudet, varning innan automatiska ersätts |
| `58168ac` | Steg 3 Transkription som Redigeras andra vy; påverkansraden före borttagning |
| `aee6e33` | Steg 4 Titelns fyra grupper |
| `15ee478` | Steg 5 Omfattning: renderingsförval, proxy "alla projekt", klippvolym med nyckelruta, mixerns reglage |
| `7bf01c3` | Steg 6 Tre lägen (Text-läget borttaget efter att allt fått nya ingångar) |
| `394ce7f` | Steg 7 En kopiera/klistra in för färg |
| `6ca6553` | Steg 8 Övertoning som knappar för ljud och i videons Bild-ark |
| `4a237a1` | Steg 9 Större text: dockans namn bryts vid mjukt bindestreck, "Spela in", rubriken utan dubblering |
| `7eaaed6`, `ee64b72` | Transkriptionsvyn slutar ovanför dockan (hittat vid telefonprovet) |
| `b1ab70b`, `6a78fa0`, `dbfd579` | Testbyggen 173-ui, 174-ui, 175-ui |

Ändrade filer i appen: Back.kt (ny), CaptionEdits.kt (ny), TranscriptCut.kt (ny), CaptionUi.kt, ColorPage.kt,
ColorUi.kt (`PillButton(enabled)`), McpBridge.kt, MediaLibrary.kt, Model.kt, Panels.kt, ProjectsScreen.kt,
Properties.kt, Settings.kt, Shell.kt, SourceTextUi.kt, TitleUi.kt, TranscriptUi.kt, engine/Grade.kt,
engine/ProjectController.kt, strängar i `values` och `values-sv`.

## 3. Var varje tidigare funktion finns nu

| Tidigare | Nu |
|---|---|
| Text-läget › Titlar | Lägg till › Titel (samma `TitlesTab`; listan "Titlar på tidslinjen" finns kvar där) |
| Text-läget › Undertexter | Lägg till › Undertexter; ⋯ › Undertexter… (när något är markerat) |
| Text-läget › Klipp med text › Öppna utskriften | Redigera › transkriptionsknappen i tidslinjens rad |
| Lägg till "Textning" / "Röst" / "Ljud" | "Undertexter" / "Spela in" (läses upp som "Berättarröst") / "Ljudbibliotek" |
| Undertextlistans redigerare (Spara) | listraden öppnar undertextredigeraren (sparar själv) |
| "Typsnitt & stil" (första spåret) | "Stil – alla undertexter i ”spår”" (valt spår) |
| Undertextens tid (bara dra) | dra, eller "Sätt start/slut vid spelhuvudet" i redigeraren |
| Källans "Text" | "Transkription" (rubrik "Transkription · källklipp") |
| Klippdockan "Kopiera" / "Volym" / "Effekter" | "Visa i källan" / "Klippvolym" / "Ljudeffekter" |
| Titel: Text, Stil, Animera, Följ, Rörelse, Övertoning | Innehåll, Stil, Placering (rörelse + följ/fäst), Animation (entré + toning) |
| Projektinställningar: Rendera automatiskt, Ta bort renderingar | Inställningar › Uppspelning (båda "alla projekt") |
| Färg › Tidslinje › "Original" | samma plats: "Spela original, inte proxy (alla projekt)" |
| Färg: hjulens kopiera/klistra in, rutnätets, stillbildens | samma platser, en gemensam regel |
| Toning för ljud/video: bara hörnhandtaget | hörnhandtaget, eller fliken Övertoning |
| Projektmenyn "Exportera med media till en fil…" / "Exportera bara projektfilen…" | "Spara projektpaket med media…" / "Spara bara projektfilen…" |
| ⋯ "Låst spelhuvud" | ⋯ "Spelhuvudet i mitten" |

Test-ID:n som ändrats (för MCP-skript):
- **Borttagna:** `modeTab:3`, `textTab:*`, `titleTab:2`, `titleTab:3` samt titlarnas `propTab:motion` och `propTab:fade`.
- **Nya:** `view:transcript`, `view:timeline`, `view:title`, `transcriptView`, `tx:cut*`, `tx:captions`,
  `tx:deselect`, `menu:captions`, `capTrack:<id>`, `capTrackChoose`, `capStyle`, `caption:row:<id>`, `captionTimes`,
  `captionSetStart`, `captionSetEnd`, `captionEdgeWhy`, `captionBlank`, `titleTab:place`, `titleTab:anim`,
  `set:renderAuto`, `mixer:scope`, `mixer:auto`, `sourceText:title`.
- **Fortfarande giltiga:** `titleTab:0` (Innehåll) och `titleTab:1` (Stil).
- **Bryggan:** `set_mode` tar bara emot edit, color och audio. `tools/regress` använder bara `set_mode edit` och
  läget som var öppet, så skripten behövde inte ändras.

## 4. Tester

**Enhetssviten** (`testDebugUnitTest`, på `0dcdaf5`): 708 tester, 707 godkända, 0 misslyckade, 1 överhoppat
(`FilmCpuRenderTest.renderCases`, sedan tidigare).

Nya testklasser:
- ColorResetTest, ColorCopyTest;
- BackLayerTest, MediaSheetBackTest;
- CaptionEditingTest, CaptionEditsTest;
- TranscriptCutTest, TranscriptViewTest;
- ScopeLabelsTest, LargeTextTest.

Avsiktligt ändrade testklasser: BackTest, PropertiesUiTest, ProjectsTest, KlippUiTest, ShellTest, DesignRowsTest och
ModeSwipeTest.

**Regression på OnePlus:**
- 172-fix, R1, R3–R6: 43/43.
- 175-ui, R1, R3–R6: 43/43 (export, förrenderat, pausbild, timing, upprepning, övergångar). UI-omstruktureringen ändrade inte bild, ljud eller export.

**Telefonprov på OnePlus** (testpaketet, egna testprojekt):

| Prov | Resultat |
|---|---|
| F1 | Återställ ger 1 nod och mättnad 1,0; Bild-inställningarna inklusive maskens nyckelrutor finns kvar; ett Ångra återställer allt |
| F2, F4 | Ett lager per tryck: färgrutnät, färgsida, hastighets-/Mer-/frysrad, markering, undertextredigerare, undertext, namnkort, Media-ark |
| F3 | Text skriven i två omgångar med paus autosparas; ett Ångra ger originalet; Gör om ger allt |
| F5 | Dialogens text och raden "Restore as a new project" |
| Uppgift 1 | Lägg till › Titel › Namnskylt; egenskaperna öppnas med de fyra grupperna; fet stil på Stil, markeringen kvar; Tillbaka stänger, sedan avmarkera |
| Uppgift 2 | Två undertextspår: översikten frågar efter spår och visar ingen stilknapp förrän ett spår är valt; "Style – all captions in ”Rättat”"; ruta "None" ändrade bara det spåret |
| Uppgift 3 | Listraden öppnar redigeraren med spårets namn; "Set end at the playhead" 2,48 → 1,6 s; Ångra återställer tiden; text i två omgångar ångras i ett steg |
| Uppgift 5 | Alla vyer ovan plus transkriptionsvyn (markering och spelhuvud kvar), titel-, undertext-, ljudbiblioteks- och inspelningsarket, egenskaperna; appen lämnas först när inget är öppet |

## 5. Oprövat och kända problem

- **Uppgift 4 är inte verifierad på telefon.** Telefonen hade ingen internetanslutning, så talmodellen (59 MB) kunde
  inte laddas ned. Påverkansraden och vyväxlingen är enhetstestade (TranscriptCutTest, TranscriptViewTest). På
  telefonen är bara vyväxlingen och Tillbaka provade.
- **F6 är bekräftat men inte rättat (fanns före UI-ändringen):** när V1–V3 redan är upptagna vid spelhuvudet lägger
  motorn en ny titel på V4 eller högre. Tidslinjen visar bara V1–V3, så titeln syns inte, och vid nästa ändring släpps
  markeringen. Detta kräver ett separat beslut om tidslinjen eller titelns placering.
- **Bryggans `tap`** når inte ark och dialoger i egna fönster (ModalBottomSheet, AlertDialog, DropdownMenu).
  Telefonskripten använder därför riktiga tryck via `adb input tap` på koordinater från `ui_nodes`.
- **Inte gjort:** klippremsans långtryck för att kopiera eller klistra in färg (förslagets extra genväg). Funktionen
  finns på hjulen och i rutnätet.
- **Inte provat:** export efter UI-ändringen. UI-ändringen rör inte exportkedjan, och regressionen exporterar varje
  fall.
- **Inte provat:** landskapsläge med den nya verktygsraden.
- **Mellanbyggen:** 173-ui hade transkriptionsvyns nedladdningsknapp under dockan (rättat i `7eaaed6` och `ee64b72`).

## 6. Tre uppgifter för externa testare (utan hjälp)

1. Lägg en namnskylt med ditt namn i början av filmen och gör texten fet.
2. Skapa en undertext där någon pratar, rätta stavningen och låt den sluta exakt där meningen tar slut.
3. Ta bort en mening ur filmen med hjälp av texten, och säg innan du gör det vilka klipp som kommer att kortas.
