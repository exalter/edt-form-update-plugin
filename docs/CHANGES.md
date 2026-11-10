## 1.0.2.v20261109i — skip parent sync = no disk writes

When BaseForm←parent is skipped (hollow / adapted ЦФГ / Report / structural),
Update writes **only** the sync marker. No Form rebuild, no strip missing refs /
excludedCommands / commandName / shortcuts. Status: `Unchanged (marker only: …)`.
Fixes mass Update still dumping the same sanitize diff into git after v20261109h.

## 1.0.2.v20261109h — adapted forms: no silent rewrite / no export break

Operator expectation: after forms are already adapted, **Update must not
change Form/BaseForm** (and must not break `1cedtcli export`).

- **Detect/compare** (`FormUpdateSyncMarker.normalize`): also ignore
  `commandName` / `role` / `functionalOptions` / `CommonPicture.*` picture
  tags (same hygiene Update strips). Stops eternal re-list after sanitize
  cleared `commandName>0` or missing pictures while parent still has them.
- **BaseForm←parent skipped** when Form is hollow, Report/DataProcessor,
  has `EventHandlerExtension` / `ЦФГ_` / `Form*Extension` / names beyond
  BaseForm. Those rows get hygiene + sync **marker** only (Accept-class).
- **Unchanged (marker only)** when Form+BaseForm bytes identical after the
  pipeline — no git noise from empty sanitize passes.

## 1.0.2.v20261109g — Adopted Form button wiring after Update

- After Update (rebuild **and** keep-fat): strip plain
  `FormCommandHandlerContainer` actions from **Form** (parent Module procs —
  they stay on BaseForm). Keep ЦФГ `FormExtensionCommandHandlerContainer`.
- Restore `<commandName>Form.Command.*</commandName>` on buttons from the
  pre-Update Form overlay when missing (урок мфо_НачислениеРезервов:
  Form без commandName + action на Form → кнопки Заполнить/Подробности).

## 1.0.2.v20261109f — Update must not break export/compile

Contract: after **Update** the Adopted form stays loadable by `1cedtcli export`
and `compileexttocfe` — no manual git restore / post-scrub required for cases
the plugin itself created.

- **Hollow Form** (`<attributes>` count = 0): do **not** copy parent → BaseForm.
  Inflating a legacy empty Adopted shell caused Form hollow + BaseForm full
  (export NPE / IndexOutOfBounds) or both-full ReportAttribute /
  DataProcessorAttribute / XDTO Type on Reports/DataProcessors.
- **Form rebuild**: keep-fat only for `Form*Extension` item types and for
  item/attr `<name>` absent from BaseForm. Item-level `EventHandlerExtension` /
  `ЦФГ_*` handlers no longer block rebuild — they are **re-injected** into the
  matching `<items><name>…` after Form:=BaseForm+overlays.
- **HARD assert** after writes: Form attrs=0 and BaseForm attrs>5 → row Failed +
  workspace restore (never leave hollow/full mismatch).
- **Missing refs**: also strip `<picture>CommonPicture.X</picture>` when X is
  not adopted into the extension (XDTO Picture / `ЗагрузитьЧекиПоQRКоду`).

## 1.0.2.v20261109c — Mac shortcut normalize inside Update

- After BaseForm←parent (early + final HARD): rewrite `<shortcut>` bodies
  `Cmd+`/`Command+`/`Option+` → `Ctrl+`/`Alt+` (same as
  `normalize_form_shortcuts.py`). Fail the row if Mac tokens remain.
- Compare/sanitize and sync-marker normalize apply the same rewrite so parent
  still-on-Mac vs BaseForm-on-Ctrl does not eternal-relist or false
  «BaseForm partial».
- Orphan Adopted / thin overlay stay outside the plugin (project-wide /
  advisory).

## 1.0.2.v20261109b — excludedCommands strip: do not eat next-line indent

- Cleaner / sync-marker regex no longer uses `\s*` before EOL. Greedy `\s*`
  ate the next form-level line's leading spaces, so that line was no longer
  at `^` in the same Matcher pass and survived → «pattern gap», Updated:0 /
  Failed:N on reports (Cancel/Generate/… half-stripped).
- New: 0–2 spaces/tabs + tag + same-line spaces + one optional `\r\n`/`\n`/`\r`.
- Item-level (deeper indent) still untouched.

