"""
Minimal Android packaging tools (no aapt2 / Gradle needed).

- encode_axml(): compiles an XML element tree into Android binary XML (AXML)
- build_arsc():  writes a small resources.arsc for file-based resources
- write_apk():   writes an unsigned, 4-byte-aligned APK zip

Only the features this project needs are implemented, but the output follows
the same layout aapt2 produces (sorted attributes, resource map, etc.).
"""
import struct
import zipfile
import zlib

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Res_value data types
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12


# --------------------------------------------------------------------------
# String pool (UTF-16)
# --------------------------------------------------------------------------
def _string_pool(strings, utf8=False):
    offsets = []
    data = bytearray()
    for s in strings:
        offsets.append(len(data))
        if utf8:
            b = s.encode("utf-8")
            for n in (len(s), len(b)):
                if n > 0x7F:
                    data += bytes([(n >> 8) | 0x80, n & 0xFF])
                else:
                    data += bytes([n])
            data += b + b"\x00"
        else:
            u = s.encode("utf-16-le")
            n = len(u) // 2
            assert n < 0x8000
            data += struct.pack("<H", n) + u + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(data)
    flags = 0x100 if utf8 else 0
    out = struct.pack("<HHIIIIII", 0x0001, header_size, size, len(strings), 0,
                      flags, strings_start, 0)
    out += b"".join(struct.pack("<I", o) for o in offsets)
    out += bytes(data)
    return out


# --------------------------------------------------------------------------
# Binary XML
# --------------------------------------------------------------------------
class E:
    """Element: E(tag, attrs=[(ns, name, resid, type, value)], children=[])"""

    def __init__(self, tag, attrs=None, children=None):
        self.tag = tag
        self.attrs = attrs or []
        self.children = children or []


def A(name, typ, value, resid=None, ns=ANDROID_NS):
    """Attribute helper. For no-namespace attrs pass ns=None, resid=None."""
    return (ns, name, resid, typ, value)


def encode_axml(root, namespaces=(("android", ANDROID_NS),)):
    # Collect attribute names with resource IDs (these go first, sorted by ID)
    res_attrs = {}

    def walk(e):
        for ns, name, resid, typ, val in e.attrs:
            if resid is not None:
                res_attrs[name] = resid
        for c in e.children:
            walk(c)

    walk(root)
    res_names = sorted(res_attrs, key=lambda n: res_attrs[n])
    strings = list(res_names)
    index = {s: i for i, s in enumerate(strings)}

    def sidx(s):
        if s not in index:
            index[s] = len(strings)
            strings.append(s)
        return index[s]

    # Pre-register other strings in a stable order
    for prefix, uri in namespaces:
        sidx(prefix)
        sidx(uri)

    def reg(e):
        sidx(e.tag)
        for ns, name, resid, typ, val in e.attrs:
            if resid is None:
                sidx(name)
            if typ == TYPE_STRING:
                sidx(val)
        for c in e.children:
            reg(c)

    reg(root)

    NONE = 0xFFFFFFFF
    body = bytearray()
    line = [1]

    for prefix, uri in namespaces:
        body += struct.pack("<HHIIIII", 0x0100, 16, 24, line[0], NONE,
                            sidx(prefix), sidx(uri))

    def emit(e):
        line[0] += 1
        attrs = []
        for ns, name, resid, typ, val in e.attrs:
            ns_i = sidx(ns) if ns else NONE
            name_i = index[name]
            if typ == TYPE_STRING:
                raw = sidx(val)
                data = raw
            else:
                raw = NONE
                if typ == TYPE_INT_BOOLEAN:
                    data = 0xFFFFFFFF if val else 0
                else:
                    data = val & 0xFFFFFFFF
            sort_key = (0, resid) if resid is not None else (1, ns or "", name)
            attrs.append((sort_key, struct.pack("<IIIHBBI", ns_i, name_i, raw,
                                                8, 0, typ, data)))
        attrs.sort(key=lambda a: a[0])
        n = len(attrs)
        size = 16 + 20 + 20 * n
        body.extend(struct.pack("<HHIII", 0x0102, 16, size, line[0], NONE))
        body.extend(struct.pack("<IIHHHHHH", NONE, index[e.tag], 20, 20, n,
                                0, 0, 0))
        for _, a in attrs:
            body.extend(a)
        for c in e.children:
            emit(c)
        line[0] += 1
        body.extend(struct.pack("<HHIIIII", 0x0103, 16, 24, line[0], NONE,
                                NONE, index[e.tag]))

    emit(root)
    for prefix, uri in reversed(namespaces):
        body += struct.pack("<HHIIIII", 0x0101, 16, 24, line[0], NONE,
                            sidx(prefix), sidx(uri))

    pool = _string_pool(strings)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(res_names))
    resmap += b"".join(struct.pack("<I", res_attrs[n]) for n in res_names)
    total = 8 + len(pool) + len(resmap) + len(body)
    return struct.pack("<HHI", 0x0003, 8, total) + pool + resmap + bytes(body)


