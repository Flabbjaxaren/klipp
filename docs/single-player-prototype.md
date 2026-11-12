# Prototyp: en spelare för alla spår (2026-11-12, gren `single-player-prototype`)

**Frågan:** kan förhandsvisningen spela alla lager i Media3:s `CompositionPlayer` genom kompositorn, som exporten gör –
en klocka, inga egna lagerspelare, inget skarvlager?

**Byggt:** en växel (`CutTrace.singlePlayer`, via agentbryggan `cut_trace {"single":true}`): huvudspelaren får exportens
fulla flerlagerskomposition (`forceSingle = false`), appens lagerspelare och skarvlager används inte, tidslinjens Look
ligger i kompositionen. Mätning förberedd: bildkodat klipp på V2 i halv storlek nere till höger över bildkodat V1, båda
koderna avläses ur samma skärmbild (`sync.py` i scratch: skillnaden V1 − V2 per bildruta).

**Resultat på vivo X300 Ultra, Media3 1.11.1:**
1. Standardgrafen (`SingleInputVideoGraph`) vägrar: "SingleInputVideoGraph does not use VideoCompositor, and therefore
   cannot apply VideoCompositorSettings".
2. Med `CompositionPlayer.Builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory(...))` byggs spelaren, men
   **ingen bildruta når videokedjan** (0 i spåret från slutsteget), spelaren lämnar aldrig buffring, inget fel – även med
   Media3:s egen standardprocessor och även med en **minimal komposition** (en fil, inga effekter, inget ljud). Samma
   minimala komposition spelar med standardgrafen (29,6 bilder/s).
3. `MultipleInputVideoGraph.redraw()` kastar `UnsupportedOperationException` (spelaren anropar den); ändrad i den
   vendorade modulen till att rita om – ingen skillnad.

**Slutsats:** flerlagersuppspelning i `CompositionPlayer` fungerar inte i Media3 1.11.1 i den här uppsättningen; att få
den att fungera kräver felsökning och ändringar i Media3:s uppspelningsdelar med osäkert utfall. Jämförelsen mot dagens
väg kunde därför inte göras. **Rekommendation:** behåll dagens väg; pröva igen vid nästa Media3-uppgradering (samma växel
och mätning finns på grenen).

**Bimätning av dagens väg** (8 omgångar, V2 över V1, 25 fps): V2 ligger efter V1 med 0–2 bildrutor – med lagerspelarnas
tidiga utlämning på 50 ms: samma bildruta 42 %, en efter 55 %, två efter 3 %; med 35 ms: 15 % / 80 % / 4,5 %.
