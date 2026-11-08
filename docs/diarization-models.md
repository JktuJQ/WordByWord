# Диаризация

Приложение использует sherpa-onnx 2.0.1 на CPU.
`SherpaOnnxSpeakerDiarizer` сначала получает речевые интервалы, затем вычисляет голосовые признаки и повторно объединяет интервалы по участникам.

## Модели

| Компонент | Источник | Лицензия |
| --- | --- | --- |
| Сегментация pyannote/segmentation-3.0 int8 | [ONNX-архив](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2) | MIT |
| ERes2Net VoxCeleb | [3D-Speaker ONNX](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx) | Apache-2.0 |
| sherpa-onnx AAR/JNI | [Релиз 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) | Apache-2.0 |

URL и SHA-256 закреплены в `app/gradle/models.gradle`.
Задача `prepareDiarizationModels` проверяет скачанные файлы и размещает модели в assets, библиотеку — в `app/vendor/`.
Эти каталоги исключены из Git.
Веса упаковываются в APK и доступны без сети после установки.

## Алгоритм

- Сегментация работает по всему PCM 16 кГц, моно.
- Для голосового признака берётся центральный участок интервала продолжительностью до восьми секунд.
- Опорные интервалы от 1,5 секунды объединяются average-linkage по косинусному расстоянию с порогом 0,45.
- Интервалы от 0,3 секунды назначаются ближайшим сформированным центроидам.
- Если голосового признака недостаточно, используется ближайший по времени уже назначенный интервал.
- Временные границы передаются в `OverlapTranscriptAssembler` для совмещения с текстом.

Число участников определяется автоматически и не задаётся по эталону.
Память растёт с длительностью записи: PCM читается целиком в `float[]`.
Один нативный вызов сегментации не прерывается отменой; результат отменённой операции не сохраняется.

## Проверка

`DiarizationHypothesisTest` запускает используемый приложением алгоритм на Android и сохраняет RTTM-гипотезы.
`ScoreDiarization` сравнивает их с RTTM-эталонами без участия ASR.
Команды находятся в [evaluation/README.md](../evaluation/README.md), опубликованные результаты — в [TEST_RESULTS.md](TEST_RESULTS.md).
