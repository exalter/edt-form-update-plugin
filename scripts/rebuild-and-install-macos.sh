#!/usr/bin/env bash
# Rebuild formupdate.ui from sources against EDT 2026.2 jars → ~/.p2/pool + bundles.info.
# EDT must be restarted after install.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/bundles/ru.xelgo.edt.formupdate.ui/src"
JAVA_HOME="${JAVA_HOME:-$HOME/Library/Application Support/1C/jvms/liberica-full-25.0.5+11-x86_64/jdk-25.0.5-full.jdk}"
export PATH="$JAVA_HOME/bin:$PATH"
VER="${1:-1.0.2.v$(date +%Y%m%d%H%M)}"
P2="$HOME/.p2/pool/plugins"
BASE_JAR="$(ls -t "$P2"/ru.xelgo.edt.formupdate.ui_1.0.2.*.jar | head -1)"
BI="$HOME/Library/Application Support/1C/1cedtstart/installations/1C_EDT 2026.2/1cedt.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
WORK="$(mktemp -d)"
python3 - "$BASE_JAR" "$WORK/cp.txt" <<'PY'
import sys
from pathlib import Path
base, out = sys.argv[1], Path(sys.argv[2])
dirs = [Path.home()/".p2/pool/plugins",
 Path.home()/"Library/Application Support/1C/1cedtstart/installations/1C_EDT 2026.2/1cedt.app/Contents/Eclipse/plugins"]
prefs = ("com._1c.g5.","org.eclipse.core.","org.eclipse.equinox.","org.eclipse.osgi","org.eclipse.jface",
 "org.eclipse.swt","org.eclipse.ui","org.eclipse.e4.","org.eclipse.emf.","org.eclipse.xtext","com.google.guava","org.eclipse.text")
jars=[]; seen=set()
for d in dirs:
  if d.is_dir():
    for j in sorted(d.glob("*.jar")):
      if any(j.name.startswith(p) for p in prefs) and j.name not in seen:
        seen.add(j.name); jars.append(str(j))
out.write_text(":".join([base] + jars))
print("classpath jars", len(jars)+1)
PY
mkdir -p "$WORK/classes" "$WORK/jarpack"
# shellcheck disable=SC2046
javac --release 17 -cp "$(cat "$WORK/cp.txt")" -d "$WORK/classes" $(find "$SRC/ru/xelgo/edt/formupdate/ui" -name '*.java')
cd "$WORK/jarpack"
jar xf "$BASE_JAR"
cp -R "$WORK/classes/ru" .
# NLS properties live next to Java sources (not compiled)
find "$SRC/ru" -name '*.properties' -print0 | while IFS= read -r -d '' f; do
  rel="${f#"$SRC/"}"
  mkdir -p "$(dirname "$rel")"
  cp -f "$f" "$rel"
done

python3 - "$VER" <<'PY'
import re, sys
from pathlib import Path
ver = sys.argv[1]
p = Path("META-INF/MANIFEST.MF")
t = p.read_text()
t2, n = re.subn(r"Bundle-Version:\s*\S+", f"Bundle-Version: {ver}", t, count=1)
assert n == 1
p.write_text(t2)
PY
OUT="$P2/ru.xelgo.edt.formupdate.ui_${VER}.jar"
jar cfm "$OUT" META-INF/MANIFEST.MF .
mkdir -p "$ROOT/dist"
cp -f "$OUT" "$ROOT/dist/"
cp -f "$BI" "$BI.bak-before-formupdate-${VER}"
python3 - "$BI" "$VER" <<'PY'
from pathlib import Path
import sys
bi, ver = Path(sys.argv[1]), sys.argv[2]
lines = bi.read_text().splitlines(True)
out=[]; done=False
for line in lines:
  if line.startswith("ru.xelgo.edt.formupdate.ui,"):
    out.append(f"ru.xelgo.edt.formupdate.ui,{ver},../../../../../../../../../.p2/pool/plugins/ru.xelgo.edt.formupdate.ui_{ver}.jar,4,false\n")
    done=True
  else:
    out.append(line)
if not done:
  out.append(f"ru.xelgo.edt.formupdate.ui,{ver},../../../../../../../../../.p2/pool/plugins/ru.xelgo.edt.formupdate.ui_{ver}.jar,4,false\n")
bi.write_text("".join(out))
print("installed", f"ru.xelgo.edt.formupdate.ui_{ver}.jar")
print("Restart EDT 2026.2 to load the new bundle.")
PY
