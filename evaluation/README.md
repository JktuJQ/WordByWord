# Оценка качества

Отдельный JVM-модуль со скорерами WER/CER, cpWER и DER.
Он не включается в APK.
Опубликованные результаты находятся в [artifacts/test-results](../artifacts/test-results/), условия измерений — в [docs/TEST_RESULTS.md](../docs/TEST_RESULTS.md).

## Данные

| Каталог | Содержание |
| --- | --- |
| `fixtures/asr/` | MINDS-14 и manifest с эталонами |
| `fixtures/asr-native-rate/` | Аудио для проверки декодирования с исходной частотой |
| `fixtures/diarization/` | Два минутных контрольных примера с RTTM |
| `fixtures/dialogs/` | Три составленных русскоязычных разговора |
| `fixtures/podcasts/` | Разговорные фрагменты и длинные записи |
| `fixtures/veche/` | Сценарные встречи raw/hard |

Источники, лицензии и ограничения разметки указаны в `SOURCE.md` рядом с каждым набором.
Нормализация ASR и сравнение моделей описаны в [asr-quality.md](../docs/asr-quality.md).
Предварительные эталоны разговоров не считаются независимо проверенной ручной разметкой.

Подготовка разговорных наборов требует Python 3.11+ без сторонних пакетов и FFmpeg/FFprobe:

```sh
python3 evaluation/scripts/prepare_podcasts.py
python3 evaluation/scripts/prepare_veche.py
python3 evaluation/scripts/check_corpus.py evaluation/fixtures/podcasts/manifest.json --audio
python3 evaluation/scripts/check_corpus.py evaluation/fixtures/veche/manifest.json --audio
```

Аудио и промежуточные результаты исключены из Git.
Загрузки и кэш подготовки описаны в документах соответствующих наборов.

## Скореры

Команды выполняются из корня репозитория.

```sh
./gradlew :evaluation:scoreAsr --args='evaluation/fixtures/asr/manifest.json evaluation/results/asr-hypotheses.json evaluation/results/asr-report.json'
./gradlew :evaluation:scoreDiarization --args='evaluation/fixtures/diarization/rec_0055.rttm evaluation/results/rec_0055.hyp.rttm 60'
./gradlew :evaluation:scoreTranscripts --args='evaluation/fixtures/podcasts/manifest.json evaluation/results/hypotheses evaluation/results/transcripts-report.json evaluation short --allow-provisional'
```

- `scoreAsr`: JSON с массивом `runs` или `samples`, поля `file` и `hypothesis`.
Для каждого файла manifest нужна ровно одна гипотеза; при наличии `status` требуется `READY`.
- `scoreDiarization`: эталонный RTTM, RTTM-гипотеза, длительность записи в секундах и необязательное время обработки для RTF.
- `scoreTranscripts`: каталог экспортированных стенограмм `<id>.json`, разделение development/evaluation и протокол short/long/raw/hard согласно manifest.
Флаг `--allow-provisional` нужен для предварительной разметки.

WER/CER оценивают ошибки текста; `lexical_wer` и `lexical_cer` исключают пунктуацию.
Числа канонизируются по правилам скорера.
cpWER сопоставляет текстовые потоки участников оптимальной перестановкой.
DER учитывает пропущенную речь, ложную речь и путаницу участников, collar=0, с учётом наложений.
DER по итоговым репликам JSON включает влияние совмещения с ASR; прямой DER по RTTM оценивает диаризацию отдельно.

## Android-проверки

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.wordbyword.app.evaluation.DiarizationHypothesisTest dev.wordbyword.app.test/androidx.test.runner.AndroidJUnitRunner
```

Доступны `AsrHypothesisTest`, `DialogsHypothesisTest`, `CommonVoiceHypothesisTest`, `DiarizationHypothesisTest`, `InferenceBenchmarkTest` и `SummaryFeatureTest`.
Суммаризация требует явного opt-in `-e runSummary true`.
Сохраняйте идентификатор устройства и проверяйте итог инструментирования, а не только код завершения adb.

## Скорость и память

`benchmark.sh` снимает время и выборочные PSS/RSS при обработке одной записи через обычный интерфейс.

```sh
bash evaluation/benchmark.sh parallel 60
```

Полностью автоматическое сравнение последовательного и параллельного режимов:

```sh
bash evaluation/compare-inference.sh
```

Скрипт собирает APK обоих режимов, запускает изолированный AVD и обрабатывает `rec_0055.flac` через производственный конвейер.
Он сравнивает JSON и сохраняет отчёты в `evaluation/results/`.
На машине с ограниченной RAM сборку выполняйте до запуска эмулятора.
Выборочный пик памяти может пропустить кратковременный максимум.
