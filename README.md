# EDT Form Update (fork for EDT 2026.1 / 2026.2)

Публичный форк плагина [Xelgo/edt-form-update-plugin](https://github.com/Xelgo/edt-form-update-plugin)
с доработками под актуальные **1C:EDT 2026.1** и **2026.2**.

Upstream давно без релизов; на EDT 2026.2 стоковый плагин либо не резолвится
(OSGi Import-Package), либо поиск форм падает с
`AssertionError: Cyclic resolution of lazy links` → «No updatable forms».

Лицензия исходника: **Eclipse Public License 2.0** (см. `LICENSE`, `NOTICE`).

## Что делает плагин

В меню EDT: **Проект → Обновить формы расширения**.

1. Выбор проекта расширения в workspace.
2. Поиск adopted-форм, у которых форма в основной конфигурации изменилась.
3. Таблица с выбором → пакетный **Update**.
4. После каждого Update — выравнивание id Form → BaseForm (анти-segfault
   `compileexttocfe --ibcmd`): platform URI + refresh + retry; оставшийся
   crash-pattern → **Failed**. Агент на диске всё равно гоняет
   `post_form_update_hygiene.py` (Mac shortcuts + id + orphan).

## Доработки этого форка

| Проблема | Решение |
|----------|---------|
| EDT 2026.2: `bm.integration` / `dt.core.platform` / `compare.*` вне Import-Package | Расширены диапазоны в `META-INF/MANIFEST.MF` |
| `ExtensionEqualityHelper` → Cyclic lazy links → пустой список | Fallback: сравнение `BaseForm/Form.form` с parent `Form.form` на диске |
| EMF «разные», EDT compare пустой/висит | Тоже подтверждение через workspace-файлы |
| После Update Form id `100000x`, BaseForm `1..N` → segfault ibcmd | `FormIdConsistency`: копирует id атрибутов/команд Form → BaseForm |
| После Update: Adopted ТЧ + Own-реквизит уже из родителя → дубль CFE | `ParentAttributeAdoptFix`: Own→Adopted shell по uuid родителя |
| После Update: ссылки на команды/роли/ФО, которых нет в CFE → ibcmd/Jenkins | `ExtensionMissingRefCleaner`: вырезает незаимствованные refs из Form/BaseForm |
| Список снова полный после смены `-data` / ручной чистки BaseForm | Маркеры в `.settings/…/parent-sync` + кнопка **Accept (no Update)** |

Проверено: EDT **2026.2**, пакетный Update (~129 форм), `Updated: N. Failed: 0`.

## Быстрая установка (macOS, EDT 2026.2)

Самый надёжный путь на shared p2 — jar в `~/.p2/pool/plugins` + строка в
`bundles.info` (одного `dropins/` часто мало).

Подробно: **[docs/INSTALL-EDT-2026.md](docs/INSTALL-EDT-2026.md)**.

Кратко:

1. Закройте EDT.
2. Возьмите jar из [Releases](../../releases) (или соберите сами).
3. Положите в `~/.p2/pool/plugins/ru.xelgo.edt.formupdate.ui_<version>.jar`.
4. В `…/1C_EDT 2026.2/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`
   добавьте строку на **новый** Bundle-Version; старые `ru.xelgo.edt.formupdate` уберите.
5. Запустите EDT с `-data` = ваш workspace (проекты конфигурации + расширения).
6. Критерий: меню **Проект → Обновить формы расширения**; в `.log` нет
   `BundleException` по `formupdate`.

Install New Software с update-site upstream на 2026.2 часто **не** встаёт
из‑за узких Import-Package — используйте jar этого форка.

## Использование

1. Workspace с проектом расширения (например `Ортикон.ЦФГ`) и parent-конфигурацией.
2. Дождитесь конца активации проектного контекста.
3. **Проект → Обновить формы расширения**.
4. Выберите расширение → дождитесь списка (не «No updatable»).
5. **Select all** (если нужны все) → **Update**.
6. Ждите лог `Updated: N. Failed: 0.` (кнопки серые, пока идёт).
7. Закройте диалог.

После Update в git часто шум EDT (mass-adopt в `Configuration.mdo`, stub
объекты) — в коммит расширения кладите только Form/BaseForm и осмысленные
`.mdo` форм; см. практику в skill `edt-form-update` у операторов ЦФГ.

### Ограничения

- Тяжёлые формы иногда лучше обновлять через Конфигуратор + выгрузку в git.
- Id-align снимает crash-pattern Form/BaseForm, но **не** заменяет смысловую
  адаптацию контента от parent.
- После Update могут понадобиться adopt Enum/ФО, на которые ссылаются новые
  типы в формах (иначе `compileexttocfe` падает на XDTO `Type`).

## Сборка из исходников

Требования: JDK 17+, Maven 3.8+.

```bash
cd edt-form-update-plugin   # корень репозитория
mvn -DskipTests clean verify
```

Артефакты: update site / jars в `repositories/` и `bundles/…/target/`
(как в upstream). Для Mac shared p2 достаточно jar UI-бандла.

Версия qualifier подставляется Tycho; для ручной установки в `bundles.info`
удобнее зафиксировать Bundle-Version вида `1.0.2.vYYYYMMDDHHMM` (как в релизе).

## Совместимость

| EDT | Этот форк |
|-----|-----------|
| 2026.2 | да (основная цель) |
| 2026.1 | да (те же расширенные Import-Package) |
| 2025.x | возможно; ориентир — upstream Xelgo |

Java: **17** (Bundle-RequiredExecutionEnvironment).

## Связь с upstream

- Исходный проект: https://github.com/Xelgo/edt-form-update-plugin  
- Этот репозиторий — независимый публичный форк с патчами под EDT 2026.x.
- Pull request в upstream приветствуется, если автор возобновит поддержку.

## Документация

- [Установка на EDT 2026.1 / 2026.2 (macOS / Windows)](docs/INSTALL-EDT-2026.md)
- [Что изменено относительно Xelgo](docs/CHANGES.md)
- [NOTICE (атрибуция EPL)](NOTICE)
