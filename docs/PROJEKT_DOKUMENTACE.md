# MCAW – technická dokumentace projektu (na základě analýzy kódu)

> Verze dokumentace: generováno z aktuálního `HEAD`.

---

## 1) Úvodní stránka: co systém dělá a jak má fungovat

MCAW je Android aplikace pro **detekci rizikových objektů před jezdcem** (auto/motorka/kolo/chodec apod.) pomocí kamery telefonu. Nad detekcí běží výpočet telemetrie (odhad vzdálenosti, relativní rychlosti, TTC) a následná alert logika (zvuk/vibrace/hlas/UI).

### Zjednodušený tok dat

1. **Kamera (CameraX)** dodává snímky (`ImageProxy`) do analyzátoru.
2. `DetectionAnalyzer` převádí frame (`YUV -> Bitmap`), otáčí dle orientace a volá zvolený model:
   - `YoloOnnxDetector` (ONNX), nebo
   - `EfficientDetTFLiteDetector` (TFLite).
3. Surové detekce jdou přes `DetectionPostProcessor`:
   - mapování labelů,
   - score threshold,
   - class-aware NMS,
   - filtry (ROI, edge, area, aspect).
4. Výstup detekcí stabilizuje `TemporalTracker` (IoU matching, EMA, gate pro alerty).
5. `DetectionAnalyzer` počítá fyziku (`DetectionPhysics`) + kombinuje se `SpeedProvider`.
6. Výsledek jde do:
   - UI overlay (`PreviewActivity` + `OverlayView`),
   - metrik (`MainActivity`),
   - alert vrstvy (sound/vibration/voice).

### Co je důležité pro ladění detekce

Nejdůležitější parametry jsou rozprostřené ve 4 místech:

- `DetectionPostProcessor.Config` (filtry, prahy, NMS, ROI),
- `TemporalTracker` (časová stabilizace + gate),
- `YoloOnnxDetector` / `EfficientDetTFLiteDetector` (input size, score threshold, parse výstupů),
- `DetectionAnalyzer` (výběr modelu, vazba na alerty, speed=0 logika, TTC/distance).

---

## 2) Logické celky projektu

### 2.1 Aplikační bootstrap a konfigurace

- `app/src/main/java/com/mcaw/app/MCAWApp.kt`
- `app/src/main/java/com/mcaw/config/AppPreferences.kt`
- `app/src/main/AndroidManifest.xml`

### 2.2 Detekční AI pipeline

- `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`
- `app/src/main/java/com/mcaw/ai/YoloOnnxDetector.kt`
- `app/src/main/java/com/mcaw/ai/EfficientDetTFLiteDetector.kt`
- `app/src/main/java/com/mcaw/ai/DetectionPostProcessor.kt`
- `app/src/main/java/com/mcaw/ai/TemporalTracker.kt`
- `app/src/main/java/com/mcaw/ai/DetectionLabelMapper.kt`
- `app/src/main/java/com/mcaw/ai/DetectionPhysics.kt`
- `app/src/main/java/com/mcaw/ai/ImageUtils.kt`
- `app/src/main/java/com/mcaw/ai/CoordinateTransformer.kt`
- `app/src/main/java/com/mcaw/ai/OnnxInput.kt`
- `app/src/main/java/com/mcaw/ai/AlertNotifier.kt`

### 2.3 Speed stack

- `app/src/main/java/com/mcaw/location/SpeedProvider.kt`
- `app/src/main/java/com/mcaw/location/SpeedMonitor.kt`
- `app/src/main/java/com/mcaw/location/sources/LocationSpeedSource.kt`
- `app/src/main/java/com/mcaw/location/sources/BluetoothSpeedSource.kt`

### 2.4 UI a provozní režimy

- `app/src/main/java/com/mcaw/ui/MainActivity.kt`
- `app/src/main/java/com/mcaw/ui/PreviewActivity.kt`
- `app/src/main/java/com/mcaw/ui/OverlayView.kt`
- `app/src/main/java/com/mcaw/ui/SettingsActivity.kt`
- `app/src/main/java/com/mcaw/service/McawService.kt`

### 2.5 Datové a utilitní třídy

- `app/src/main/java/com/mcaw/model/Box.kt`
- `app/src/main/java/com/mcaw/model/Detection.kt`
- `app/src/main/java/com/mcaw/util/LabelMapper.kt`
- `app/src/main/java/com/mcaw/util/PublicLogWriter.kt`

---

## 3) Detekční pipeline – detailní dokumentace (priorita)

## 3.1 `DetectionAnalyzer.kt` – orchestrace celé inference + alertů

