# Changes vs upstream Xelgo

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

## Docs

- Russian README focused on 2026.x install and usage.
- `docs/INSTALL-EDT-2026.md` — macOS shared p2 / `bundles.info` procedure.
- `NOTICE` — EPL attribution to Xelgo.
