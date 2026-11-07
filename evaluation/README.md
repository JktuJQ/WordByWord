# Оценка качества

Отдельный JVM-модуль для WER/DER, их тестов и небольших контрольных датасетов. Он не зависит от Android и не включается в APK.

- `fixtures/asr/` — архив аудио и manifest с эталонами ASR.
- `fixtures/diarization/` — аудио, метаданные и RTTM-эталоны. Происхождение, лицензии и атрибуция: [SOURCE.md](fixtures/diarization/SOURCE.md). Контрольные суммы проверяются из этой папки: `sha256sum -c SHA256SUMS`.
- `src/main/java/dev/wordbyword/evaluation/` — скореры WER/DER.

ASR-набор расширен в #32: `manifest.json`/`audio.zip` содержат 120 MINDS-14
(30 regression, 30 development, 60 evaluation). Эталоны MINDS местами неточны;
они сохранены без исправлений по выводу модели. `fleurs-validation.json` и
`fleurs-validation.zip` — отдельная проверка на 30 записях FLEURS validation,
не полный официальный тест и не разговоры нескольких участников. Наборы оцениваются отдельно.
- `src/test/java/dev/wordbyword/evaluation/` — тесты нормализации, формул и сопоставления участников.
- `results/` — локальные гипотезы и отчёты, игнорируются Git.
- `measure-on-device.sh` — собирает debug и androidTest APK, устанавливает их на подключённое устройство/эмулятор, получает гипотезы и сразу прогоняет оба скорера.

Гипотезы для скореров получают инструментированные тесты в `app/src/testAndroid/java/dev/wordbyword/app/evaluation/` (`DiarizationHypothesisTest`, `AsrHypothesisTest`) — они запускают настоящие `AndroidAudioPreparer`/`SherpaOnnxSpeakerDiarizer`/`SherpaSpeechRecognizer` на устройстве и пишут результат в кэш приложения. Так и должно быть: эти классы трогают `Context`/`MediaCodec`/нативные `.so`-библиотеки, а `app/src/test` исполняется против stub-jar, который на любом таком вызове бросает исключение — обычный JVM-юнит-тест этот путь не проверит.

Полный цикл одной командой из корня репозитория (нужен подключённый эмулятор/устройство, `nix develop`):

```sh
./evaluation/measure-on-device.sh
```

Скрипт устанавливает debug и androidTest APK под именами `dev.wordbyword.app`/`dev.wordbyword.app.test`, запускает оба инструментированных теста через `adb shell am instrument`, забирает файлы из `cache/` приложения через `adb exec-out run-as ... cat` (обычный способ прочитать приватное хранилище приложения без root) и удаляет оба APK при выходе — поэтому порядок именно такой: обычная задача Gradle `connectedDebugAndroidTest` сама удаляет приложение сразу после прогона, не оставляя времени забрать файлы из `cache/`.

Шаги можно повторить и по отдельности:

```sh
./gradlew :evaluation:test
./gradlew :evaluation:scoreDiarization --args='evaluation/fixtures/diarization/rec_0055.rttm evaluation/results/rec_0055.hyp.rttm 60 18'
./gradlew :evaluation:scoreAsr --args='evaluation/fixtures/asr/manifest.json evaluation/results/asr-hypotheses.json evaluation/results/asr-report.json'
```

Время `18` в примере — условное время обработки в секундах; нужно подставить измеренное для своего прогона. Без четвёртого аргумента DER-скорер не выводит RTF. Папку `results` нужно создать перед записью отчётов. Гипотезы ASR — объект с массивом `runs` (либо `samples`); каждая запись содержит `file` из manifest и строку `hypothesis`. Если есть `status`, он должен быть `READY`. Нужна ровно одна гипотеза для каждого файла manifest. Gson и JUnit — уже используемые в проекте зависимости.

Скореры оценивают готовые гипотезы и не доказывают качество моделей сами по себе отдельно от самого прогона на устройстве. Тест «эталон против себя» проверяет скорер.

Для FLEURS можно передать Android-тесту `-e archive fleurs-validation.zip -e output asr-fleurs.json`.
Четвёртый аргумент `scoreAsr` выбирает раздел MINDS: `regression`, `development` или `evaluation`.
Время каждого клипа в Android-гипотезах включает подготовку аудио, VAD и загрузку ASR.

Для сравнения на Linux есть `:app:benchmarkAsr`; нужны локальные JNI-библиотеки
sherpa-onnx 2.0.1 (`-PasrNativeDir=/путь/к/lib`). На вход подаётся заранее подготовленный
PCM16 LE, моно, 16 кГц; это исследование не заменяет Android-проверку. Например:

```sh
./gradlew :app:benchmarkAsr --args='evaluation/fixtures/asr/manifest.json evaluation/results/pcm local-models/gigaam-v2 pipeline development evaluation/results/asr-dev.json'
```

Вместо `pipeline` доступны `transducer` (NeMo RNNT/TDT) и `whisper` (int8 ONNX,
язык `ru`). В папке модели нужен `silero_vad.onnx`. Необязательные два последних
аргумента — максимальная длительность речи в секундах и контекст VAD в миллисекундах;
по умолчанию 15 и 200, как в приложении. Отчёт содержит WER/CER, RTF, хеши и гипотезы.

## Измерения

Получены `measure-on-device.sh` на эмуляторе Android API 36 (google_apis, arm64-v8a) в Nix devShell — не на физическом телефоне, этот TODO остаётся открытым.

### Диаризация

| Запись | DER | Confusion | Missed | False alarm | RTF |
| --- | ---: | ---: | ---: | ---: | ---: |
| rec_0055 (2 говорящих) | 27.97% | 14.31 с | 1.28 с | 2.21 с | 0.28 |
| rec_0273 (3 говорящих) | 13.54% | 4.60 с | 2.15 с | 1.10 с | 0.28 |

DER: collar=0, перекрытия учитываются, идентификаторы участников сопоставляются оптимально один-к-одному. Числа совпадают с прежними измерениями старой диагностической Activity — автоматизация гипотез не изменила результат, как и ожидалось.

RTF в таблице приближённый: `measure-on-device.sh` запускает оба файла одним `am instrument` и делит общее время прогона на два, а не замеряет каждый файл отдельно.

### Распознавание речи

Исторический прогон до расширения #32: 30 клипов MINDS-14, русский и смешанный с английским.

| Метрика | Значение |
| --- | ---: |
| WER | 14.09% |
| CER | 8.70% |
| Точных совпадений | 13 / 30 |

Основной источник ошибок — не грубые промахи, а нормализация (ё/е, слитное/раздельное написание) и несколько фраз на английском, которые модель, обученная на русском (GigaAM), распознаёт фонетически неточно (`fixtures/asr` → `minds_148.wav`). Подробный разбор по каждому клипу — в `results/asr-report.json` после запуска скрипта (файл игнорируется Git).
