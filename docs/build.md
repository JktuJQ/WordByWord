# Сборка, установка и проверка APK

## Без Nix

Самый короткий путь — открыть корень проекта в Android Studio, выбрать **Gradle JDK 21**, установить через SDK Manager **Android SDK Platform 36**, **Build Tools 36.0.0** и **Platform Tools**. Android Studio создаст `local.properties` с путём SDK. Для эмулятора дополнительно нужны Android Emulator и системный образ API 36. На Windows можно пользоваться Device Manager и кнопкой Run; в терминале Wrapper называется `gradlew.bat`.

Для Linux/macOS подойдут также [Android SDK Command-line Tools](https://developer.android.com/studio#command-tools) и JDK 21. Настройте `JAVA_HOME` на JDK и `ANDROID_HOME` на SDK; типовой путь Linux — `$HOME/Android/Sdk`, macOS — `$HOME/Library/Android/sdk`. Команды ниже рассчитаны на Bash и существующую установку Command-line Tools в `cmdline-tools/latest`:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
./gradlew --no-daemon --no-parallel --max-workers=1 :app:packageDemoApk
```

Первый запуск требует интернет для Gradle, Maven-зависимостей, AAR и моделей. `prepareAsrModels` и `prepareDiarizationModels` проверяют SHA-256 скачанных файлов и включают модели в APK. На устройстве скачивать или копировать модели отдельно не нужно. Проект использует Java/XML, AGP 8.13.2 и Gradle 8.14.6; NDK, CMake, Python и FFmpeg для этой APK не требуются — sherpa-onnx уже поставляется как AAR с JNI-библиотеками. Версии и источники моделей: [ASR](asr-models.md), [диаризация](diarization-models.md).

`packageDemoApk` создаёт в `artifacts/apk/` APK, файл `.sha256` и JSON с commit, признаком локальных изменений, вариантом сборки, ABI, настройкой параллелизма и контрольными суммами моделей. Повторная сборка той же версии заменяет эти три файла. Сохраните комплект под отдельным именем перед следующей сборкой, если он нужен для сравнения. Стандартный результат `assembleDebug` также остаётся в `app/build/outputs/apk/debug/app-debug.apk`.

По умолчанию включён параллельный инференс. Контрольная последовательная сборка: `./gradlew :app:packageDemoApk -PparallelInference=false`. Измерения — в [processing.md](processing.md). Debug-подпись зависит от локального `~/.android/debug.keystore`; для финальной release-поставки понадобится постоянный ключ, который не хранится в Git. При конфликте подписей перед удалением старого приложения экспортируйте нужные результаты: удаление стирает библиотеку.

## Эмулятор из терминала

Системный образ устанавливается средствами SDK, приложение собирается из этого репозитория. На x86-64 используйте команды ниже; на Apple Silicon замените `x86_64` на `arm64-v8a` в обеих строках с образом. Нужна аппаратная виртуализация; [требования Android Emulator](https://developer.android.com/studio/run/emulator-acceleration).

```sh
sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"
avdmanager create avd --name wordbyword-api36 --package "system-images;android-36;google_apis;x86_64"
emulator -avd wordbyword-api36 -memory 4096 -cores 2 -no-snapshot
```

На вопрос о custom hardware profile можно ответить `no`. Для экрана 1080×1920 / 420 dpi откройте Edit у этого AVD в Device Manager и настройте дисплей, либо измените `hw.lcd.width=1080`, `hw.lcd.height=1920`, `hw.lcd.density=420` в `~/.android/avd/wordbyword-api36.avd/config.ini` перед запуском.

После загрузки, в другом терминале с тем же SDK PATH:

```sh
adb -e install -r artifacts/apk/wordbyword-0.1.0-debug.apk
adb -e shell am start -n dev.wordbyword.app/.MainActivity
```

Если подключено несколько телефонов/эмуляторов, используйте `adb -s SERIAL` из `adb devices` вместо `-d` или `-e`. С Nix создание AVD заменяет `nix run .#start-emulator`. На машине с ограниченной RAM собирайте APK при выключенном эмуляторе.

## Приёмка на телефоне · issue #13

Передайте APK на 64-битный телефон с Android 8.0+, установите и включите режим полёта. Для проверки подойдут записи из [evaluation/fixtures/dialogs](../evaluation/fixtures/dialogs/SOURCE.md) и [diarization](../evaluation/fixtures/diarization/SOURCE.md); это собственные контрольные примеры, не экспертные записи организаторов.

1. Импорт → обработка → текст, участники и таймкоды → JSON с массивом `transcript` и полями `speaker_id`, `start_ms`, `end_ms`, `text`.
2. Нажатие на реплику → воспроизведение нужного момента; переименование участника → изменение всех его реплик.
3. Перезапуск приложения → повторное открытие записи и сохранённых имён; поворот/сворачивание → проверка состояния.
4. Отмена обработки, тишина и повреждённый файл → понятный результат, без падения и частично сохранённой стенограммы.

Запишите commit и SHA-256 из комплекта сборки, модель телефона, Android, RAM, файл и длительность, время обработки/RTF, память и наблюдаемые ошибки. JSON-гипотезы и отчёты храните в `artifacts/test-results/`; исходные метрики и правила оценки — в [evaluation/README.md](../evaluation/README.md). Не запускайте `measure-on-device.sh` на личной библиотеке: этот тестовый сценарий удаляет установленное приложение при выходе.

Issue #13 закрывается после реального офлайн-прогона и фиксации результатов. Для сдачи дополнительно нужны видео демонстрации и доступный команде APK: после проверки можно разместить комплект на Google Drive или в GitHub Release и добавить ссылку в README папки APK. Локальная папка или истекающий CI Artifact не заменяют доступный проверяющим комплект. Структура материалов следует [шаблону организаторов](https://github.com/grigorevmp/hacathon_template); исходный Android-модуль остаётся в `app/`.
