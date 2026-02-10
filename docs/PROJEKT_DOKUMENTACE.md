# MCAW – analytická dokumentace projektu

> Stav dokumentace: odpovídá aktuálnímu `HEAD` větve `work`.

## 1) Co aplikace dělá (funkční popis)

MCAW je Android aplikace pro asistenci jezdci (kolo/moto) pomocí mobilní kamery. Hlavní smyčka:

1. CameraX snímá obraz.
2. AI detektor (YOLO ONNX nebo EfficientDet TFLite) vrací bbox detekce.
3. Postprocessing (thresholdy, NMS, geofiltry, ROI) čistí výstup.
4. Temporal tracker stabilizuje objekty mezi framy.
5. Výpočet fyziky odhaduje vzdálenost, relativní rychlost, TTC.
6. Alert logika vyhodnotí úroveň (safe/orange/red), pustí zvuk/vibrace/hlas.
7. Výstup jde do UI (overlay + metriky) i do foreground service režimu.

---

## 2) Architektura a vazby modulů

- **Bootstrap + config**: `MCAWApp`, `AppPreferences`, `AndroidManifest.xml`.
- **AI pipeline**: `DetectionAnalyzer` orchestruje modely, postprocess, tracking, fyziku, alerty.
- **Modely**: `YoloOnnxDetector`, `EfficientDetTFLiteDetector`.
- **Detekční utility**: `DetectionPostProcessor`, `TemporalTracker`, `DetectionLabelMapper`, `DetectionPhysics`, `ImageUtils`, `CoordinateTransformer`, `OnnxInput`.
- **Rychlost jezdce**: `SpeedProvider` + zdroje `LocationSpeedSource`, `BluetoothSpeedSource`, lifecycle wrapper `SpeedMonitor`.
- **UI + provoz**: `MainActivity`, `PreviewActivity`, `OverlayView`, `SettingsActivity`, `McawService`.
- **Model dat**: `Box`, `Detection`.
- **Utility**: `LabelMapper`, `PublicLogWriter`.

---

## 3) Souborová dokumentace (co je kde + vazby)

### Build + projektové soubory

- `build.gradle.kts` – root plugin verze Android/Kotlin.
- `settings.gradle.kts` – jméno projektu + zahrnutí modulu `:app`.
- `gradle.properties` – AndroidX/Jetifier + JVM memory pro Gradle.
- `app/build.gradle.kts` – Android konfigurace aplikace, SDK levely, dependency stack (CameraX, ONNX, TFLite, Material atd.).

### Manifest + resources

- `app/src/main/AndroidManifest.xml` – permission model (camera, location, BLE, foreground service) + registrace aktivit a service.
- `app/src/main/res/layout/*.xml` – obrazovky main/preview/settings.
- `app/src/main/res/values/strings.xml`, `arrays.xml` – texty a volby spinnerů.
- `app/src/main/res/raw/alert_beep.mp3` – zvukový alert.
- `app/src/main/assets/models/*.onnx|*.tflite` – modely inference.

### Aplikační runtime kód

- `app/src/main/java/com/mcaw/app/MCAWApp.kt`
  - Init `AppPreferences`, IO executoru, crash handleru, TTS.
  - Vazby: `AppPreferences`, `PublicLogWriter`.

- `app/src/main/java/com/mcaw/config/AppPreferences.kt`
  - Centrální runtime konfigurace (SharedPreferences): režim, model, alerty, user thresholdy, ROI, flags.
  - Vazby: používají téměř všechny UI/AI části.

- `app/src/main/java/com/mcaw/ai/DetectionAnalyzer.kt`
  - Hlavní orchestrátor inference.
  - Kamera frame -> model -> postprocess -> tracker -> physics -> alert state machine -> broadcast metrik/overlay.
  - Vazby: `YoloOnnxDetector`, `EfficientDetTFLiteDetector`, `DetectionPostProcessor`, `TemporalTracker`, `DetectionPhysics`, `SpeedProvider`, `AppPreferences`.

- `app/src/main/java/com/mcaw/ai/YoloOnnxDetector.kt`
  - ONNX Runtime inference YOLO modelu + parsing výstupního tensoru.

- `app/src/main/java/com/mcaw/ai/EfficientDetTFLiteDetector.kt`
  - TFLite inference EfficientDet modelu + podpora více output layoutů.

