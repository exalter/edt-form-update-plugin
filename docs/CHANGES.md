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
