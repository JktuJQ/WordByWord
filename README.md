# Слово в слово · WordByWord

Android-приложение для локальной расшифровки разговоров: импорт аудио → распознавание русского текста → разделение по участникам → стенограмма с таймкодами → экспорт JSON. Есть прослушивание по реплике, переименование участников и сохранение записей. Аудио не отправляется на сервер; модели включены в APK.

## Установка

Нужен Android 8.0+ и 64-битное устройство ARM64 или x86-64. Папка готовых локальных сборок — [artifacts/apk](artifacts/apk/README.md). Перенесите APK на телефон, откройте его и разрешите установку из выбранного источника. Через USB с включённой отладкой:

```sh
adb devices
adb -d install -r artifacts/apk/wordbyword-0.1.0-debug.apk
```

Откройте «Слово в слово», импортируйте запись и нажмите «Обработать». Затем можно прослушать реплики, изменить имена участников и экспортировать JSON. Интернет после установки не нужен. Проверка текущего APK на физическом телефоне ещё предстоит — [issue #13](https://github.com/JktuJQ/WordByWord/issues/13).

## Сборка

Зависимости: Git, JDK 21, Android SDK Platform 36, Build Tools 36.0.0 и Platform Tools. Gradle 8.14.6 поставляется через Wrapper; sherpa-onnx 2.0.1, Gson 2.15.0, Kotlin stdlib 2.2.0 и модели скачиваются автоматически при первой сборке. Python, FFmpeg и Nix для сборки не нужны.

```sh
git clone https://github.com/JktuJQ/WordByWord.git
cd WordByWord
./gradlew --no-daemon --max-workers=1 :app:packageDemoApk
```

Результат — APK со всеми моделями, SHA-256 и сведения о сборке в `artifacts/apk/`. Для Windows используйте `gradlew.bat`. [Настройка SDK и запуск эмулятора без Nix](docs/build.md).

С Nix:

```sh
nix develop --command ./gradlew --no-daemon --max-workers=1 :app:packageDemoApk
nix run .#start-emulator
```

Сначала собирайте APK, затем запускайте эмулятор. По умолчанию AVD использует 4 ГБ RAM.

## Документация

- [Архитектура и выполнение](docs/processing.md), [контракты](docs/contracts.md), [хранение](docs/storage.md).
- [Модели ASR/VAD](docs/asr-models.md), [диаризация](docs/diarization-models.md), [измерения и тестовые записи](evaluation/README.md).
- [Установка, эмулятор и проверка на телефоне](docs/build.md), [требования хакатона](docs/hackathon.md).

Текущая поставка — подписанный debug APK. Таймкоды ASR относятся к речевым фрагментам; качество диаризации зависит от записи. Длинные записи требуют больше RAM, фон после завершения процесса не поддерживается. Перед сдачей остаются проверка на телефоне и видео демонстрации; результаты эмулятора её не заменяют.