**Role souboru:**
- centrální `ImageAnalysis.Analyzer`,
- volí aktivní model podle `AppPreferences.selectedModel`,
- spouští postprocess + tracking,
- počítá distance/TTC/relative speed,
- rozhoduje o alert levelu,
- vysílá broadcasty pro overlay a metriky.

**Napojení:**
- vstup: CameraX (`ImageProxy`),
- modely: `YoloOnnxDetector`, `EfficientDetTFLiteDetector`,
- postprocess: `DetectionPostProcessor`,
- tracking: `TemporalTracker`,
- physics: `DetectionPhysics`,
- speed: `SpeedProvider`,
- UI: `MCAW_DEBUG_UPDATE` + `ACTION_METRICS_UPDATE` broadcast.

**Laditelné parametry / konstanty v souboru:**
- `TemporalTracker(minConsecutiveForAlert = 3)` → gate alertů (3 snímky po sobě),
- `speed <= 0.01f` → okamžité vypnutí alertů,
- `thresholdsForMode()` → mapování režimu na TTC/dist/speed prahy,
- `realHeightM` v odhadu vzdálenosti (1.3 pro moto/bicycle, 1.5 ostatní).

**Poznámka pro tuning:**
- Overlay nyní následuje track, který už prošel postprocessingem/trackerem; pokud chceš více „raw“ vizualizaci, je potřeba rozdělit data větev „UI detekce“ vs „alert kandidáti“.

---

## 3.2 `DetectionPostProcessor.kt` – hlavní filtrační vrstva

**Role souboru:**
- normalizace labelů,
- thresholding dle třídy,
- class-aware NMS,
- geometrické filtry (area, aspect, edge, ROI),
- vrací i `rejected` položky s důvodem.

**Napojení:**
- vstup: `List<Detection>` z detektoru,
- výstup: `Result(accepted, rejected, counts)` pro `DetectionAnalyzer`.

**Laditelné parametry (`Config`) – klíčové pro tuning detekčních zón:**

1. `classThresholds` – per-class score threshold.
   - car/van/bus/truck 0.30
   - motorcycle 0.35
   - bicycle 0.40
   - person 0.35
2. `defaultThreshold = 0.25` – fallback pro ostatní labely.
3. `nmsIouThreshold = 0.45` – IoU pro NMS.
4. `minAreaRatio = 0.0002` – minimální plocha boxu relativně k frame.
5. `maxAreaRatio = 0.95` – maximální plocha boxu.
6. `minAspect = 0.15`, `maxAspect = 6.0` – poměr stran boxu.
7. `edgeMarginRatio = 0.07` – okrajové pásmo.
8. `edgeFilterEnabled = false` – zapnutí/vypnutí edge filtru.
9. `roiFilterEnabled = false` – zapnutí/vypnutí ROI filtru.
10. `roi = RectNorm(0.45, 0.10, 1.0, 0.95)` – pravá část obrazu.
11. `debug` – detailní log pipeline.

**Důvody vyhození (`RejectedDetection.reason`):**
- `lowScore`, `NMS`, `minArea`, `aspect`, `edge`, `outsideROI`, `invalidFrame`.

**Napojení pro ladění:**
- `DetectionAnalyzer` loguje pipeline counts a důvody rejectu; to je hlavní místo pro diagnostiku „proč mám 0 detekcí“.

---

## 3.3 `TemporalTracker.kt` – stabilizace v čase a gate alertů

**Role souboru:**
- páruje detekce napříč snímky přes IoU,
- vytváří/udržuje tracky,
- aplikuje EMA vyhlazení boxu/score,
- drží `consecutiveDetections`,
- nastavuje `alertGatePassed` po dosažení minima.

**Napojení:**
- vstup: `accepted` detekce z postprocessu,
- výstup: tracky pro `DetectionAnalyzer`.

**Laditelné parametry:**
- `matchIouThreshold` (typicky ~0.3),
- `maxMisses` (kolik frame může track chybět),
- `emaAlphaBox`, `emaAlphaScore`,
- `minConsecutiveForAlert` (aktuálně 3).

**Dopad na chování:**
- příliš přísné IoU/misses → flicker,
- příliš měkké → ghost tracky.

---

## 3.4 `YoloOnnxDetector.kt` – ONNX inference

**Role souboru:**
- načte ONNX model z assets,
- preprocess do NCHW float tensoru,
- parsuje output (channel-first/interleaved varianty),
- počítá score (`objectness * classProb`, pokud objektovost existuje),
- mapuje classId přes `DetectionLabelMapper.cocoLabel`.