- `app/src/main/java/com/mcaw/ai/DetectionPostProcessor.kt`
  - Canonical label map, thresholdy, class-aware NMS, area/aspect/edge/ROI filtry.

- `app/src/main/java/com/mcaw/ai/TemporalTracker.kt`
  - IoU párování frame-to-frame, EMA vyhlazení, alert gate (min consecutive detekcí).

- `app/src/main/java/com/mcaw/ai/DetectionPhysics.kt`
  - Odhad vzdálenosti a TTC.

- `app/src/main/java/com/mcaw/ai/DetectionLabelMapper.kt`
  - Alias mapování labelů + COCO classId -> label.

- `app/src/main/java/com/mcaw/ai/ImageUtils.kt`
  - Převody `ImageProxy`/YUV -> `Bitmap`, rotace.

- `app/src/main/java/com/mcaw/ai/CoordinateTransformer.kt`
  - Konverze souřadnic boxů.

- `app/src/main/java/com/mcaw/ai/OnnxInput.kt`
  - Konstrukce `OnnxTensor` z NCHW dat.

- `app/src/main/java/com/mcaw/ai/AlertNotifier.kt`
  - Notifikační kanály (sound/vibrate/silent), kompatibilní fallback upozornění.

- `app/src/main/java/com/mcaw/location/SpeedProvider.kt`
  - Fúze zdrojů rychlosti + freshness/hold-last pravidla.

- `app/src/main/java/com/mcaw/location/SpeedMonitor.kt`
  - Start/stop wrapper pro speed stack.

- `app/src/main/java/com/mcaw/location/sources/LocationSpeedSource.kt`
  - GPS/network location update source rychlosti.

- `app/src/main/java/com/mcaw/location/sources/BluetoothSpeedSource.kt`
  - BLE scan + GATT subscribe pro rychlostní data.

- `app/src/main/java/com/mcaw/service/McawService.kt`
  - Foreground service režim (detekce bez preview obrazovky).

- `app/src/main/java/com/mcaw/ui/MainActivity.kt`
  - Dashboard, start/stop service, živé metriky, přechody do preview/settings.

- `app/src/main/java/com/mcaw/ui/PreviewActivity.kt`
  - Camera preview + overlay + analyzátor.

- `app/src/main/java/com/mcaw/ui/OverlayView.kt`
  - Render boxů a telemetrie.

- `app/src/main/java/com/mcaw/ui/SettingsActivity.kt`
  - UI pro editaci `AppPreferences`.

- `app/src/main/java/com/mcaw/model/Box.kt`
  - Datová třída bbox + odvozené geometrické veličiny.

- `app/src/main/java/com/mcaw/model/Detection.kt`
  - Datová třída detekce + runtime telemetrie (distance/rel speed/TTC).

- `app/src/main/java/com/mcaw/util/LabelMapper.kt`
  - Uživatelské překlady labelů (CZ text).

- `app/src/main/java/com/mcaw/util/PublicLogWriter.kt`
  - Export logů do veřejného úložiště (Downloads/MCAW) + fallback do interního storage.

---

## 4) Katalog parametrů projektu (hodnota, význam, doporučené meze)

> Pozn.: „doporučené meze“ jsou provozní doporučení pro tuning; nejsou to striktní compile-time limity.

### 4.1 Build / platform parametry

| Parametr | Aktuální hodnota | Význam | Doporučené meze + proč |
|---|---:|---|---|
| `compileSdk` | 34 | API proti kterému se app kompiluje | držet aktuální stabilní API (min. 33+), kvůli security API a moderním permission flow |
| `targetSdk` | 34 | cílové API chování aplikace | držet max dostupné stabilní API, kvůli Play policy a runtime behavior |
| `minSdk` | 26 | minimální Android verze | 26+ je rozumné pro CameraX/TFLite/ORT stabilitu |
| `versionCode` | 1 | interní build verze | monotónně růst |
| `versionName` | `1.0` | uživatelská verze | semver dle release politiky |
| `org.gradle.jvmargs` | `-Xmx2g` | paměť pro Gradle | 2–6 GB dle CI/stroje |

### 4.2 Uživatelské runtime parametry (`AppPreferences`)

