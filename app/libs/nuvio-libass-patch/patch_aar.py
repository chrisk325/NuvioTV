"""Puts the patched classes from `classes/` into app/libs/nuvio-libass-release.aar's classes.jar.

Usage: python3 -I patch_aar.py <classes dir> <aar>. Leaves every other entry as it is, and drops the
classes the patches replace with nothing; running it again changes nothing more.
"""
import io
import os
import sys
import zipfile

REMOVED = {"com/nuvio/tv/libass/media/widget/AssCpuBlendSurfaceView$Companion.class"}

classes, aar = sys.argv[1], sys.argv[2]
patched = {}
for root, _, files in os.walk(classes):
    for name in files:
        path = os.path.join(root, name)
        patched[os.path.relpath(path, classes).replace(os.sep, "/")] = open(path, "rb").read()

src = zipfile.ZipFile(aar)
jar_in = zipfile.ZipFile(io.BytesIO(src.read("classes.jar")))
jar = io.BytesIO()
with zipfile.ZipFile(jar, "w") as out:
    names = set()
    for info in jar_in.infolist():
        if info.filename in REMOVED:
            continue
        names.add(info.filename)
        out.writestr(info, patched.get(info.filename, jar_in.read(info)), compress_type=info.compress_type)
    missing = set(patched) - names
    if missing:
        sys.exit(f"not in classes.jar: {sorted(missing)}")
result = io.BytesIO()
with zipfile.ZipFile(result, "w") as out:
    for info in src.infolist():
        data = jar.getvalue() if info.filename == "classes.jar" else src.read(info)
        out.writestr(info, data, compress_type=info.compress_type)
src.close()
open(aar, "wb").write(result.getvalue())
print(f"patched {len(patched)} class(es) into {aar}")
