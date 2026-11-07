# Слово в слово · WordByWord

Android-приложение для расшифровки русскоязычных разговоров на устройстве.
Поддерживает импорт аудио, разделение по говорящим, таймкоды, прослушивание реплик и экспорт JSON.
Записи, стенограммы и имена участников сохраняются локально.
Модели включены в APK, подключение к интернету для обработки не требуется.

## Установка

Требования: Android 8.0+, ARM64 или x86-64.
APK находится в [artifacts/apk](artifacts/apk/README.md).
Передайте файл на телефон, откройте его и разрешите установку из выбранного источника.
Для установки через USB включите отладку и выполните:

```sh
adb devices
adb -d install -r artifacts/apk/wordbyword-0.1.0-debug.apk
```

В приложении импортируйте запись и нажмите «Обработать».
Установка и работа приложения на телефоне подтверждены владельцем.
Параметры устройства и измерения пока не записаны.
Порядок проверки — в [docs/build.md](docs/build.md#приёмка-на-телефоне).

## Сборка

Нужны Git, JDK 21, Android SDK Platform 36, Build Tools 36.0.0 и Platform Tools.
Gradle 8.14.6 запускается через Wrapper.
Зависимости и модели скачиваются при первой сборке.
Основные библиотеки: sherpa-onnx 2.0.1, Gson 2.15.0, Kotlin stdlib 2.2.0.

```sh
git clone https://github.com/JktuJQ/WordByWord.git
cd WordByWord
./gradlew --no-daemon --max-workers=1 :app:packageDemoApk
```

APK, контрольная сумма и сведения о сборке записываются в `artifacts/apk/`.
В Windows используйте `gradlew.bat`.
Настройка SDK и запуск эмулятора без Nix описаны в [docs/build.md](docs/build.md).

Сборка и запуск эмулятора с Nix:

```sh
nix develop --command ./gradlew --no-daemon --max-workers=1 :app:packageDemoApk
nix run .#start-emulator
```

На машине с ограниченной RAM собирайте приложение до запуска эмулятора.
AVD использует 4 ГБ RAM.

## Документация

- [Выполнение обработки](docs/processing.md), [контракты](docs/contracts.md), [хранение](docs/storage.md).
- [ASR и VAD](docs/asr-models.md), [диаризация](docs/diarization-models.md).
- [Измерения и тестовые записи](evaluation/README.md), [требования хакатона](docs/hackathon.md).

Текущая сборка подписана debug-ключом.
ASR возвращает таймкоды речевых фрагментов, а не отдельных слов.
Потребление памяти растёт с длительностью записи.
Незавершённая обработка после остановки процесса не восстанавливается.
