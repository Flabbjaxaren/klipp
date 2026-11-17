# Kornets tidskontrakt (reviderat förslag)

Status: **förslag, inte implementerat, separat från N1.** Bygger på beslutet 2026-11-17: levande korn även när bilden
står still; paus på samma projektruta ger alltid exakt samma korn igen.

## Problem (mätt)

`FilmGl` seedar kornet med `presentationTimeUs / 1000` – tiden i den komposition som råkar rendera. Mätt på OnePlus,
bygge 161, samma storlek, samma tidslinjeruta (`grain_seed_check.py`, gren `exp/grain-seed-check`): samma rendering två
gånger 1,000; förrenderade bitar från 1,0 s mot 2,0 s −0,004; exporter från 1,0 s mot 2,0 s +0,003; bit mot export
−0,001/+0,004. CPU-vägen (`FilmFx.Rng`, en ström genom bilden) har en egen generator.

## Kontrakt

**K1. Nyckeln.** Kornet i en bildpunkt är en ren funktion av:

1. **kornidentiteten** `gid` – ett stabilt 64-bitarsvärde för filmbehandlingen (filmnoden i klippet), se K3;
2. **den absoluta projektrutan** – tidslinjetiden avrundad till sekvensens rutnät, som heltal (`round(t · fps)`);
3. **bildpunkt och ström** – pixelns läge i filmens indata vid aktuell renderstorlek, lager och kanal (och ett eget
   salt för glaren).

**K2. Inte en del av nyckeln:** renderintervallets start, lokal presentationstid, renderväg (uppspelning, paus,
sökning, förrenderad bit, nodcache, export), källans tid, klippets id.

**K3. Kornidentiteten bevaras** när synligt innehåll inte ändras:

| Åtgärd | `gid` |
|---|---|
| Trimma början eller slutet | behålls (kvarvarande rutor ligger på samma projektrutor → samma korn) |
| Dela ett klipp | båda delarna behåller originalets `gid` |
| Flytta ett klipp i tiden | behålls – men projektrutorna ändras, så kornet ändras (accepterat, dokumenterat) |
| Ändra filmens inställningar | behålls (kornets mönster följer inte reglagen) |
| Kopiera/klistra in, duplicera | ny `gid` (två kopior på samma tid ska inte ha identiskt korn) |
| Ny filmnod | ny `gid` |
| Projekt utan `gid` (äldre) | härleds en gång deterministiskt ur (klippets id, nodens id) och sparas |

`gid` sparas i filmnodens JSON (som `v` i dag).

**K4. Samma ruta, samma storlek, samma korn – bit för bit.** CPU och GPU använder samma hash (PCG-kedjan porteras
till `FilmFx`). I olika storlekar är mönstret olika men styrkan densamma (N1).

**K5. Nästa projektruta har nytt korn** – även när bilden står still (frysning, upprepade källrutor i slowmotion,
stillbilder): korrelation ≈ 0 mellan grannrutor.

## Renderingsvägarna

| Väg | Projektruta i dag | Ändring |
|---|---|---|
| Export | tidslinjen från 0 → `pts` = projekttid | ingen (pts → ruta) |
| Uppspelning och paus | kompositionen från `plan.origin` | förskjutningen in i filmeffekten |
| Förrenderad bit | intervall från `a − förrullning` | intervallets start in i filmeffekten |
| Nodcache | källklipp, tid från källans början | se nedan |
| Färgsidans stillbild, CPU-stillbilder | fast frö (0) | projektrutan in |

## Nodcachen – utredning

I dag bakar nodcachen in **hela filmen, kornet inräknat**, i en fil per **källintervall** (nyckel: original,
källintervall + 2 s marginal, nodernas värden – inte placeringen på tidslinjen). Samma fil används oavsett var och hur
många gånger källan används. Med K1 går det inte ihop: kornet beror på projektrutan, och en frysning eller slowmotion
behöver nytt korn per projektruta medan cachen bara har en bild per källruta. Att behålla filen som den är vore att
tvinga fram fryst korn – uteslutet.

Alternativ:

| | Hur | För | Mot |
|---|---|---|---|
| **A. Cachen på projektrutorna** (rekommenderas) | cachen renderas över klippets projektrutor (som en förrenderad bit, en bild per ruta); nyckeln får placering (start, hastighet, källstart) och `gid` | exakt K1–K5; samma kod som förrenderingen | flytt/omtidning renderar om klippets cache (4 s 4K ≈ 25 s på OnePlus); en källa använd två gånger får två filer |
| B. Cachen före kornet | cachepunkten flyttas till före filmnoden; filmen alltid live | cachen oberoende av tid | filmen är den tunga noden – cachens nytta försvinner för film |
| C. Korn utanför cachen | cachen utan korn, korn ovanpå live | billigt | kornet sitter mitt i kedjan (i densitet, före kopieringen) – att lägga det på utdata ändrar utseendet; bryter pariteten med spektrafilm |
| D. Ingen nodcache för film med korn | korn → alltid live | enkelt | tunga filmprojekt spelar sämre; kräver mätning |

Innan implementation:
1. Mät hur ofta och hur länge nodcachen används med film (uthållighetspasset: 2 av 5 klipp i E1).
2. Mät A:s omrenderingskostnad vid flytt och D:s uppspelning (sena rutor) på båda telefonerna.
3. Välj A eller D utifrån det; C uteslutet (utseende), B bara om A och D båda är sämre.

## Acceptanskriterier

1. `grain_seed_check.py` utökad med pausad bild (paus på samma ruta två gånger, efter sökning bort och tillbaka),
   förrenderade bitar med olika start, export och nodcache: alla par för samma projektruta i samma storlek ≥ 0,95;
   grannrutor ≈ 0, även på en frysning och en stillbild.
2. JVM-test: `FilmFx` och GPU-hashen ger samma heltal per pixel; instrumenterat test: CPU-korn = GPU-korn bit för bit
   före kodning.
3. Trimning och delning: samma korn på kvarvarande rutor (test); flytt: nytt korn (test, dokumenterat).
4. Statistiska korntester oförändrat gröna; cacheversioner höjda där innehållet ändras.
5. Inget i betan utan godkännande.
