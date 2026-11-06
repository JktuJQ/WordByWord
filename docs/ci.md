# CI и сборки для команды

Workflow `Android CI` запускается для PR в `main`, push в `main` и вручную
через Actions → Android CI → Run workflow. Запуск вручную появится после
слияния workflow в основную ветку.

## Проверки

Job `Android build and checks` использует Ubuntu 24.04, Temurin JDK 21,
Android SDK API 36 и Build Tools 36.0.0. Gradle берётся из `./gradlew` и
проверяется по зафиксированной SHA-256; setup-gradle также проверяет wrapper JAR.

```sh
./gradlew --no-daemon --stacktrace --continue testDebugUnitTest assembleDebug lintDebug
```

`--continue` позволяет собрать остальные результаты, если отдельная проверка
упала; итоговый job всё равно будет неуспешным. Отчёты загружаются также при
ошибке сборки, если они успели появиться. Старый запуск одного PR или ветки
отменяется при новом push.

Кеш Gradle использует GitHub Actions cache. В основной ветке кеш обновляется;
в PR восстанавливается без записи. Секреты и ключи подписи не требуются.
Версии Actions зафиксированы commit SHA в YAML, без комментариев в коде.

AAR и модели диаризации (sherpa-onnx, пайаннот и 3D-Speaker — issue #6)
скачивает и раскладывает сама сборка — Gradle-таска `prepareDiarizationModels`
в `app/build.gradle`, навешенная на `preBuild`; отдельных шагов в workflow для
этого нет, только `actions/cache` на `app/build/diarization-downloads/`, чтобы
не качать те же ~90 МБ на каждый прогон. Сам AAR и модели в git не попадают
(`app/vendor/`, `app/src/main/assets/diarization/` — в `.gitignore`),
подробности — [docs/diarization-models.md](diarization-models.md).

CI устанавливает только SDK-компоненты, нужные для текущей Java-сборки.
Полное локальное окружение с NDK, эмулятором и инструментами остаётся в flake.
При изменении `compileSdk` или `buildToolsVersion` нужно согласованно обновить
`app/build.gradle`, `flake.nix` и шаг установки SDK в workflow. Если появится
сборка C/C++ из исходников, в CI потребуется добавить выбранные NDK/CMake.

## APK и отчёты

В успешном запуске Actions → Android CI → нужный run → Artifacts доступны:

- `wordbyword-debug-<run_number>-<run_attempt>` — отладочный APK;
- `android-reports-<run_number>-<run_attempt>` — результаты JVM-тестов, Android
  Lint и доступные отчёты Gradle.

Артефакты хранятся 14 дней. APK подписан стандартным отладочным ключом runner;
это сборка для ручной установки и демонстрации, не release-публикация.
Ключ runner не является постоянным: для обновления уже установленного APK
с другой подписью может понадобиться сначала удалить прежнее приложение.
Скачать можно через интерфейс GitHub или командой:

```sh
gh run download <run-id> --repo JktuJQ/WordByWord
```

Публикация в Releases/Google Play, постоянная подпись и автоматический rollout
в этот workflow не входят. Прогон эмулятора и измерения моделей на реальном
телефоне также остаются отдельными проверками.

## Работа через PR

1. Создать или выбрать Issue с границами и критериями готовности; назначить
   ответственного и перевести карточку Project в `In progress`.
2. Сделать отдельную ветку от актуального `main` и небольшой PR.
3. В PR описать результат, связанную Issue, реальные проверки и ограничения.
   `Closes #N` использовать только если задача закрывается целиком; иначе `Refs #N`.
4. После собственного просмотра diff передать PR на ревью другому участнику.
   Изменение общего контракта проверяет один из потребителей.
5. Перед слиянием проверить успешный `Android build and checks` и результаты ревью.
6. После слияния проверить запуск workflow на `main` и затронутый сценарий приложения.

Задачи #1 и #3 и координация MVP закреплены за лидером проекта. Технические
вопросы общих компонентов согласуются с их потребителями. Branch protection
этим изменением не настраивается; при его включении имя check для выбора —
`Android build and checks`.

Скорер WER `ScoreAsr` находится в `app/src/test/java/dev/wordbyword/evaluation` вместе с тестовыми Java-исходниками и не включается в APK. Датасет и manifest остаются в `test-data/asr`. Это позволяет Gradle и JDT LS использовать одинаковую структуру исходников. Для ручного запуска скорера нужен существующий Gson в classpath.