# --------------------------------------------------------------------------
# resources.arsc
# --------------------------------------------------------------------------
DENSITY_ANY = 0xFFFE
CONFIG_DENSITY = 0x0100
CONFIG_VERSION = 0x0400


def _config(density=0, sdk=0):
    c = bytearray(64)
    struct.pack_into("<I", c, 0, 64)
    struct.pack_into("<H", c, 14, density)
    struct.pack_into("<H", c, 24, sdk)
    return bytes(c)


def build_arsc(package_name, package_id, types):
    """
    types: ordered list of (type_name, [ (entry_name, density, sdk, path) ])
    Each entry has exactly one config here (enough for this app).
    Type IDs are assigned 1..n in order; entry IDs 0..m-1 in order.
    Returns (arsc_bytes, {"type/entry": resource_id}).
    """
    global_strings = []
    type_names = [t for t, _ in types]
    key_names = []
    ids = {}
    for ti, (tname, entries) in enumerate(types):
        for ei, (ename, density, sdk, path) in enumerate(entries):
            if ename not in key_names:
                key_names.append(ename)
            global_strings.append(path)
            ids[f"{tname}/{ename}"] = (package_id << 24) | ((ti + 1) << 16) | ei

    type_pool = _string_pool(type_names)
    key_pool = _string_pool(key_names)

    chunks = bytearray()
    for ti, (tname, entries) in enumerate(types):
        tid = ti + 1
        n = len(entries)
        # typeSpec
        spec = struct.pack("<HHIBBHI", 0x0202, 16, 16 + 4 * n, tid, 0, 0, n)
        for _ in entries:
            spec += struct.pack("<I", CONFIG_DENSITY | CONFIG_VERSION)
        chunks += spec
        # one ResTable_type chunk per distinct config
        configs = []
        for ename, density, sdk, path in entries:
            if (density, sdk) not in configs:
                configs.append((density, sdk))
        for density, sdk in configs:
            header_size = 20 + 64
            entries_start = header_size + 4 * n
            offsets = []
            edata = bytearray()
            for ename, d, s, path in entries:
                if (d, s) != (density, sdk):
                    offsets.append(0xFFFFFFFF)
                    continue
                offsets.append(len(edata))
                edata += struct.pack("<HHI", 8, 0, key_names.index(ename))
                edata += struct.pack("<HBBI", 8, 0, TYPE_STRING,
                                     global_strings.index(path))
            size = entries_start + len(edata)
            t = struct.pack("<HHIBBHII", 0x0201, header_size, size, tid, 0, 0,
                            n, entries_start)
            t += _config(density, sdk)
            t += b"".join(struct.pack("<I", o) for o in offsets)
            t += bytes(edata)
            chunks += t

    name16 = package_name.encode("utf-16-le")[:254]
    name16 += b"\x00" * (256 - len(name16))
    pkg_header_size = 288
    type_strings_off = pkg_header_size
    key_strings_off = type_strings_off + len(type_pool)
    pkg_size = key_strings_off + len(key_pool) + len(chunks)
    pkg = struct.pack("<HHII", 0x0200, pkg_header_size, pkg_size, package_id)
    pkg += name16
    pkg += struct.pack("<IIIII", type_strings_off, len(type_names),
                       key_strings_off, len(key_names), 0)
    assert len(pkg) == pkg_header_size
    pkg += type_pool + key_pool + bytes(chunks)

    gpool = _string_pool(global_strings, utf8=True)
    total = 12 + len(gpool) + len(pkg)
    arsc = struct.pack("<HHII", 0x0002, 12, total, 1) + gpool + pkg
    return arsc, ids


# --------------------------------------------------------------------------
# APK zip writer with 4-byte alignment of stored entries (like zipalign -p 4)
# --------------------------------------------------------------------------
def write_apk(path, entries):
    """entries: list of (name, bytes, store: bool)"""
    import time
    dt = (2008, 1, 1, 0, 0, 0)
    with open(path, "wb") as f:
        central = []
        for name, data, store in entries:
            nb = name.encode("utf-8")
            crc = zlib.crc32(data) & 0xFFFFFFFF
            if store:
                comp = data
                method = 0
            else:
                c = zlib.compressobj(9, zlib.DEFLATED, -15)
                comp = c.compress(data) + c.flush()
                method = 8
            offset = f.tell()
            extra = b""
            if store:
                data_start = offset + 30 + len(nb)
                pad = (-data_start) % 4
                extra = b"\x00" * pad
            dosdate = ((dt[0] - 1980) << 9) | (dt[1] << 5) | dt[2]
            dostime = 0
            f.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x0800, method,
                                dostime, dosdate, crc, len(comp), len(data),
                                len(nb), len(extra)))
            f.write(nb + extra + comp)
            central.append((nb, method, dostime, dosdate, crc, len(comp),
                            len(data), offset))
        cd_start = f.tell()
        for nb, method, t, d, crc, csize, usize, off in central:
            f.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20,
                                0x0800, method, t, d, crc, csize, usize,
                                len(nb), 0, 0, 0, 0, 0, off))
            f.write(nb)
        cd_size = f.tell() - cd_start
        f.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central),
                            len(central), cd_size, cd_start, 0))
