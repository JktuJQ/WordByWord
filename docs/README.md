# Документация WordByWord

Приложение импортирует аудио, локально распознаёт речь и участников, сохраняет стенограмму и экспортирует JSON.
Основная инструкция установки и сборки — в [README проекта](../README.md).

## Материалы для сдачи

| Документ | Содержание |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Компоненты и путь данных |
| [PROTOCOL.md](PROTOCOL.md) | Входное аудио, внешний JSON и внутренние контракты |
| [LIMITATIONS.md](LIMITATIONS.md) | Ограничения готовой версии и непроверенные условия |
| [TEST_RESULTS.md](TEST_RESULTS.md) | Сводка измерений, условия и ссылки на методику |
| [build.md](build.md) | Сборка, APK, контрольная сумма, установка и приёмка |
| [hackathon.md](hackathon.md) | Требования организаторов и открытые вопросы |

## Подробности реализации

| Область | Документы |
| --- | --- |
| Обработка и ресурсы | [processing.md](processing.md), [contracts.md](contracts.md) |
| Модели | [asr-models.md](asr-models.md), [asr-quality.md](asr-quality.md), [diarization-models.md](diarization-models.md) |
| Хранение | [storage.md](storage.md) |
| Сборочные проверки | [ci.md](ci.md) |
| Датасеты и команды оценки | [testing/evaluation.md](testing/evaluation.md) |
| Исторический отчёт сборки | [testing/build-2026-11-07.md](testing/build-2026-11-07.md) |
| Интерфейс и история дизайна | [app-shell.md](app-shell.md), [pdf-ui.md](pdf-ui.md), [ui-refresh.md](ui-refresh.md) |

## Соответствие шаблону организаторов

В [шаблоне](https://github.com/grigorevmp/hacathon_template) документация находится в `docs/`, исходники — в `src/`, конфигурация — в `config/`, материалы проверки — в `artifacts/`.
В Android-проекте исходники остаются в [app/src/](../app/src/), сборочные настройки — в [app/build.gradle](../app/build.gradle), [gradle.properties](../gradle.properties) и [flake.nix](../flake.nix).
Приложение не читает `config/example.env`; фиктивный файл с неиспользуемыми параметрами не добавлен.
Готовые APK создаются в [artifacts/apk/](../artifacts/apk/README.md), а описания наборов и лицензии остаются рядом с [данными evaluation](../evaluation/fixtures/).
Видеодемонстрация и опубликованный APK требуются для сдачи отдельно; наличие документации не подтверждает их публикацию.

## Перенос материалов

Подробный бывший `evaluation/README.md` перенесён в `docs/testing/evaluation.md`, а на старом пути сохранён указатель для существующих ссылок.
Отчёт `artifacts/test-results/build-2026-11-07.md` перенесён в `docs/testing/build-2026-11-07.md` с сохранением содержания и исправлением относительной ссылки.
Общий чек-лист `artifacts/test-results/README.md` объединён с `TEST_RESULTS.md` и `build.md`; дублирующий файл удалён.
Технические инструкции `artifacts/apk/README.md` объединены с `build.md`; рядом с APK сохранён короткий указатель.
Рабочий `AGENTS.md`, README проекта, описания источников, исходные README датасетов и лицензии не удалены.
