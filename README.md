# EDT Form Update (форк под EDT 2026.1 / 2026.2)

Публичный форк [Xelgo/edt-form-update-plugin](https://github.com/Xelgo/edt-form-update-plugin)
с правками под **1C:EDT 2026.1** и **2026.2**.

Upstream давно без релизов. На EDT 2026.2 стоковый плагин либо не ставится
(OSGi Import-Package), либо поиск форм падает с
`AssertionError: Cyclic resolution of lazy links` и в диалоге «No updatable forms».

Лицензия исходника: **Eclipse Public License 2.0** (`LICENSE`, `NOTICE`).

## Зачем этот форк

Adopted-форма в расширении = слой **Form** (доработки расширения) + **BaseForm**
(снимок формы из основной конфигурации).

Старый путь EDT `updateAdopted` копировал в Form родителя целиком: в Конфигураторе
форма выглядела «вся в расширении», в git раздувались Form и stubs в `src/`.

Этот форк при **Update** так не делает.

## Что делает Update

Меню: **Проект → Обновить формы расширения**.

1. Ищет adopted-формы, у которых форма в основной конфигурации ушла вперёд
   относительно BaseForm (и маркеров sync).
2. По выбранным строкам:
   - копирует parent `Form.form` → `BaseForm/Form.form` (EOL файла расширения
     сохраняется);
   - выравнивает id атрибутов/команд Form → BaseForm (иначе часто segfault
     `compileexttocfe --ibcmd`);
   - вырезает из Form/BaseForm form-level `excludedCommands`, ссылки на
     команды/роли/ФО, которых нет в расширении, и часть опасных `commandName`;
   - пишет sync-маркер только в workspace (не в дерево CFE).
3. Слой **Form** adopt’ом не пересобирает: handlers и осознанная дельта
   расширения остаются как были.
4. Crash-pattern id после align → строка **Failed**, не тихий Success.

## Чего Update не делает

- не вызывает `updateAdopted`;
- не ужимает уже толстый Form до «только обработчики» - чинит устаревший
  BaseForm и гигиену load, исторический жирный Form оставляет;
- не кладёт маркеры sync в git расширения.

Опциональный ужим Form до handlers-only - отдельный скрипт
(`thin_adopted_form_layer.py` в skill `edt-form-update`), не часть плагина.

После пакетного Update агент на диске всё равно гоняет
`post_form_update_hygiene.py` (Mac shortcuts, id, orphan, load-команды).

## Доработки форка

| Проблема | Решение |
|----------|---------|
| EDT 2026.2: `bm.integration` / `dt.core.platform` / `compare.*` вне Import-Package | Расширены диапазоны в `META-INF/MANIFEST.MF` |
| `ExtensionEqualityHelper` → Cyclic → пустой список | Fallback: сравнение `BaseForm` с parent `Form.form` на диске |
| EMF «разные», compare пустой/висит | То же подтверждение через файлы workspace |
| `updateAdopted` раздувает Form + stubs в `src/` | Update = sync BaseForm (`v20261108+`); Form adopt’ом не трогается |
| Form id `100000x`, BaseForm `1..N` → segfault ibcmd | `FormIdConsistency`: id Form → BaseForm |
| Adopted ТЧ + Own-реквизит с родителя → дубль CFE | `ParentAttributeAdoptFix`: Own→Adopted shell (хвосты старых jar) |
| Ссылки на команды/роли/ФО не из CFE | `ExtensionMissingRefCleaner` |
| form-level `excludedCommands` → «Неверное имя команды» | `ExtensionFormLevelExcludedCommandsCleaner` (item-level не трогает) |
| `commandName` CommonCommand / StandardCommand / orphan `Form.Command` | `ExtensionFormCommandNameCleaner` → `0` |
| Список снова полный после смены `-data` / ручной чистки | Маркеры в `.metadata/…/parent-sync` + **Accept (no Update)** |

Проверено: EDT **2026.2**, пакетный Update (~129 форм), `Updated: N. Failed: 0`.

## Быстрая установка (macOS, EDT 2026.2)

Надёжный путь на shared p2: jar в `~/.p2/pool/plugins` + строка в `bundles.info`
(одного `dropins/` часто мало).

Подробно: **[docs/INSTALL-EDT-2026.md](docs/INSTALL-EDT-2026.md)**.

1. Закройте EDT.
2. Jar из [Releases](../../releases) или своя сборка.
3. В `~/.p2/pool/plugins/ru.xelgo.edt.formupdate.ui_<version>.jar`.
4. В `…/1C_EDT 2026.2/…/bundles.info` - строка на новый Bundle-Version; старые
   `ru.xelgo.edt.formupdate` уберите.
5. EDT с `-data` = workspace (конфигурация + расширение).
6. Критерий: меню **Проект → Обновить формы расширения**; в `.log` нет
   `BundleException` по `formupdate`.

Install New Software с update-site upstream на 2026.2 часто не встаёт из‑за
узких Import-Package - берите jar этого форка.

## Использование

1. Workspace: расширение (например `Ортикон.ЦФГ`) и parent-конфигурация.
2. Дождаться конца активации проектного контекста.
3. **Проект → Обновить формы расширения**.
4. Выбрать расширение → список не «No updatable».
5. Нужные строки (или Select all) → **Update**.
6. Дождаться `Updated: N. Failed: 0.` (кнопки серые, пока идёт).
7. Закрыть диалог.

С `v20261108+` mass-adopt stubs и раздутый `Configuration.mdo` от Update не
ожидаются. В коммит - Form/BaseForm и осмысленные `.mdo`; см. skill
`edt-form-update`.

**Accept (no Update)** - записать workspace-маркеры без sync: форма пропала из
списка после ручной чистки BaseForm, пока parent снова не изменится.

### Ограничения

- Тяжёлые формы иногда проще через Конфигуратор и выгрузку в git.
- Id-align снимает crash-pattern, но не заменяет смысловую адаптацию контента.
- После Update могут понадобиться adopt Enum/ФО под новые типы в формах
  (иначе `compileexttocfe` падает на XDTO `Type`).
- Уже толстый Form плагин сам до handlers-only не ужимает.

## Сборка из исходников

JDK 17+, Maven 3.8+.

```bash
cd edt-form-update-plugin
mvn -DskipTests clean verify
```

Артефакты в `repositories/` и `bundles/…/target/`. Для Mac shared p2 достаточно
jar UI-бандла. Qualifier Tycho; в `bundles.info` удобнее
`1.0.2.vYYYYMMDDHHMM`.

## Совместимость

| EDT | Этот форк |
|-----|-----------|
| 2026.2 | да (основная цель) |
| 2026.1 | да |
| 2025.x | возможно; ориентир - upstream Xelgo |

Java: **17** (Bundle-RequiredExecutionEnvironment).

## Связь с upstream

- Исходник: https://github.com/Xelgo/edt-form-update-plugin
- Здесь - публичный форк с патчами под EDT 2026.x.
- PR в upstream приветствуется, если автор возобновит поддержку.

## Документация

- [Установка на EDT 2026.1 / 2026.2](docs/INSTALL-EDT-2026.md)
- [Что изменено относительно Xelgo](docs/CHANGES.md)
- [NOTICE (атрибуция EPL)](NOTICE)
