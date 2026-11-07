# Модели и runtime для распознавания речи (issue #5)

Распознавание реализовано через `sherpa-onnx` v2.0.1: Silero VAD находит речевые
интервалы, `OfflineRecognizer` (GigaAM v2 int8, nemo_transducer) распознаёт текст
по каждому интервалу отдельно. Результат имеет `Timing.SEGMENT` — таймкоды
относятся к речевым фрагментам, не к отдельным словам.

## Файлы

| Файл | Источник | Лицензия | Назначение |
| --- | --- | --- | --- |
| `sherpa-onnx-2.0.1.aar` | [релиз v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) | Apache-2.0 | Android-биндинги sherpa-onnx (тот же файл, что уже скачивает `prepareDiarizationModels` для #6) |
| `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` | [sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19.tar.bz2) | MIT ([salute-developers/GigaAM](https://github.com/salute-developers/GigaAM); не проверяли LICENSE-файл байт в байт, но README репозитория и его зеркала указывают MIT) | ASR-модель GigaAM v2, русский язык |
| `silero_vad.onnx` | [релиз asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) | MIT ([snakers4/silero-vad](https://github.com/snakers4/silero-vad)) | Отсечение тишины перед распознаванием |

Контрольные суммы — в таске `prepareAsrModels` в `app/build.gradle`.

## Подготовка

Модели не хранятся в git (`app/src/main/assets/asr/` — в `.gitignore`, как и
`app/src/main/assets/diarization/`). Их скачивание, проверку SHA-256 и раскладку
делает сама сборка — Gradle-таска `prepareAsrModels`, навешенная на `preBuild`,
тем же способом, что и `prepareDiarizationModels` для #6. Достаточно:

```sh
./gradlew assembleDebug
```

При первом запуске (или если файлов нет на диске) таска скачает архив GigaAM
(≈165 МиБ) и `silero_vad.onnx` (≈629 КиБ) в `app/build/asr-downloads/`,
проверит контрольные суммы и распакует нужные файлы в `app/src/main/assets/asr/`;
при повторных запусках с уже готовыми файлами — не скачивает заново.

`SherpaSpeechRecognizer` и `Vad` читают модели прямо из `AssetManager`
(`new OfflineRecognizer(assets, config)`, `new Vad(assets, vadConfig)`) —
как и диаризация, без промежуточного копирования на диск устройства.
Готовая APK работает офлайн сразу после установки, без `adb push` и без
докачки на самом устройстве, как того требует [docs/hackathon.md](hackathon.md).
Скачивание происходит только на машине, которая собирает APK (CI или
разработчик).

## История: было до этой автоматизации

До появления `prepareAsrModels` модели нужно было вручную копировать в
`getExternalFilesDir(null)/models/gigaam-v2/` на устройстве (например через
`adb push`) — без этого шага распознавание падало с понятной ошибкой
(`ProcessingException`), но не работало вообще. `SherpaSpeechRecognizer`
принимал `java.io.File` с этой папкой вместо `AssetManager`. Старый файл
атрибуций `assets/asr-notices.txt` был удалён при рефакторинге интеграции
без замены — этот документ его заменяет.

## Известные ограничения

Не предполагать word-level таймкоды: `Timing.SEGMENT`, границы — по VAD-окнам
с добавленным контекстом (0,2 с с каждой стороны, не дальше середины до
соседнего окна).

Переизмерено (#29) после перехода на `AssetManager` инструментированным тестом
`AsrHypothesisTest` на том же наборе MINDS-14 (30 фраз, 433 слова), что и в
коммите `9785976`: WER 14,09% (61 ошибка), CER 8,70% — близко к прежним
13,63%/8,21% (коммит `9785976`, до рефакторинга `SherpaSpeechRecognizer` и
удалённой диагностической Activity), без расследования причины небольшого
расхождения. Подробности и разбор по каждой фразе — `evaluation/README.md` и
`evaluation/results/asr-report.json` после `evaluation/measure-on-device.sh`.
Всё измерялось на эмуляторе, не на физическом целевом устройстве.
