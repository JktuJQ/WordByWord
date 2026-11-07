# APK

Сборка из корня проекта:

```sh
./gradlew --no-daemon --max-workers=1 :app:packageDemoApk
```

Результат:

- `wordbyword-0.1.0-debug.apk` — debug APK для Android 8.0+, ARM64/x86-64, с моделями.
- `wordbyword-0.1.0-debug.apk.sha256` — контрольная сумма.
- `wordbyword-0.1.0-debug.apk.json` — commit, состояние исходников, параметры сборки и модели.

Проверка контрольной суммы из этой папки:

```sh
sha256sum -c wordbyword-0.1.0-debug.apk.sha256
```

В macOS используйте `shasum -a 256 -c wordbyword-0.1.0-debug.apk.sha256`.
Установка описана в [docs/build.md](../../docs/build.md).

Бинарные файлы исключены из Git.
[GitHub не принимает файлы больше 100 МиБ](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).
