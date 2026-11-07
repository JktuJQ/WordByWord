# Сборка и установка

## Android Studio

Откройте корень проекта и выберите Gradle JDK 21.
В SDK Manager установите Android SDK Platform 36, Build Tools 36.0.0 и Platform Tools.
Путь к SDK хранится в `local.properties`.
Для эмулятора также установите Android Emulator и системный образ API 36.
На Windows можно собрать и запустить приложение кнопкой Run.
Для командной строки используйте `gradlew.bat`.

## Командная строка без Nix

Установите JDK 21 и [Android SDK Command-line Tools](https://developer.android.com/studio#command-tools).
Укажите путь JDK в `JAVA_HOME`, путь SDK — в `ANDROID_HOME`.
Обычный путь SDK в Linux — `$HOME/Android/Sdk`, в macOS — `$HOME/Library/Android/sdk`.
Команды ниже рассчитаны на Bash и Command-line Tools в `cmdline-tools/latest`:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
./gradlew --no-daemon --no-parallel --max-workers=1 :app:packageDemoApk
```

Проект использует Java/XML, AGP 8.13.2 и Gradle 8.14.6.
Для первой сборки нужен интернет.
Задачи `prepareAsrModels` и `prepareDiarizationModels` скачивают модели, проверяют SHA-256 и включают их в APK.
Дополнительная установка моделей на телефон не требуется.
sherpa-onnx подключён как AAR с JNI-библиотеками, поэтому NDK и CMake для сборки не нужны.
Python и FFmpeg также не требуются.
Источники и версии моделей указаны в документации [ASR](asr-models.md) и [диаризации](diarization-models.md).

## Результат сборки

Задача `:app:packageDemoApk` записывает в `artifacts/apk/`:

- APK с debug-подписью;
- файл `.sha256` с контрольной суммой;
- JSON с commit, состоянием исходников, ABI, параметрами сборки и хешами моделей.

Повторная сборка той же версии заменяет эти файлы.
Стандартный результат `assembleDebug` находится в `app/build/outputs/apk/debug/app-debug.apk`.
Параллельная обработка включена по умолчанию.
Для последовательной сборки передайте `-PparallelInference=false`.
Измерения приведены в [processing.md](processing.md).

Debug-подпись использует ключ `~/.android/debug.keystore`.
Для release-сборки нужен постоянный ключ, который не хранится в Git.
Если подпись новой APK отличается, Android не разрешит обновить установленное приложение.
Перед удалением старой версии экспортируйте нужные результаты: удаление приложения стирает библиотеку.

## Эмулятор

Установите системный образ и создайте AVD.
Команды ниже используют x86-64.
На Apple Silicon замените `x86_64` на `arm64-v8a` в обеих строках с образом.
Требования к виртуализации приведены в [документации Android Emulator](https://developer.android.com/studio/run/emulator-acceleration).

```sh
sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"
avdmanager create avd --name wordbyword-api36 --package "system-images;android-36;google_apis;x86_64"
emulator -avd wordbyword-api36 -memory 4096 -cores 2 -no-snapshot
```

На вопрос о custom hardware profile ответьте `no`.
Параметры экрана можно задать в Device Manager или в `~/.android/avd/wordbyword-api36.avd/config.ini` при выключенном эмуляторе:

```ini
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
```

После загрузки Android установите и откройте приложение из другого терминала с тем же SDK PATH:

```sh
adb -e install -r artifacts/apk/wordbyword-0.1.0-debug.apk
adb -e shell am start -n dev.wordbyword.app/.MainActivity
```

При нескольких подключённых устройствах используйте `adb -s SERIAL`.
Список устройств выводит `adb devices`.
С Nix AVD создаёт и запускает команда `nix run .#start-emulator`.
На машине с ограниченной RAM собирайте APK при выключенном эмуляторе.

## Приёмка на телефоне

Установите APK на 64-битный телефон с Android 8.0+ и включите режим полёта.
Контрольные записи находятся в [dialogs](../evaluation/fixtures/dialogs/SOURCE.md) и [diarization](../evaluation/fixtures/diarization/SOURCE.md).

1. Импортируйте запись и запустите обработку.
2. Проверьте текст, участников и таймкоды.
3. Экспортируйте JSON и проверьте массив `transcript` с полями `speaker_id`, `start_ms`, `end_ms`, `text`.
4. Нажмите на реплику и проверьте позицию воспроизведения.
5. Переименуйте участника и проверьте остальные его реплики.
6. Перезапустите приложение и откройте сохранённую запись.
7. Проверьте поворот экрана, сворачивание и отмену обработки.
8. Проверьте тишину и повреждённый файл.

В отчёте укажите commit и SHA-256 APK, модель телефона, Android, RAM, файл и его длительность.
Отдельно запишите время обработки, RTF, память и обнаруженные ошибки.
Отчёты и JSON-гипотезы размещаются в `artifacts/test-results/`.
Правила оценки описаны в [evaluation/README.md](../evaluation/README.md).

`measure-on-device.sh` удаляет установленное приложение при выходе.
Не запускайте его на устройстве с библиотекой, которую нужно сохранить.