**Laditelné parametry:**
- `inputSize = 640`,
- `scoreThreshold = 0.25`,
- `iouThreshold = 0.45` (v aktuální verzi hlavně metadata; NMS dělá postprocessor).

**Napojení:**
- používá ONNX Runtime,
- výstup jde do `DetectionAnalyzer`.

**Poznámka:**
- tento soubor je citlivý na tensor layout modelu; při výměně modelu nutné ověřit `outputShape` log.

---

## 3.5 `EfficientDetTFLiteDetector.kt` – TFLite inference

**Role souboru:**
- načte TFLite model z assets,
- preprocess bitmap na NHWC tensor,
- podporuje dva typy výstupů:
  - 4-output (`boxes/classes/scores/count`),
  - 2-output (`boxes` + `class scores`).

**Napojení:**
- TFLite Interpreter,
- label mapping přes `DetectionLabelMapper.cocoLabel`.

**Laditelné parametry:**
- `inputSize = 320`,
- `scoreThreshold = 0.25`,
- `iouThreshold = 0.45`.

**Důležité detaily pro tuning:**
- v 2-output větvi se vybírá best class z `interestedClassIds` (0,1,2,3,5,7,4,6,8),
- při debug je jednorázový log shape tensorů,
- boxy se škálují do původního frame.

---

## 3.6 `DetectionLabelMapper.kt` – canonical label vrstva

**Role souboru:**
- sjednocuje CZ/EN aliasy do canonical labelů,
- poskytuje COCO mapování ID -> label.

**Laditelné parametry:**
- mapa `aliases`,
- `cocoLabel()` mapování classId (aktuálně explicitně: person, bicycle, car, motorcycle, bus, truck).

**Napojení:**
- používá `DetectionPostProcessor`, `YoloOnnxDetector`, `EfficientDetTFLiteDetector`.

---

## 3.7 `DetectionPhysics.kt` – fyzikální odhady

**Role souboru:**
- odhad vzdálenosti (`Z = f*H/h`),
- TTC z růstu výšky boxu,
- adaptivní TTC threshold helper.

**Laditelné parametry:**
- `realHeightM`,
- `focalPx` (odvozeno z kalibrace kamery),
- mapování v `adaptiveTtcThreshold()`.

**Napojení:**
- volá `DetectionAnalyzer`.

---

## 3.8 `ImageUtils.kt` + `CoordinateTransformer.kt`

### `ImageUtils.kt`
- převod `ImageProxy (YUV_420_888)` do `Bitmap` přes NV21/JPEG,
- rotace bitmapy podle `rotationDegrees`.

**Parametry:**
- JPEG kvalita v `compressToJpeg(..., 90, ...)`.

### `CoordinateTransformer.kt`
- helper pro převod `Box <-> normalized coordinates`.

**Napojení:**
- obecný helper; vhodný pro sjednocení ROI/overlay mapování mezi portrait/landscape.

---

## 4) Speed stack

## 4.1 `SpeedProvider.kt`

**Role souboru:**
- sjednocuje zdroje rychlosti,
- priorita: BLE (fresh <=2s) > GPS (<=2s) > GPS stale (<=4s) > UNKNOWN,
- hold-last: při UNKNOWN drží poslední hodnotu ~1.5 s.

**Laditelné parametry:**
- freshness okna (`2000`, `4000` ms),
- hold-last (`1500` ms),
- confidence hodnoty.

## 4.2 `SpeedMonitor.kt`

- life-cycle wrapper (`start/stop`) pro `SpeedProvider`,
- `pollCurrentSpeedMps()` promítá rychlost do `AppPreferences.lastSpeedMps`.

## 4.3 `LocationSpeedSource.kt`

- GPS + Network provider,
- update intervaly:
  - GPS: 500 ms, 0.2 m
  - NETWORK: 1000 ms, 0.3 m

## 4.4 `BluetoothSpeedSource.kt`

- BLE scan/connect/subscribe,
- default UUID:
  - service `00001816...` (Cycling Speed and Cadence),
  - characteristic `00002A5B...`,
  - CCCD `00002902...`.
- `parseSpeed()` je generic placeholder (2B km/h/100 -> m/s).

**Laditelné parametry:**
- UUID service/char,
- parser `parseSpeed()` dle konkrétního HW,
- scanning policy.

---

## 5) UI a provozní režimy

## 5.1 `MainActivity.kt` (dashboard)

- ovládání start/stop enginu,
- zobrazení metrik přes receiver `MCAW_METRICS_UPDATE`,
- periodický poll rider speed přes `SpeedMonitor`,
- spouští `PreviewActivity`/`SettingsActivity`.

