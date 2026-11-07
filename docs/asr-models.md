# Распознавание речи

Приложение использует sherpa-onnx 2.0.1, Silero VAD и GigaAM v3 RNNT int8 с пунктуацией.
VAD находит речевые интервалы; ASR распознаёт каждый интервал с контекстом 200 мс, максимум речи — 15 с.
ASR использует два CPU-потока.
Результат имеет `Timing.SEGMENT`: таймкоды относятся к речевым фрагментам, а не словам.

## Источники

| Компонент | Источник | Лицензия |
| --- | --- | --- |
| sherpa-onnx AAR/JNI | [релиз 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) | Apache-2.0 |
| GigaAM v3 RNNT, punctuation | [ONNX-архив sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2), [исходная модель](https://github.com/salute-developers/GigaAM) | MIT; `LICENSE` из архива включён в assets |
| Silero VAD | [ONNX-модель](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx), [репозиторий](https://github.com/snakers4/silero-vad) | MIT |

URL и SHA-256 закреплены в `prepareAsrModels` в `app/build.gradle`.
Архив GigaAM занимает около 162 МиБ.
В APK включаются `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`, `silero_vad.onnx` и лицензия GigaAM.

## Подготовка моделей

```sh
./gradlew assembleDebug
```

`prepareAsrModels` скачивает и проверяет файлы в `app/build/asr-downloads/`, затем размещает модели в `app/src/main/assets/asr/`.
Оба каталога исключены из Git.
Контрольные суммы источников объявлены входами Gradle-задачи, подготовленные файлы — выходами.
Смена версии или изменение выходного файла запускает подготовку заново; наличие файлов предыдущей модели не блокирует обновление.

Модели читаются через `AssetManager`, без копирования в отдельный каталог телефона.
Сеть нужна при первой сборке, но не при установке или обработке записи.
APK содержит модели и работает офлайн сразу после установки.

## Качество и ограничения

Сравнение с GigaAM v2 и Parakeet, правила нормализации и условия измерений — в [asr-quality.md](asr-quality.md).
Выбор v3 основан на Linux-прогонах; проверка на Sony Xperia 10 V остаётся обязательной перед сдачей.
Отдельный постпроцессор пунктуации и генеративная LLM не используются.
Пунктуация поступает из модели; на границах VAD-фрагментов возможны лишние точки и заглавные буквы.
Текст ASR сохраняется без нормализации скорера.

V3 использует BPE-токены, поэтому извлечение word-level таймкодов должно поддерживать субсловные токены, а не только отдельные символы.
Интеграция word-level таймкодов ведётся в PR #44.

Исторические результаты v2 в `evaluation/README.md` получены прежним скорером без пунктуации и не являются результатами новой модели.
