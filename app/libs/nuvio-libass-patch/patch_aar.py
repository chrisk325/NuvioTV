"""Puts the patched classes from `classes/` into app/libs/nuvio-libass-release.aar's classes.jar.

Usage: python3 -I patch_aar.py <classes dir> <aar>. Leaves every other entry as it is, and drops the
classes the patches replace with nothing; running it again changes nothing more.

It also sets the priority the libass workers in WORKER_PRIORITY start at: the `bipush` before their
`new HandlerThread(name, priority)` (-4, THREAD_PRIORITY_DISPLAY, as the AAR is built) gets the
operand WORKER_PRIORITY gives, an instruction of the same length, so nothing else in the class moves.
"""
import io
import os
import struct
import sys
import zipfile

REMOVED = {"com/nuvio/tv/libass/media/widget/AssCpuBlendSurfaceView$Companion.class"}

# bipush operands: THREAD_PRIORITY_DISPLAY (-4), THREAD_PRIORITY_DEFAULT (0), and nice 5, below the
# stream's loader (the default) and above THREAD_PRIORITY_BACKGROUND (10), from which Android also
# moves a thread to the background scheduling group. The app's LibassWorker.PRIORITY is the same 5.
DISPLAY, DEFAULT, BELOW_LOADER = 0xFC, 0x00, 0x05
PRIORITIES = (DISPLAY, DEFAULT, BELOW_LOADER)

# Class -> the name its libass worker HandlerThread is given, and the priority it starts at.
# libass's own render threads are started from the worker and take its priority. The Overlay worker
# starts below the loader, so heavy typesetting can't take the CPU the stream needs to keep
# buffering. The Video Effect worker stays at the default: the video's frame processing thread waits
# for the libass lock it holds while it renders, so a lower priority would hold up the video.
WORKER_PRIORITY = {
    "com/nuvio/tv/libass/media/widget/AtlasLibassThread.class": ("AssLibassThread", BELOW_LOADER),
    "com/nuvio/tv/libass/media/effect/AssVideoEffectAsyncProgram.class": ("AssRenderGL", DEFAULT),
}


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


def worker_priority(name, data, thread_name, priority):
    """`data` with its `new HandlerThread(thread_name, p)` given `priority` instead of `p`."""
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
    found = []
    for current in PRIORITIES:
        for load in loads:
            pattern = load + bytes([0x10, current, 0xB7]) + struct.pack(">H", init)
            at = data.find(pattern, end)
            while at >= 0:
                found.append(at + len(load) + 1)
                at = data.find(pattern, at + 1)
    if len(found) != 1:
        sys.exit(f"{name}: expected one {thread_name!r} HandlerThread, found {len(found)}")
    patched = bytearray(data)
    patched[found[0]] = priority
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
            data = worker_priority(info.filename, data, *WORKER_PRIORITY[info.filename])
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