## 5.2 `PreviewActivity.kt` (preview režim)

- CameraX preview + analyzer,
- subscribuje `MCAW_DEBUG_UPDATE` a kreslí `OverlayView`,
- mapuje labely přes `LabelMapper`,
- udržuje kalibraci kamery (focal length / sensor size),
- při aktivním preview stopuje service analýzu a naopak.

## 5.3 `OverlayView.kt`

- kreslí box + text telemetrie,
- zobrazuje debug prvky (ROI/edge), pokud jsou předány.

## 5.4 `SettingsActivity.kt`

- nastavuje AppPreferences:
  - režim detekce,
  - model,
  - lane filter,
  - debug overlay,
  - user thresholds pro TTC/dist/speed,
  - alert přepínače.

## 5.5 `McawService.kt` (no-preview režim)

- foreground service,
- běží stejný `DetectionAnalyzer` bez preview okna,
- inicializuje modely a kamera pipeline,
- retry logika při failu bindu kamery,
- speed monitor běží i v service režimu.

---

## 6) Konfigurace a centrální parametry

## 6.1 `AppPreferences.kt` – runtime konfigurace

Klíče relevantní pro ladění detekce:

- `detectionMode` – 0 default / 1 sport / 2 custom,
- `selectedModel` – 0 YOLO, 1 EfficientDet,
- `debugOverlay` – debug logy/overlay,
- `laneFilter` – aktivace lane/ROI filtru,
- `userTtcOrange`, `userTtcRed`,
- `userDistOrange`, `userDistRed`,
- `userSpeedOrange`, `userSpeedRed`,
- `cameraFocalLengthMm`, `cameraSensorHeightMm`,
- `lastSpeedMps`.

---

## 7) Datový model + utility

## 7.1 `Box.kt`

- reprezentace bbox,
- helpery: `w`, `h`, `cx`, `cy`, `area`.

## 7.2 `Detection.kt`

- nese `box`, `score`, `label`, `trackId`,
- runtime telemetrie (`distanceM`, `relSpeedMps`, `ttcSec`).

## 7.3 `LabelMapper.kt`

- mapování technických labelů do uživatelsky čitelných názvů (CZ text pro UI).

## 7.4 `PublicLogWriter.kt`

- zápis textových log souborů (diagnostika, activity/service/model).

---

## 8) Manifest a oprávnění

`AndroidManifest.xml` deklaruje:

- camera + location,
- vibration,
- BLE (`BLUETOOTH`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`),
- foreground service + typy `camera|location` pro `McawService`.

---

## 9) Praktický návod: jak ladit detekční zóny, časování, thresholdy

### 9.1 Nejrychlejší workflow ladění

1. Zapnout `debugOverlay` v nastavení.
2. Jet v `PreviewActivity` (vizuální feedback + debug logy).
3. Sleduj pipeline počty:
   - raw -> threshold -> NMS -> filters -> tracks -> gate.
4. Pokud „raw > 0, accepted = 0“:
   - snížit `classThresholds/defaultThreshold`,
   - vypnout `roiFilterEnabled`/`edgeFilterEnabled`,
   - snížit `minAreaRatio`, rozšířit `minAspect/maxAspect`.
5. Pokud je „accepted > 0, alert = 0“:
   - zkontrolovat `minConsecutiveForAlert` v trackeru,
   - zkontrolovat speed gate (`speed <= 0.01f` blokuje alerty),
   - zkontrolovat custom thresholdy režimu.

### 9.2 Doporučené pořadí úprav

1. **Modelové prahy** (`scoreThreshold` v detektoru + postprocess classThresholds).
2. **Geometrie** (`minAreaRatio`, `aspect`, ROI).
3. **NMS** (`nmsIouThreshold`).
4. **Časování trackeru** (`matchIoU`, `maxMisses`, `minConsecutiveForAlert`).
5. **Alert prahy** (`userTtc/dist/speed`).

### 9.3 Risky změny (pozor)

- příliš agresivní ROI + edge + minArea zároveň často vede na nulové výstupy,
- změna parseru model output bez ověření `outputShape` logu,
- použití gate logiky pro UI (gate má být jen pro alert, ne pro surovou detekci).

---

## 10) Doporučené pokračování dokumentace

- Přidat kapitolu „Known issues“ s konkrétními zařízeními a model variantami.
- Dodat export ladicích profilů (JSON preset: city/highway/night/rain).
- Rozšířit parser BLE rychlosti podle reálného HW (OBD plugin architektura).

