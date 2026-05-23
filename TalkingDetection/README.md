# LipTalk — Обнаружение разговора по движению губ

Android-приложение реального времени, которое:
- Находит лицо и отслеживает губы через **MediaPipe Face Mesh** (478 точек)
- Вычисляет **MAR (Mouth Aspect Ratio)** — раскрытие рта
- Оценивает частоту движений методом **Zero-Crossing**
- Определяет факт разговора (речь: 1.5–7 Гц)
- Визуализирует контур губ и график MAR во времени

---

## Структура проекта

```
LipTalk/
├── app/src/main/
│   ├── java/com/example/liptalk/
│   │   ├── MainActivity.kt              — точка входа, CameraX
│   │   ├── camera/
│   │   │   └── FaceMeshProcessor.kt     — MediaPipe Face Landmarker
│   │   ├── analysis/
│   │   │   └── LipMotionAnalyzer.kt     — MAR + Zero-Crossing анализ
│   │   └── ui/
│   │       └── OverlayView.kt           — отрисовка поверх камеры
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   └── values/{strings, themes}.xml
│   └── assets/                          ← сюда положить модель!
│       └── face_landmarker.task
└── README.md
```

---

## Шаг 1: Скачать модель MediaPipe

**ОБЯЗАТЕЛЬНО** скачать и положить в `app/src/main/assets/`:

```
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task
```

Команда через curl:
```bash
curl -o app/src/main/assets/face_landmarker.task \
  "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
```

---

## Шаг 2: Открыть в Android Studio

1. `File → Open` → выбрать папку `LipTalk`
2. Дождаться синхронизации Gradle
3. Подключить Android-устройство (API 26+) или создать эмулятор
4. Нажать **Run ▶**

---

## Как работает алгоритм

### MAR (Mouth Aspect Ratio)

```
MAR = |верх_губы.y - низ_губы.y| / |левый_угол.x - правый_угол.x|
```

Точки MediaPipe:
- `13` — верхняя губа (центр)
- `14` — нижняя губа (центр)  
- `61` — левый угол рта
- `291` — правый угол рта

### Zero-Crossing (определение частоты)

```
1. Вычисляем среднее MAR за окно (60 кадров = 2 сек при 30 fps)
2. Считаем переходы MAR через среднее: ↑ или ↓
3. Частота = (переходы / 2) / время_окна_секунды
```

### Классификация "говорит / молчит"

| Условие | Результат |
|---------|-----------|
| Амплитуда MAR > 0.025 И частота 1.5–7 Гц | 🗣 ГОВОРИТ |
| Иначе | 🤫 МОЛЧИТ |

---

## Визуализация

| Элемент | Описание |
|---------|----------|
| Зелёный контур губ | Говорит |
| Красный контур губ | Молчит |
| Жёлтые точки | Опорные точки MAR |
| Панель метрик | MAR, частота, амплитуда, уверенность |
| График внизу | MAR во времени (2 сек) |
| Жёлтая пунктирная линия | Порог MAR_THRESHOLD |

---

## Зависимости

```gradle
com.google.mediapipe:tasks-vision:0.10.9    // Face Mesh
androidx.camera:camera-*:1.3.1              // CameraX
com.github.PhilJay:MPAndroidChart:v3.1.0    // (опционально, не используется в UI)
```

---

## Требования

- Android 8.0+ (API 26)
- Камера (желательно фронтальная)
- ARM64 или x86_64 процессор
