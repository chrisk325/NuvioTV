"""Puts the patched classes from `classes/` into app/libs/nuvio-libass-release.aar's classes.jar.

Usage: python3 -I patch_aar.py <classes dir> <aar>. Leaves every other entry as it is, and drops the
classes the patches replace with nothing; running it again changes nothing more.

It also starts the libass workers in WORKER_PRIORITY at the default priority: their bytecode's
`bipush -4` (THREAD_PRIORITY_DISPLAY) before `new HandlerThread(name, priority)` becomes `bipush 0`,
the same length, so nothing else in the class moves.
"""
import io
import os
import struct
import sys
import zipfile

REMOVED = {"com/nuvio/tv/libass/media/widget/AssCpuBlendSurfaceView$Companion.class"}

# Class -> the name its libass worker HandlerThread is given.
WORKER_PRIORITY = {
    "com/nuvio/tv/libass/media/widget/AtlasLibassThread.class": "AssLibassThread",
    "com/nuvio/tv/libass/media/effect/AssVideoEffectAsyncProgram.class": "AssRenderGL",
}
DISPLAY, DEFAULT = 0xFC, 0x00  # bipush operands: -4 and 0


def constant_pool(data):
    """The constant pool's entries by index, as (tag, value), and where the pool ends."""
    count = struct.unpack_from(">H", data, 8)[0]
    pool, at, index = {}, 10, 1
    while index < count:
        tag = data[at]
        if tag == 1:
            length = struct.unpack_from(">H", data, at + 1)[0]
            pool[index] = (1, data[at + 3:at + 3 + length].decode("utf-8", "replace"))
            at += 3 + length
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack_from(">H", data, at + 1)[0])
            at += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, struct.unpack_from(">HH", data, at + 1))
            at += 5
        elif tag in (3, 4):
            at += 5
        elif tag in (5, 6):
            at += 9
            index += 1
        elif tag == 15:
            at += 4
        else:
            sys.exit(f"unknown constant pool tag {tag}")
        index += 1
    return pool, at


def worker_priority(name, data, thread_name):
    """`data` with its `new HandlerThread(thread_name, -4)` given priority 0 instead."""
    pool, end = constant_pool(data)
    utf8 = lambda i: pool[i][1]
    strings = [i for i, (tag, v) in pool.items() if tag == 8 and utf8(v) == thread_name]
    inits = []
    for i, (tag, v) in pool.items():
        if tag != 10:
            continue
        owner, name_and_type = v
        method, descriptor = pool[name_and_type][1]
        if utf8(pool[owner][1]) == "android/os/HandlerThread" and utf8(method) == "<init>" \
                and utf8(descriptor) == "(Ljava/lang/String;I)V":
            inits.append(i)
    if len(strings) != 1 or len(inits) != 1:
        sys.exit(f"{name}: no single {thread_name!r} HandlerThread to patch")
    string, init = strings[0], inits[0]
    loads = [bytes([0x13]) + struct.pack(">H", string)]
    if string < 256:
        loads.append(bytes([0x12, string]))
    found = {}
    for priority in (DISPLAY, DEFAULT):
        for load in loads:
            pattern = load + bytes([0x10, priority, 0xB7]) + struct.pack(">H", init)
            at = data.find(pattern, end)
            while at >= 0:
                found.setdefault(priority, []).append(at + len(load) + 1)
                at = data.find(pattern, at + 1)
    display, default = found.get(DISPLAY, []), found.get(DEFAULT, [])
    if len(display) + len(default) != 1:
        sys.exit(f"{name}: expected one {thread_name!r} HandlerThread, found {len(display) + len(default)}")
    if default:
        return data
    patched = bytearray(data)
    patched[display[0]] = DEFAULT
    return bytes(patched)


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
        data = patched.get(info.filename, jar_in.read(info))
        if info.filename in WORKER_PRIORITY:
            data = worker_priority(info.filename, data, WORKER_PRIORITY[info.filename])
        out.writestr(info, data, compress_type=info.compress_type)
    missing = (set(patched) | set(WORKER_PRIORITY)) - names
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
