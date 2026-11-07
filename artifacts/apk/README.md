# APK

Локальные установочные комплекты создаёт команда из корня проекта:

```sh
./gradlew --no-daemon --max-workers=1 :app:packageDemoApk
```

- `wordbyword-0.1.0-debug.apk` — подписанный debug APK, Android 8.0+, ARM64/x86-64, модели включены.
- `wordbyword-0.1.0-debug.apk.sha256` — контрольная сумма.
- `wordbyword-0.1.0-debug.apk.json` — commit, состояние исходников, параметры сборки и модели.

Проверка в этой папке: `sha256sum -c wordbyword-0.1.0-debug.apk.sha256` (macOS: `shasum -a 256 -c …`). Установка и проверка: [docs/build.md](../../docs/build.md).

Бинарные комплекты игнорируются Git: [GitHub блокирует файлы больше 100 МиБ](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github). После проверки телефона APK, checksum и JSON можно положить на Google Drive или в GitHub Release и добавить ссылку сюда. Пока комплект доступен только локально; после клонирования APK нужно собрать. CI Artifact — временный способ обмена, срок хранения 14 дней.