## 1.0.2.v20261109a — excludedCommands strip HARD after last align

- Form-level `excludedCommands` strip no longer soft-`return 0` on the final
  pass: row **Failed** + snapshot restore if clean throws or BaseForm still
  has form-level lines after write (ibcmd «Неверное имя команды»).
- Order: early strip right after BaseForm←parent, then refs/commandName/
  id-align, then **final strip** (EMF re-save was reintroducing Cancel/
  Generate/… when strip ran only before the second align).
- Cleaner regex: 0–2 spaces/tabs + line EOL (see v20261109b); verify on disk.
- Message still appends `stripped form-level excludedCommands=N`.

## 1.0.2.v20261109 — scan: disk BaseForm↔parent is authoritative

- Detect/list no longer gates workspace file compare on EMF
  `!baseEqual && sourceAdopted && freshBaseForm`. Stale CommonForms
  (ПанельОтчетов, ФормаОтчета, СправкаРасчет, ФормаНастроекОтчета) were
  skipped when EMF reported equal / adopt failed while BaseForm on disk
  lagged parent by 1k+ lines.
- Order: dual sync marker → `equalIgnoringIds` files → EMF only for Message /
  log. Outer check failure still tries file compare before skipping.
- Message extras: `BaseForm ≠ parent (files)`, `(files; EMF equal)`,
  `(check failed; files)`.

## 1.0.2.v20261108b — dual sync marker (parent + BaseForm)

- Sync marker stores `parent=` and `base=` SHA-256. Skip only when **both**
  still match disk. Git restore of BaseForm or parent release bump re-lists.
- Legacy single-line (parent-only) markers are not trusted alone — Update or
  Accept once with this jar to rewrite.

## 1.0.2.v20261108 - BaseForm-first Update (no updateAdopted)

Contract (RU): Update синхронизирует **BaseForm** с parent и чистит гигиену
load (id, excludedCommands, refs, commandName). Слой **Form** adopt’ом не
пересобирает и **не** ужимает уже толстый Form до handlers-only - только
перестаёт раздувать. Ужим Form - вне плагина (отдельный скрипт при нужде).

- Batch **Update** no longer calls EDT `updateAdopted`. Primary path =
  copy parent `Form.form` → extension `BaseForm` (EOL preserved), then
  id-align + missing-ref / excludedCommands / commandName strips + marker.
- Extension **Form** layer (handlers / intentional deltas) is not rebuilt -
  avoids Designer “whole form in extension” and mass-adopt stubs in `src/`.
- Removed `ExtensionAdoptSideEffectCleaner` from the Update happy-path (no
  post-batch stub rollback as SoT). `ParentAttributeAdoptFix` kept for
  leftover Own shells from older jars.
- Already-fat `Form.form` files are not auto-thinned; Update stops making
  them worse and refreshes BaseForm only.

## 1.0.2.v20261107d — crash-pattern HARD fail + preserve EOL + BaseForm partial

- After id-align (and again after missing-ref strip): remaining **crash-pattern**
  → row **Failed** (no soft Success that segfaults `ibcmd`).
- Form/BaseForm / missing-ref writes keep the file's previous LF/CRLF
  (`FormXmlIo`); BaseForm sync from parent keeps extension EOL.
- Message `Updated (BaseForm partial)` when BaseForm still ≠ parent after
  ignoring `<id>` and stripped commandName/role/FO.

## 1.0.2.v20261107c — auto-clean adopt stubs after batch Update

- Before batch: snapshot `src/**` file set + `Configuration.mdo`.
- After batch: delete **new** files under `src/` (updateAdopted stubs), restore
  `Configuration.mdo`, refresh project. Keeps modified Form/BaseForm that
  already existed. Log line: `side-effect cleanup: deletedNewFiles=…`.

## 1.0.2.v20261107b — markers workspace-only (never CFE tree)

- Revert project-local `.settings/ru.xelgo…/parent-sync/` write/read. Product
  extension repos must not get plugin side files (Ортикон.ЦФГ is a symlink
  into git). SoT = workspace
  `.metadata/.plugins/ru.xelgo.edt.formupdate.ui/parent-sync/` only.
- Changing EDT `-data` loses markers → Accept / Update again.