| Klíč | Aktuální default | Význam | Doporučený rozsah |
|---|---:|---|---|
| `mode` (`detectionMode`) | `0` | 0=city, 1=sport, 2=user custom | {0,1,2} |
| `model` (`selectedModel`) | `1` | 0=YOLO ONNX, 1=EfficientDet TFLite | {0,1}; volit dle výkonu zařízení |
| `sound` | `true` | zvukové alerty | bool |
| `vibration` | `true` | vibrační alerty | bool |
| `voice` | `true` | TTS hlasové alerty | bool |
| `debugOverlay` | `false` | debug log/overlay režim | bool; v produkci false kvůli výkonu |
| `laneFilter` | `false` | aktivace pruhového/ROI omezení | bool; zapínat při vyšším množství falešných detekcí |
| `previewActive` | `false` | interní stav preview režimu | bool |
| `user_ttc_orange` | `3.0 s` | custom warning TTC | 1.5–6 s dle stylu jízdy |
| `user_ttc_red` | `1.2 s` | custom critical TTC | 0.6–2.5 s; níže příliš pozdě |
| `user_dist_orange` | `15 m` | custom warning distance | 8–60 m dle rychlosti |
| `user_dist_red` | `6 m` | custom critical distance | 3–20 m |
| `user_speed_orange` | `3 m/s` | custom warning approach speed | 1–12 m/s |
| `user_speed_red` | `5 m/s` | custom critical approach speed | 2–20 m/s |
| `roi_left_n` | `0.15` | levá hranice ROI (0..1) | 0..0.8 |
| `roi_top_n` | `0.15` | horní hranice ROI (0..1) | 0..0.8 |
| `roi_right_n` | `0.85` | pravá hranice ROI (0..1) | 0.2..1 |
| `roi_bottom_n` | `0.85` | dolní hranice ROI (0..1) | 0.2..1 |
| `ROI_MIN_SIZE_N` | `0.10` | minimální šířka/výška ROI | 0.05–0.5; menší ROI zvyšuje riziko ztráty cíle |

### 4.3 Parametry AI modelů

| Soubor | Parametr | Aktuální hodnota | Doporučené meze |
|---|---|---:|---|
| `YoloOnnxDetector` | `inputSize` | 640 | 320–960 (vyšší = přesnější, pomalejší) |
| `YoloOnnxDetector` | `scoreThreshold` | 0.25 | 0.15–0.50 |
| `YoloOnnxDetector` | `iouThreshold` | 0.45 | 0.3–0.7 |
| `EfficientDetTFLiteDetector` | `inputSize` | 320 | 256–640 |
| `EfficientDetTFLiteDetector` | `scoreThreshold` | 0.25 | 0.15–0.50 |
| `EfficientDetTFLiteDetector` | `iouThreshold` | 0.45 | 0.3–0.7 |

### 4.4 Postprocess / tracking / alert parametry

