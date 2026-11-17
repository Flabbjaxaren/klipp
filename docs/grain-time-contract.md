# Förslag: kornets tidskontrakt

Status: **förslag, inte implementerat.** Kornets tidsbeteende ändras inte utan uttryckligt godkännande.

## Problem (mätt)

I dag: `FilmGl` seedar kornet med `presentationTimeUs / 1000` – presentationstiden i den komposition som råkar rendera.
Den börjar om vid varje komposition: exporten (från 0), förrenderade bitar (intervall med förrullning), nodcachen
(ett klippt källklipp). CPU-vägen (`FilmFx.Rng`, en splitmix-ström genom bilden) har en egen generator.

Mätt på OnePlus, bygge 161, samma storlek (720p), samma tidslinjeruta (`tools/regress/grain_seed_check.py`, gren
`exp/grain-seed-check`):

| Par | Korrelation |
|---|---|
| Samma förrenderade bit två gånger | 1,000 |
| Bitar som börjar vid 1,0 s respektive 2,0 s | −0,004 |
| Exporter från 1,0 s respektive 2,0 s | +0,003 |
| Förrenderad bit mot export | −0,001 / +0,004 |

Statistiken är densamma, men mönstret följer renderingen och inte rutan.

## Kontrakt

**K1. Kornet är en ren funktion av bildens ursprung.** För en filmnod på ett klipp bestäms kornet i en bildpunkt av:

1. **källans identitet:** mediaobjektets id (stabilt när klipp delas, flyttas eller trimmas);
2. **källrutan:** källtiden avrundad till källans egen ruta (Klipps `FrameSnap`-tider), som heltal;
3. **noden:** filmnodens id i klippets kedja (två filmnoder får olika korn);
4. **bildpunkten** i filmens indata vid den aktuella renderstorleken, samt lager och kanal.

Kornet beror **inte** på renderväg (uppspelning, paus, sökning, förrenderad bit, nodcache, export), kompositionens
start, presentationstider, tidslinjeposition, klippets id eller hastighet.

**K2. Samma storlek, samma korn.** Två renderingar av samma källruta i samma storlek ger samma korn bit för bit
(CPU och GPU använder samma hash: PCG-kedjan i `FilmGl` porteras till `FilmFx`). I olika storlekar är mönstret
olika men styrkan densamma (N1).

**K3. Nästa ruta har nytt korn.** Rutor med olika källruta är oberoende (korrelation ≈ 0).

**K4. Glaren** följer samma regel (eget frö-salt).

## Följder att ta ställning till

- **Frysta rutor och upprepade rutor** (frysning, slowmotion med upprepade rutor, baklänges): samma källruta → samma
  korn, alltså fryst korn – som en kopierad frysbild på film. Alternativet (nytt korn per tidslinjeruta) bryter K1.
- **Samma källa två gånger på tidslinjen vid samma källtid** får samma korn. Ovanligt; ett salt per klippinstans skulle
  ändra kornet när klipp delas.
- **Stillbilder** har bara en källruta. **Beslut behövs:** stillastående korn (fysiskt riktigt för ett foto) eller
  levande korn med klippets egen rutnumrering (ändras om klippets början trimmas).
- **Befintliga projekt:** kornets mönster ändras en gång (statistiken inte). Ingen profilversion behövs;
  `RenderCache`- och `NodeCache`-versionerna höjs.

## Implementation (skiss)

- Filmeffekten får källtiden via den `clock` (pts → källtid) som redan finns för spårade fönster; nodcachens
  `clock` ger redan källtid (`r.start + pts`). Exporten skapar i dag bara `clock` när graderingen behöver den – för
  film behövs den alltid.
- Frö = `pcg(mediaId) ^ pcg(sourceFrame) ^ pcg(nodeId)`, och per pixel som i dag.
- `FilmFx` (CPU) använder samma hash per pixel i stället för en ström genom bilden.

## Acceptanskriterier

1. `grain_seed_check.py` utökad med pausad bild och nodcache: alla par för samma källruta i samma storlek ≥ 0,95;
   nästa ruta ≈ 0.
2. Nytt JVM-test: `FilmFx` och GPU-hashen ger samma heltal per pixel; ett instrumentellt eller bryggbaserat prov
   visar CPU-korn = GPU-korn bit för bit (före kodning).
3. Befintliga statistiska korntester oförändrat gröna (`grainMatchesSpektrafilmStatistically`,
   `grainVariantsMatchSpektrafilmStatistically`).
4. Frysning, slowmotion och baklänges: dokumenterat beteende enligt K1, med test.
5. Cacheversionerna höjda; inget i betan utan godkännande.