## 1.0.2.v20261107 — Accept without Update + scan reasons

- Scan checks marker **before** EMF `adopt` (fast skip; fewer Cyclic warnings).
- Message column shows why a form is listed (`BaseForm ≠ parent`, …).
- Dialog button **Accept (no Update)**: write **workspace** markers for
  selected forms without adopt — hand-cleaned Form/BaseForm stay hidden until
  parent `Form.form` changes.
- (Withdrawn) project-local markers under CFE `.settings/` — see v20261107b.

## 1.0.2.v20261106b — strip missing extension refs after Update

- After BaseForm sync + id-align: remove from Form and BaseForm XML
  `<commandName>` / `<role>` / `<functionalOptions>` that point at metadata
  **not** present in the extension project. Keep `Form.*` command names.
  Leave command-interface `<command>` rows alone.
- Index built once per batch from extension `src/**/*.mdo` (+ `Commands/`).
- Soft on IO; status message appends `stripped missing refs=N`.
- Parent sync marker still uses raw parent digest — sanitized BaseForm must
  not re-list forever.
- Case: MR orticon_cfe !1935 (`Контрагенты` SPARK/ПроверкаКонтрагента,
  unadopted FO/roles on document forms).

## 1.0.2.v20261106 — Own→Adopted for parent attributes after Update

- After each form Update: scan object `.mdo`. If `updateAdopted` borrowed an
  Adopted ТЧ but left an attribute as Own (type/synonym, no
  `objectBelonging=Adopted`) while the same name exists in the parent at that
  scope — rewrite to an Adopted shell
  (`extendedConfigurationObject` = parent attribute uuid +
  `BasicFeatureExtension` / Checked). Keeps the ТЧ; does not delete it.
- True extension-only Own attributes (not in parent) stay Own.
- Soft on missing parent `.mdo` / IO — form row still Updated.
- Case: `Documents/СведенияОбУдержанномНДФЛ` ТЧ `НДФЛ` / `ЦФГ_СчетУчета`
  (Jenkins CFE duplicate after form Update).

## 1.0.2.1.0.2.v202611031743 (relist fix)

- Update detector: when EMF BaseForm ≠ fresh adopt, decide only via sync-marker /
  equalIgnoringIds — never EDT compare alone (id-align made EMF always unequal).
- Sync marker: write+read both qualified and simple owner keys (compat after
  AccountingRegister.НФО disambiguation).

## 1.0.2.v202611031723

- Resolve parent Form.form by metadata type folder when the same object name
  exists twice (e.g. AccountingRegister.НФО vs ChartOfAccounts.НФО;
  Catalog vs InformationRegister нфо_УсловияТрансляции). Fixes the two leftover
  forms that always reappeared with parent: null (ambiguous lookup).

## 1.0.2.v202611031701

- Extension picker pre-selects `Ортикон.ЦФГ` (not `*_Тест`) — no typing in
  the filter (EN/RU keyboard layout traps for agents).

## 1.0.2.v202611031640

- Id-align after BaseForm sync is XML-first (EMF only if crash-pattern remains).
- Avoids EDT 2026.2 EMF save truncating BaseForm to 0 bytes mid-align.
- XML write via temp + replace; refuse empty content.

## 1.0.2.v202611031612

- BaseForm sync: missing parent Form.form is soft skip (Updated), not Failed.
- Id-align: on EMF Cyclic → XML Form→BaseForm id copy (same as hygiene script).
- Remaining crash-pattern after align → soft warn; row stays Updated (hygiene closes).
- Refresh parent folder after BaseForm overwrite (fewer out-of-sync ResourceException).
- Sync-marker + equalIgnoringIds unchanged: updated forms must not reappear solely due to id-align.

# Changes vs upstream Xelgo

## 2026-11-03 — wait for V8 extension projects after restart

- `selectExtensionProject`: poll `IV8ProjectManager` up to 90s (UI pump) so
  early menu open after restart does not show empty-workspace false negative.
- Clearer `NoExtensionProject` message (wait for context activation).

## 2026-11-03 — restore on AssertionError (cyclic lazy links)

- Batch Failed on `AssertionError: Cyclic resolution of lazy links` used to
  skip restore: catch was only `CoreException|RuntimeException`, then
  `finally` deleted the snapshot. Now catch `Throwable`, restore before
  cleanup, and re-restore in `finally` unless committed.