| Soubor | Parametr | Aktuální hodnota | Význam / doporučení |
|---|---|---:|---|
| `DetectionPostProcessor` | `classThresholds` | car/van/bus/truck 0.30, motorcycle/person 0.35, bicycle 0.40 | per-class precision/recall tuning |
| `DetectionPostProcessor` | `defaultThreshold` | 0.25 | fallback threshold |
| `DetectionPostProcessor` | `nmsIouThreshold` | 0.45 | vyšší = méně agresivní NMS |
| `DetectionPostProcessor` | `minAreaRatio` | 0.0002 | eliminace mikroboxů (šum) |
| `DetectionPostProcessor` | `maxAreaRatio` | 0.95 | eliminace nereálných obřích boxů |
| `DetectionPostProcessor` | `minAspect` / `maxAspect` | 0.15 / 6.0 | filtrace extrémních poměrů stran |
| `DetectionPostProcessor` | `edgeMarginRatio` | 0.07 | okrajové pásmo filtru |
| `DetectionPostProcessor` | `edgeFilterEnabled` | false | zapnout při falešných detekcích u okrajů |
| `DetectionPostProcessor` | `roiFilterEnabled` | false | zapnout pro detekci pouze v jízdní zóně |
| `DetectionPostProcessor` | `roi` | (0.15,0.15)-(0.85,0.85) | default geometrie ROI |
| `DetectionPostProcessor` | `roiMinContainment` | 0.80 | minimální průnik detekce s ROI |
| `TemporalTracker` | `minConsecutiveForAlert` | 3 | gate proti blikání alertů |
| `TemporalTracker` | `iouMatchThreshold` | 0.2 | párování tracků mezi framy |
| `TemporalTracker` | `maxMisses` | 3 | tolerance výpadku detekce |
| `TemporalTracker` | `emaAlpha` | 0.25 | vyhlazení bbox/score |
| `DetectionAnalyzer` | `ttcHeightHoldMs` | 800 ms | hold posledního TTC zdroje |
| `DetectionAnalyzer` | `minRiderSpeedForAlertsMps` | 2/3.6 ≈ 0.56 m/s | blokace alertů při velmi nízké rychlosti |
| `DetectionAnalyzer` | `closingSpeedDeadbandMps` | 0.25 | deadband relativní rychlosti |
| `DetectionAnalyzer` | `stateDebounceMs` | 320 ms | debounce přechodů stavů |
| `DetectionAnalyzer` | `orangeMaxBeeps` | 1 | max beep count pro orange |
| `DetectionAnalyzer` | `orangeRepeatMs` | 1800 ms | perioda orange beep |
| `DetectionAnalyzer` | `redRepeatMs` | 900 ms | perioda red beep |
| `DetectionAnalyzer` | `smoothRiderSpeed.deadband` | 0.8 m/s | odfiltrování GPS jitteru v nízké rychlosti |
| `DetectionAnalyzer` | `smoothRiderSpeed.alpha` | 0.20 | EMA rychlosti jezdce |
| `DetectionAnalyzer` | `thresholdsForMode(0)` | TTC 3.0/1.2, dist 15/6, speed 3/5 | město |
| `DetectionAnalyzer` | `thresholdsForMode(1)` | TTC 4.0/1.5, dist 30/12, speed 5/9 | sport |
| `DetectionAnalyzer` | `realHeightM` | 1.3 (moto/kolo), 1.5 ostatní | fyzikální odhad vzdálenosti |

### 4.5 Parametry speed stacku

| Soubor | Parametr | Aktuální hodnota | Doporučení |
|---|---|---:|---|
| `SpeedProvider` | BLE freshness | 2000 ms | 1–3 s |
| `SpeedProvider` | GPS freshness (high conf) | 2000 ms | 1–4 s |
| `SpeedProvider` | GPS stale limit | 4000 ms | 2–8 s |
| `SpeedProvider` | hold-last window | 1500 ms | 0.5–3 s |
| `LocationSpeedSource` | GPS interval/distance | 500 ms / 0.2 m | menší interval = vyšší spotřeba |
| `LocationSpeedSource` | NET interval/distance | 1000 ms / 0.3 m | fallback pro dostupnost |
| `BluetoothSpeedSource` | service UUID | `00001816...` | dle zařízení/senzoru |
| `BluetoothSpeedSource` | characteristic UUID | `00002A5B...` | dle zařízení/senzoru |
| `BluetoothSpeedSource` | parser | `uint16 kmh/100 -> m/s` | upravit dle protokolu reálného HW |

---

## 5) Revize čistoty kódu (nepoužívané části)

Provedená bezpečná revize:

1. Heuristická kontrola importů v celém `app/src/main/java`.
2. Ruční ověření kandidátů na odstranění.
3. Odstraněny pouze 2 **jistě nepoužívané importy** (bez dopadu na funkci):
   - `kotlin.math.abs` v `AppPreferences.kt`.
   - `kotlin.math.min` v `YoloOnnxDetector.kt`.

Další potenciální čištění (třídy/funkce) nebylo provedeno bez důkazu z kompilace/statické analýzy, aby nedošlo k rozbití aplikace.

---

## 6) Doporučený bezpečný postup dalšího čištění

1. Doplnit chybějící `gradle-wrapper.jar`, aby šla spustit `:app:compileDebugKotlin` a `lint`.
2. Zapnout CI krok s detektorem nepoužívaných symbolů (Detekt/ktlint + Kotlin warnings as errors pro importy).
3. Čistit postupně po malých commitech, vždy s runtime testem:
   - preview režim,
   - service režim,
   - přepínání modelů,
   - toggly v settings.

