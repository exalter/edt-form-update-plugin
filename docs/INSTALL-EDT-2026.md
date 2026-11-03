# Установка на 1C:EDT 2026.1 / 2026.2

## macOS (рекомендуемый путь: p2 pool + bundles.info)

На Mac EDT часто использует **shared p2**. Папки `dropins/` недостаточно:
бандл должен попасть в `~/.p2/pool/plugins` и в `bundles.info` конкретной
установки.

### 1. Закройте EDT

Полностью завершите процесс `1cedt` (не оставляйте зомби после crash).

### 2. Jar

Скачайте `ru.xelgo.edt.formupdate.ui_*.jar` из Releases этого репозитория
или соберите Maven-ом.

Положите файл сюда:

```text
~/.p2/pool/plugins/ru.xelgo.edt.formupdate.ui_1.0.2.vYYYYMMDDHHMM.jar
```

**Важно:** Bundle-Version в MANIFEST внутри jar должен быть **новым**
(дата/время). Иначе OSGi кэш держит старый unresolved бандл.

### 3. bundles.info

Файл (пример для 2026.2):

```text
~/Library/Application Support/1C/1cedtstart/installations/1C_EDT 2026.2/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
```

1. Удалите все строки с `ru.xelgo.edt.formupdate`.
2. Добавьте одну строку (пути подставьте свои; `file:` URL, версия = из имени jar):

```text
ru.xelgo.edt.formupdate.ui,1.0.2.v202611031200,file:/Users/<you>/.p2/pool/plugins/ru.xelgo.edt.formupdate.ui_1.0.2.v202611031200.jar,4,false
```

Формат: `symbolicName,version,location,startLevel,markedAsStarted`.

### 4. Workspace

Запускайте EDT с `-data` на workspace, где есть **и** конфигурация, **и**
расширение (например git `…/orticon конфигурация` с проектами `Ортикон` +
`Ортикон.ЦФГ`). Workspace только с базой без расширения →
`No extension projects`.

### 5. Проверка

1. Help / About / Installation Details — бандл `ru.xelgo.edt.formupdate.ui`
   в состоянии Resolved/Active (или смотрите `.log`).
2. Меню **Проект → Обновить формы расширения**.
3. В `<workspace>/.metadata/.log` нет `BundleException` / `Import-Package`
   unresolved по `formupdate`.
4. Диалог показывает список форм, не «No updatable».

Не форсируйте плагин через `-clean` «на всякий случай» — сначала новый
Bundle-Version + bundles.info.

### Zip с путями Windows

Если архив dropins собран на Windows, внутри могут быть `\`. На Mac
распаковывайте через Python `zipfile`, не слепой `unzip`.

## Windows

1. Закройте EDT.
2. Jar → `%USERPROFILE%\.p2\pool\plugins\` **или**
   `%LOCALAPPDATA%\1C\1cedtstart\installations\1C_EDT 2026.2\1cedt\dropins\edt-form-update\plugins\`.
3. При shared p2 — правка `bundles.info` у установки 2026.2 (как на Mac).
4. Перезапуск EDT → меню **Проект → Обновить формы расширения**.

Альтернатива: Help → Install New Software → Add → локальный update-site zip
из сборки Maven (`repositories/…`), если Import-Package резолвятся.

## Использование после установки

1. Дождитесь активации проектного контекста.
2. **Проект → Обновить формы расширения**.
3. Выберите расширение → список кандидатов.
4. Отметьте формы (Select all при необходимости) → **Update**.
5. Дождитесь итога в логе. `Failed` по id crash-pattern — сигнал дожать
   hygiene на диске (не «плагин сломан»).
6. **Обязательно** после диалога:
   `python3 …/edt-form-update/scripts/post_form_update_hygiene.py --cfe … [--cf …]`
   → exit 0. Без этого не коммитить Form/BaseForm.

## Типичные сбои

| Симптом | Что сделать |
|---------|-------------|
| Нет пункта меню | jar не в pool / нет строки в bundles.info / старый Bundle-Version |
| BundleException Import-Package | слишком узкий MANIFEST — нужен jar этого форка |
| No updatable / Cyclic links | старый jar без file-compare fallback |
| Секундный поиск, пусто | workspace без extension project или контекст ещё не готов |
| Segfault ibcmd после Update | `post_form_update_hygiene.py` / id-align; jar без platform-URI fix — пересобрать форк |
| Failed на строке после Update + «crash-pattern remains» | hygiene на git-диске; пересобрать/поставить jar с 2026-11-03 align |

## Снятие плагина

1. EDT закрыт.
2. Удалить jar из `~/.p2/pool/plugins`.
3. Убрать строки `ru.xelgo.edt.formupdate` из `bundles.info`.
4. Запуск EDT.
