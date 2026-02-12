Níže je čistě analýza (bez kódu), kde dnes reálně vzniká latence a “zpoždění varování”, a co má největší efekt optimalizovat.

**1) Latence pipeline (kamera → inference → postprocess → UI)**
Největší brzda: převod ImageProxy → Bitmap přes JPEG

V ImageUtils.imageProxyToBitmap() děláš:
YUV → NV21
  YuvImage.compressToJpeg(...)
  BitmapFactory.decodeByteArray(...)
To je extrémně drahé (CPU + alokace + GC) a dělá to každé políčko.

A pak ještě:
  rotace bitmapy rotateBitmap(...)
  crop ROI bitmapy cropForRoi(...)
  v DetectionAnalyzer.analyze()

Dopad: největší část latence + GC špičky (to ti umí posunout alert o stovky ms až sekundy při “zaseknutí” GC).

**Největší win (řádově):**
- zrušit JPEG cestu a dělat YUV→RGB (nebo rovnou RGBA) bez komprese
- ideálně re-use bufferů/bitmap (žádné nové alokace každé políčko)
- případně posílat do modelu přímo ByteBuffer (bez Bitmap) – ještě lepší

**Druhá brzda: broadcasty s hromadou extras každé políčko**
  sendMetricsUpdate() posílá broadcast každý frame
  sendOverlayUpdate() posílá ještě větší Intent (tuna extra dat) – taky typicky každý frame
Dopad: overhead v Binderu + GC + UI thread wakeups → jitter.

**Win:**
- metriky throttle třeba na 10–15 Hz (ne 30+)
- overlay posílat jen když je zapnutý debug overlay (nebo taky throttle)

**Třetí brzda: logování per-frame**

V analyze() voláš flog(...) s frame parametry každý frame
Tohle taky dělá IO/strings/alloc.

**Win:** logovat jen při debug / sample 1× za N framů.

**2) Časová stabilizace detekce (tracking, smoothing, ROI filtry)**
Tracker = zpoždění alertu o N framů

TemporalTracker má minConsecutiveForAlert = 3 a alertGatePassed teprve pak.
To znamená, že i když model detekuje hned, alert “smí” až po 3 po sobě jdoucích.
Na 10 FPS je to ~300 ms, na 15 FPS ~200 ms, na 30 FPS ~100 ms. V reálu s latencí pipeline se to sčítá.

**Win / kompromis:**
- snížit na 2 u kritických tříd (auto) nebo když TTC už je nízké
- nebo použít “fast path”: Red alert může přeskočit consecutive gate, Orange ne

**ROI filtry a geometrie**
V analyze() navíc filtruješ detekce podle “containment in trapezoid” (>= 0.8).
To je OK, ale přidává CPU (geometrie) a hlavně může vyhazovat “hranové” detekce → zvyšuje misses → tracker se resetuje → další zpoždění.

**Win:**
- snížit strictness v kritických situacích (TTC už je nízké) nebo použít hysterézi ROI: jakmile je target locknutý, povolit mu mírně vyjet z ROI bez dropu
- EMA smoothing
Tracker blenduje box EMA (emaAlpha=0.25).
EMA je fajn na stabilitu, ale přidává malé zpoždění v “reakci” (mění se pomaleji). To je většinou OK; hlavně problém je spíš “dropy” a consecutive gate.

**3) Prahy TTC (orange/red) a hysteréze**
Nemáš hysterézi mezi stavy 0/1/2
alertLevel() je čistě prahový OR (TTC nebo distance nebo approachSpeed).
Když metriky šumí kolem hranice, bude to přepínat úroveň sem-tam.

Dopad: alerty “cukají” (zvuk/TTS může být otravný) a někdy se čeká, až to „stabilně spadne pod práh“ → zpoždění.

**Win:**
- hysteréze: pro přechod dolů vyžadovat výrazně lepší hodnoty než pro přechod nahoru
- např. Red ON při TTC ≤ 1.2s, Red OFF až při TTC ≥ 1.6s
- nebo časová hysteréze: stav se mění až když podmínka platí X ms
- “OR” kombinace metrik může dávat pozdní trigger

Když TTC výpočet stabilizuješ EMA/oknem a zároveň tracker čeká na consecutive, může to v součtu způsobit “alert až pozdě”.

**Win:**
- pro Red dát prioritu TTC (pokud TTC spadne rychle), a distance/approachSpeed brát jako sekundární
- nebo pro Red používat méně smoothingu


**Priorita optimalizací (největší efekt → nejmenší efekt)**
- Zrušit JPEG konverzi v ImageUtils.imageProxyToBitmap() a omezit alokace (re-use).
- Throttle broadcasty + logy (metriky/overlay/log ne každý frame).
- Zrychlit náběh alertu: upravit minConsecutiveForAlert nebo “fast path” pro Red.
- Hysteréze alert level (stavový automat místo čistých práhů).
- Jemnosti: ROI hysteréze pro locknutý target, úprava EMA alpha.
