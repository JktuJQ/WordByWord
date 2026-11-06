# Модели и runtime для диаризации (issue #6)

Диаризация реализована через `sherpa-onnx` v2.0.1: `OfflineSpeakerDiarization`
(сегментация pyannote/segmentation-3.0 + эмбеддинги 3D-Speaker + кластеризация).
Модель сама выполняет VAD как часть совместного вывода сегментации — отдельного
шага VAD в этом конвейере нет.

## Файлы

| Файл | Источник | Лицензия | Назначение |
| --- | --- | --- | --- |
| `sherpa-onnx-2.0.1.aar` | [релиз v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) | Apache-2.0 | Android-биндинги sherpa-onnx (та же версия, что уже проверена в #2) |
| `segmentation.int8.onnx` | [sherpa-onnx-pyannote-segmentation-3-0.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2), файл `model.int8.onnx` | MIT (CNRS, из архива — исходник [pyannote/segmentation-3.0](https://huggingface.co/pyannote/segmentation-3.0)) | Сегментация говорящих + VAD |
| `embedding.onnx` | [3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx) | Apache-2.0 ([3D-Speaker](https://github.com/alibaba-damo-academy/3D-Speaker), подтверждено через ModelScope) | Спикер-эмбеддинги |

Контрольные суммы — в таске `prepareDiarizationModels` в `app/build.gradle`.

## Известное ограничение: язык обучения эмбеддинга

`embedding.onnx` обучен на китайском (мандарин) корпусе 3D-Speaker — русской или
мультиязычной модели эмбеддинга с открытыми весами не нашли; GigaAM (ASR-модель,
уже выбранная для #5) спикер-эмбеддингов не предоставляет. `sherpa-onnx` технически
принимает только модели эмбеддинга с ONNX-метаданными `framework` ∈
{`wespeaker`, `3d-speaker`, `nemo`} — свою модель без переобучения в одном из этих
форматов не подключить.

Спикер-эмбеддинги в целом переносятся между языками лучше, чем ASR (кодируют
голос/тембр, а не фонетику) — на этом основан выбор, и так же поступают
независимо найденные сторонние проекты русской диаризации на базе GigaAM
(`protocolvoice/asr-models`, `TimHdm/slovami-ai-models` на HuggingFace, оба берут
тот же 3D-Speaker эмбеддинг). Качество на реальной русской речи не подтверждено
отдельно — проверяется эмпирически на `test-data/diarization/` в рамках #6.

## Подготовка

Модели и AAR не хранятся в git (`app/vendor/`, `app/src/main/assets/diarization/` —
в `.gitignore`). Их скачивание, проверку SHA-256 и раскладку делает сама сборка —
Gradle-таска `prepareDiarizationModels` в `app/build.gradle`, навешенная на
`preBuild`. Достаточно просто:

```sh
./gradlew assembleDebug
```

При первом запуске (или если файлов нет на диске) таска скачает все три
артефакта в `app/build/diarization-downloads/`, проверит контрольные суммы и
разложит их в `app/vendor/` и `app/src/main/assets/diarization/`; при
повторных запусках с уже готовыми файлами — не скачивает заново. Отдельного
ручного шага или Python не требуется.

После этого Gradle упаковывает модели в APK (~41 МБ к размеру сборки) —
готовая APK работает офлайн без докачки, как того требует
[docs/hackathon.md](hackathon.md). Скачивание происходит только на машине,
которая собирает APK (CI или разработчик) — не на устройстве, куда APK
устанавливается.

## Выполнение и проверка

Диаризация по-прежнему передаёт sherpa целый массив PCM, поэтому память растёт с длительностью записи. Чтение проверяет отмену, допустимость размера и ответы reader; достижение EOF не вызывает лишнего удвоения заполненного массива. Нативный `process()` не прерывается; отмена проверяется сразу после возврата, ресурсы освобождаются через `finally`.

Диагностические Activity удалены из основного APK. Оценка DER запускается отдельным instrumentation APK: команды и ограничения — в [инструкции оценки](../test-data/diarization/README.md). Изменения не добавляют зависимостей и не меняют конфигурацию моделей.
