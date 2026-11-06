# Проверка диаризации

Про сам датасет (`rec_0055`/`rec_0273`, лицензии, атрибуции) — [SOURCE.md](SOURCE.md).
Этот файл — про инструменты проверки нашей диаризации на нём.

## Файлы

- `ScoreDiarization.java` — автономный DER-скорер (NIST-style, collar=0,
  с перекрытиями, оптимальное сопоставление говорящих один-к-одному).
  Запуск: `java ScoreDiarization.java reference.rttm hypothesis.rttm duration_seconds [processing_seconds]`.
  Не зависит от Android и от нашего кода диаризации — чистый Java, без внешних библиотек.
- `ScoreDiarizationTest.java` — самопроверка самого скорера (синтетические
  примеры с заранее известным DER + проверка «эталон против себя даёт 0»).
  **Не тестирует `SherpaOnnxSpeakerDiarizer`** — только корректность формул скорера.
  Запуск: `javac ScoreDiarization.java ScoreDiarizationTest.java && java -cp . ScoreDiarizationTest .`
- `evaluate.sh` — прогоняет **нашу настоящую диаризацию** (`SherpaOnnxSpeakerDiarizer`
  через `DiarizerCheckActivity`) на подключённом устройстве/эмуляторе и печатает DER
  по каждой записи. Это и есть реальная проверка качества, а не просто скорера.

## Как получить метрики по-настоящему

```sh
./gradlew assembleDebug           # если APK ещё не собран
adb devices                        # эмулятор или телефон должен быть виден
test-data/diarization/evaluate.sh                  # все записи по умолчанию (rec_0055 rec_0273)
test-data/diarization/evaluate.sh rec_0055          # только одна запись
```

Скрипт: ставит APK, копирует `.flac` в приватное хранилище приложения,
запускает `DiarizerCheckActivity` с `--es recording_id <id>` (активность
пишет гипотезу в `<cacheDir>/<id>.hyp.rttm`), ждёт файл, вытягивает его через
`adb exec-out run-as ... cat`, сравнивает со `.rttm`-эталоном через
`ScoreDiarization` и печатает DER/missed/false alarm/confusion/RTF.

Не часть CI — в проекте нет androidTest/эмулятора в CI (см. `docs/ci.md`).
Это ручной/разработческий прогон, как и `experiments/asr-probe/probe.py run`
для ASR (#2).

## Текущие результаты (эмулятор API 36, не физическое устройство)

| Запись | DER | Confusion | Missed | False alarm |
| --- | ---: | ---: | ---: | ---: |
| `rec_0055` (2 говорящих) | 27.97% | 14.31с | 1.28с | 2.21с |
| `rec_0273` (3 говорящих) | 13.54% | 4.60с | 2.15с | 1.10с |

Число говорящих определяется верно на обоих файлах; основная ошибка —
путаница говорящих (confusion) на отдельных участках. Подробный разбор и
принятые ограничения — карточка «Диаризация» в `AGENTS.md`.

## Задел на ASR

Когда появится проверочная активность для ASR (#5), для неё имеет смысл
такой же паттерн: активность пишет гипотезу в файл в приватном хранилище,
рядом — независимый от Android скорер (WER) и `evaluate.sh`-скрипт с тем же
циклом «push → run → pull → score». Не обязательно общий код с этим файлом —
достаточно того же паттерна.