## 2026-11-03 — Cyclic on updateAdopted: retry + BaseForm file fallback

- `updateAdopted` often throws Cyclic (PictureRef/ColorRef) on EDT 2026.2+.
- Retry once after `refreshLocal`.
- Still Cyclic → restore snapshot → copy parent `Form.form` onto extension
  `BaseForm/Form.form` → platform-URI id-align → mark
  **Updated (BaseForm file fallback)** (extension Form layer kept).
- Batch log lines include `[Owner]` for Updated/Failed.

## 2026-11-03 — stop re-listing forms after successful Update

Root cause: after Update we id-align BaseForm → bytes ≠ parent; on Cyclic the
scan used raw file compare → same forms every run. Also `updateAdopted` often
left BaseForm content behind parent.

Fix:

- After every successful adopt (and on Cyclic fallback): **always** copy parent
  `Form.form` → `BaseForm`, then id-align.
- Write sync marker (parent SHA-256) in plugin state (not git).
- Workspace “needs update?”: marker current → skip; else compare XML **ignoring
  `<id>`** (not raw bytes).


Based on [Xelgo/edt-form-update-plugin](https://github.com/Xelgo/edt-form-update-plugin)
around commit `f3c3a69` (main).

## EDT 2026.2 OSGi

`bundles/ru.xelgo.edt.formupdate.ui/META-INF/MANIFEST.MF` — widened
`Import-Package` so the bundle resolves on EDT 2026.1/2026.2, including:

- `com._1c.g5.v8.bm.integration` → `[11.1.0,13.0.0)`
- `com._1c.g5.v8.dt.core.platform` → `[12.0.0,14.0.0)`
- broader ceilings for `compare.*`, `form.model`, `dt.common`, Guava, etc.

## Cyclic lazy links / empty candidate list

In `UpdateFormHandler`:

- Catch `AssertionError` / runtime failures from `ExtensionEqualityHelper.equals`
  (typical: `Cyclic resolution of lazy links` on Picture and related).
- Fallback: byte-compare extension `BaseForm/Form.form` to parent `Form.form`
  via workspace resources (`isFormUpdateRequiredByWorkspaceFiles`).
- If EMF reports unequal but EDT compare does not require update, still
  confirm with workspace files (avoids silent skip when compare is empty).

## Form / BaseForm id alignment

New `FormIdConsistency`:

- After each successful `updateAdopted`, copy attribute and form-command `id`
  from Form onto matching BaseForm elements and save BaseForm.
- Detects “crash pattern”: Form ids ≥ 1e6 while BaseForm keeps small parent ids
  (known trigger for `compileexttocfe --ibcmd` Segmentation fault).

### 2026-11-03 — align must stick (BM overwrite)

Earlier align used `URI.createFileURI` + a private `ResourceSetImpl`. EDT BM
later rewrote BaseForm with parent ids → git still showed crash-pattern after
`Updated: N. Failed: 0`.

Now:

- Load/save via **platform-resource URI** + `refreshLocal` before/after;
  one retry if crash-pattern remains.
- Remaining crash-pattern → **`CoreException`** → batch row **Failed** (not a
  silent warning). Operator/agent runs `post_form_update_hygiene.py`.

### 2026-11-03 — hang / 0-byte BaseForm on heavy forms

Batch could freeze on a large document form; EMF `save` truncates first, so a
hang left `BaseForm/Form.form` at 0 bytes and grayed Update/Close forever.

Now:

- `FormWorkspaceGuard`: snapshot Form+BaseForm before update; restore on
  failure / timeout; assert non-empty after `updateAdopted` and after id-align.
- `FormIdConsistency.saveResource`: backup bytes → save → restore if empty.
- Per-form timeout (default **180s**, `-Dru.xelgo.edt.formupdate.perFormTimeoutSec`);
  Failed + restore + continue next form.
- Close stays enabled during batch; Cancel requests stop after current form.

## Docs

- Russian README focused on 2026.x install and usage.
- `docs/INSTALL-EDT-2026.md` — macOS shared p2 / `bundles.info` procedure.
- `NOTICE` — EPL attribution to Xelgo.
