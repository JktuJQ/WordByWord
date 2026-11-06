# Проверка Android ASR/VAD — часть Issue #2

Изолированное экспериментальное приложение для [#2](https://github.com/JktuJQ/WordByWord/issues/2).
Основное приложение, его зависимости и общие контракты не изменены.
Диаризация не запускается. Issue остаётся открытой: реальный телефон и
диаризационная часть ещё не проверены.

## Конфигурация

- sherpa-onnx **v2.0.1**, официальный Android AAR.
- GigaAM v2: `sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19`:
  `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`.
  Графы модели суммарно 241 086 243 байта (около 230 МиБ).
- Silero VAD: `silero_vad.onnx`, окно 512 отсчётов, 16 кГц,
  порог 0,5, минимальные речь/тишина 0,25 с, максимальная речь 15 с.
- CPU, ASR — 2 потока, `nemo_transducer`, `greedy_search`;
  VAD — 1 поток. PCM16 WAV, моно, 16 кГц.
- Java 17, AGP 8.13.2, compile/target SDK 36, min SDK 26.
  Kotlin stdlib 2.2.0 требуется Android-обёрткам AAR; исходники пробы — Java.
- Проверено: AVD Pixel 6, `sdk_gphone64_x86_64`, Android API 36,
  хост Linux Mint / AMD Ryzen 5 5600H / 16 ГиБ RAM.
  Профиль Pixel не означает производительность настоящего Pixel.

## Получение файлов

Скачивание выполняется отдельно. `probe.py prepare` ничего не скачивает,
проверяет SHA-256 трёх файлов и готовит входы. Большие файлы, APK и модели
не хранятся в Git. Контрольные суммы находятся в `probe.py`.

| Файл | Размер загрузки | Источник |
| --- | --- | --- |
| `sherpa-onnx-2.0.1.aar` | 49 441 341 байт | [релиз v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar) |
| `sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19.tar.bz2` | около 165 МиБ | [архив модели](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19.tar.bz2) |
| `silero_vad.onnx` | около 629 КиБ | [модель VAD](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) |

Описание GigaAM и конфигурации: [документация sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html).
Проверяемый Android API: [OfflineRecognizer.kt](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/OfflineRecognizer.kt),
[Vad.kt](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/sherpa-onnx/kotlin-api/Vad.kt).
Отдельный JVM JAR в APK не нужен и не подключается: используется Android AAR.

## Воспроизведение

Нужны Python 3.10+, ffmpeg, JDK 21, Android SDK Platform 36,
Build Tools 36.0.0, platform-tools и запущенный эмулятор либо подключённый телефон.
Команды выполняются из корня репозитория.

```sh
python3 experiments/asr-probe/probe.py prepare /папка/со/скачанными/файлами
./gradlew -p experiments/asr-probe assembleDebug lintDebug
adb devices -l
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --prepare-device --output /tmp/example.json
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --offline --output /tmp/example-offline.json
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --padding-ms 200 --output /tmp/context.json
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --wave padded.wav --padding-ms 200 --offline --output /tmp/padded.json
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --wave silence.wav --output /tmp/silence.json
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --wave short.wav --output /tmp/short.json
python3 experiments/asr-probe/probe.py run --serial emulator-5554 --wave two-speakers.wav --padding-ms 200 --vad-only --offline --output /tmp/two-speakers.json
python3 experiments/asr-probe/probe.py verify
```

`JAVA_HOME` и `ANDROID_HOME` должны указывать на установленные JDK и SDK.
Если adb отсутствует в PATH, передать `--adb /полный/путь/platform-tools/adb`.
`--prepare-device` устанавливает только отдельное приложение
`dev.wordbyword.asrprobe` и копирует входы в его внешний каталог.
Последующие запуски используют эти же входы, но заново выполняют inference.
Для телефона заменить serial и сначала повторить `--prepare-device`.
В AAR включены x86_64 и arm64-v8a; arm64 ещё не проверен.

`--offline` временно включает авиарежим и выключает Wi-Fi/мобильные данные
только выбранного Android-устройства, сохраняет dumpsys connectivity и
в `finally` восстанавливает прежние переключатели. У APK нет разрешения INTERNET.
Для каждого запуска используется уникальный ID: старый result.json не принимается
за новый результат. Таймаут ожидания — 300 с.

## Входы и результаты 6 ноября 2026

- `example.wav`: 11,29 с, русское чтение из архива модели.
- `padded.wav`: тот же PCM с 2 с нулей до и 1 с после, 14,29 с.
- `silence.wav`: 3 с нулей.
- `short.wav`: первые 0,2 с примера; это проверка устойчивости, не качества коротких реплик.
- `two-speakers.wav`: `test-data/diarization/rec_0055.flac`, декодированный
  ffmpeg в PCM16 WAV, 60 с. Используется только аудио, не RTTM и не диаризация.
  Источник и ограничения записи: [README набора](../../test-data/diarization/README.md).

Семь сырых JSON-отчётов объединены без изменения данных в [results.json](results.json).
Это автоматически полученные данные запусков, а не исходный код; при ревью
не требуется построчно проверять все токены и диагностические дампы.
Отдельные копии отчётов в `results/` остаются локальными и исключены из Git.
Измерения единичные,
на загруженном компьютере; эталонной транскрипции для WER нет.
`probe.py verify` проверяет сохранённый `results.json`; команды выше
пишут новые измерения в /tmp, чтобы не перезаписывать исходные результаты.
Для проверки набора отдельных JSON передать `verify --reports /папка/отчётов`
с именами `example-online`, `example-offline`, `example-context`, `padded-offline`,
`silence`, `short`, `two-speakers-offline` и расширением `.json`.

| Запуск | Загрузка ASR, мс | ASR целого файла, мс | VAD, мс | ASR фрагментов, мс | Фрагменты VAD |
| --- | ---: | ---: | ---: | ---: | ---: |
| example, сеть включена | 1418 | 2334 | 81 | 2030 | 2 |
| example, без сети | 2029 | 2981 | 101 | 2534 | 2 |
| example, контекст 200 мс | 1431 | 2801 | 100 | 2550 | 2 |
| padded, контекст 200 мс, без сети | 1929 | 3656 | 110 | 2483 | 2 |
| тишина 3 с | 2286 | 677 | 12 | 0 | 0 |
| фрагмент 0,2 с | 1904 | 76 | 1 | 0 | 0 |
| два голоса 60 с, контекст 200 мс, без сети | 1220 | не запускался | 428 | 12439 | 13 |

Для минутного файла VAD + ASR = 12,867 с, RTF около **0,214** без загрузки
моделей. Это не показатель скорости на телефоне. Пиковый RSS всего процесса
этого запуска (`VmHWM`) — 607 712 КиБ, около **593 МиБ**;
PSS после декодирования — 502 090 КиБ, около **490 МиБ**.
PSS — один снимок, не пик. В остальных запусках процесс последовательно
делал полный ASR и ASR по VAD, поэтому их VmHWM не разделяет эти режимы.

Подтверждено:

- Java вызывает готовый Android JNI; русский текст непустой и связный.
- Полный ASR с сетью и без сети дал одинаковые текст, токены и таймкоды.
  В офлайн-отчётах `Active default network: none`.
- Тишина и фрагмент 0,2 с дали пустой текст и ноль VAD-фрагментов, без падения.
- Количество токенов совпадает с количеством времён; времена не убывают и
  лежат внутри длительности исходного файла.
- При добавлении паузы текст сохранился, начало токенов сдвинулось примерно
  на 2 с; VAD-фрагменты декодируются с прибавлением смещения исходной записи.
  Это проверка шкалы времени, не измерение акустической точности границ.
- Без контекста VAD обрезал первый слог: «ничьих» превратилось в «чьих».
  Запас 200 мс по краям восстановил слово на этом примере.

## Ограничения и следующий шаг

1. Фактический выход — **токены-символы и времена их начала**, не слова.
   Длительности пусты; надёжных `endMs` слов нет. В #5 нельзя объявлять это
   `Timing.WORD`. Конец речевого окна не равен концу последнего слова.
2. Контекст VAD проверен только как эксперимент. Пересекающиеся расширенные
   окна могут повторно распознать слова: в #5 нужно согласованно объединять
   окна либо удалять повторы; слепое склеивание текста недопустимо.
3. Нет пунктуации и восстановления регистра. WER, разговорная речь и точность
   границ по ручному эталону не измерены. Чистое чтение не заменяет встречи.
4. Проба держит WAV и VAD-фрагменты в памяти, принимает не более 120 с.
   Это не production-реализация `SpeechRecognizer` для длинных записей.
5. JNI-вызов `decode` здесь не прерывается. В #5/#8 проверять отмену между
   этапами/фрагментами и освобождать stream/VAD/recognizer через finally.
   Проверки отмены и ошибок JNI в этой пробе не реализованы.
6. Не подтверждены arm64, реальный телефон, расход батареи и длительная работа.
   Проверка диаризации намеренно отложена пользователем; #2 не закрывается.

Основной путь для дальнейшей проверки #5: готовый Android AAR, вызовы из Java,
GigaAM v2 int8 и Silero VAD с сохранением исходной шкалы времени. Перед
интеграцией общей зависимости требуется согласование с потребителями.

## Проверки кода

- Проба: `assembleDebug`, `lintDebug` — успешно. Lint: 0 ошибок, 7 предупреждений
  о версиях AGP/Gradle, отсутствующей иконке и нелокализованных сообщениях
  диагностического экрана. Исходные версии инструментов проекта сохранены.
- Основное приложение после обновления ветки до `main` (`74c95fa`):
  `testDebugUnitTest assembleDebug lintDebug` — успешно,
  12 JVM-тестов, 0 ошибок. Локальные проверки выполнены установленным Gradle
  8.14.4; wrapper обновлён в main до 8.14.6 и проверяется отдельно в CI.
- `probe.py verify`: 7 отчётов прошли проверки токенов, времён,
  офлайн-повтора, тишины, смещения и VAD-контекста.
- Просмотрен итоговый diff; `git diff --cached --check` — без ошибок.

## Лицензии и атрибуция

- sherpa-onnx: [Apache-2.0, v2.0.1](https://github.com/k2-fsa/sherpa-onnx/blob/v2.0.1/LICENSE).
- GigaAM: [MIT, GigaChat Team](https://github.com/salute-developers/GigaAM/blob/7447938d791c4f3e643386ee22c33777004293a5/LICENSE).
  В архиве модели `LICENSE` оказался HTML-страницей GitHub, поэтому он сам
  по себе не является корректным текстом атрибуции. При поставке приложения
  включить настоящий текст лицензии по ссылке и указать конкретный чекпойнт.
- Silero VAD: [MIT, Silero Team](https://github.com/snakers4/silero-vad/blob/master/LICENSE).
- ONNX Runtime внутри AAR: [MIT, Microsoft](https://github.com/microsoft/onnxruntime/blob/main/LICENSE);
  при распространении проверить и включить third-party notices выбранной сборки.
- Для `rec_0055` сохранять атрибуцию mexus, источников речи и MUSAN,
  перечисленную в README тестового набора. Бинарные модели и аудио эта ветка
  не распространяет.
