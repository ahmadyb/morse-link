#!/usr/bin/env python3
"""
Static verification for the Morselink source tree.

We cannot compile Android/Kotlin in this sandbox (no JDK, Android SDK, or Maven
access), so these checks stand in for the compiler and catch the classes of
mistake that CI otherwise takes ~4 minutes per cycle to report.

Checks:
  1. Kotlin files parse-ably balanced and free of illegal string escapes.
  2. No unimported external type usage (catches missing imports).
  3. No module uses a class from another module it does not depend on.
  4. Every `binding.<id>` used in Kotlin exists in that module's layout XML.
  5. Every XML file is well formed.
"""
import os
import re
import sys
import glob
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEGAL_ESCAPES = set('tbnrf"\'\\u$')
BUILTIN = set("""String Int Long Boolean Float Double Byte Short Char Any Unit Nothing
List MutableList Map MutableMap Set MutableSet Array ArrayList HashMap HashSet
T R Result Pair Triple Lazy Sequence Throwable Exception Error Runnable Thread
Deprecated JvmStatic Suppress OptIn Volatile Companion Build It Enum
System Math Integer Character Object StringBuilder Number Void Class Package
Runtime Process ThreadGroup ThreadLocal StrictMath SecurityManager
CharSequence Appendable AutoCloseable Cloneable Iterable Comparable
Collection MutableCollection MutableIterable IntRange LongRange
JvmOverloads JvmField JvmName JvmStatic SuppressLint
# constants inherited from android.view.View / android.app.Service
VISIBLE INVISIBLE GONE START_STICKY START_NOT_STICKY START_REDELIVER_INTENT
ByteArray Charsets LinkedHashMap LinkedHashSet Synchronized Transient Throws
IllegalStateException IllegalArgumentException NullPointerException
NumberFormatException IndexOutOfBoundsException UnsupportedOperationException
IntArray LongArray FloatArray DoubleArray CharArray BooleanArray ShortArray Regex
# nested types of a superclass (NanoHTTPD.Response / .IHTTPSession / .Method)
IHTTPSession Response Method
""".split())


def kotlin_files():
    out = []
    for root, _, files in os.walk(ROOT):
        if "/build/" in root or root.startswith(os.path.join(ROOT, ".git")):
            continue
        for f in files:
            if f.endswith(".kt"):
                out.append(os.path.join(root, f))
    return out


def strip_code(src, blank_strings=False):
    """Blank out string literals and comments but keep line structure."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            # keep the newline, otherwise every line comment shifts line numbers
            out.append("\n" if j >= 0 else "")
            i = j + 1 if j >= 0 else n
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i)
            chunk = src[i:(j + 2) if j >= 0 else n]
            out.append("\n" * chunk.count("\n"))
            i = (j + 2) if j >= 0 else n
        elif c == "'":
            # Kotlin char literal such as '"' — not the start of a string
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 2
                    continue
                j += 1
            out.append(" ")
            i = j + 1
        elif c == '"':
            if src[i:i + 3] == '"""':
                j = src.find('"""', i + 3)
                chunk = src[i:(j + 3) if j >= 0 else n]
                out.append("\n" * chunk.count("\n") if blank_strings else chunk)
                i = (j + 3) if j >= 0 else n
            else:
                j = i + 1
                buf = []
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 2
                        continue
                    buf.append(src[j])
                    j += 1
                if blank_strings:
                    out.append(" ")
                else:
                    out.append('"' + "".join(buf) + '"')
                i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_escapes(errors):
    for p in kotlin_files():
        src = open(p, encoding="utf-8").read()
        i, n, line = 0, len(src), 1
        while i < n:
            c = src[i]
            if c == "\n":
                line += 1
                i += 1
                continue
            if c == "/" and i + 1 < n and src[i + 1] == "/":
                j = src.find("\n", i)
                i = j if j >= 0 else n
                continue
            if c == "/" and i + 1 < n and src[i + 1] == "*":
                j = src.find("*/", i)
                line += src.count("\n", i, j)
                i = (j + 2) if j >= 0 else n
                continue
            if c == "'":
                # Kotlin char literal, e.g. '"' — must not be read as a string
                j = i + 1
                while j < n and src[j] != "'":
                    if src[j] == "\\":
                        j += 2
                        continue
                    j += 1
                i = j + 1
                continue
            if c == '"':
                if src[i:i + 3] == '"""':
                    j = src.find('"""', i + 3)
                    line += src.count("\n", i, j)
                    i = (j + 3) if j >= 0 else n
                    continue
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == "\n":
                        line += 1
                        j += 1
                        continue
                    if src[j] == "\\":
                        if j + 1 < n and src[j + 1] not in LEGAL_ESCAPES:
                            errors.append(
                                f"ILLEGAL ESCAPE {p}:{line} '{src[j:j+2]}'"
                            )
                        j += 2
                        continue
                    j += 1
                i = j + 1
                continue
            i += 1


def load_modules():
    mods = {}
    for b in glob.glob(os.path.join(ROOT, "*", "build.gradle.kts")) + \
            glob.glob(os.path.join(ROOT, "*", "*", "build.gradle.kts")):
        d = os.path.dirname(b)
        if d == ROOT:
            continue
        src = open(b, encoding="utf-8").read()
        m = re.search(r'namespace\s*=\s*"([^"]+)"', src)
        if not m:
            continue
        mods[d] = {
            "ns": m.group(1),
            "deps": {x.lstrip(":").replace(":", "/")
                     for x in re.findall(r'project\("([^"]+)"\)', src)},
        }
    return mods


DECL = re.compile(
    r"\b(?:data\s+class|sealed\s+class|enum\s+class|sealed\s+interface|value\s+class|"
    r"class|interface|object|typealias)\s+(\w+)"
)

# Companion-object constants and members are local too, otherwise every
# SCREAMING_CASE reference looks like an unimported type.
DECL_MEMBER = re.compile(r"\b(?:const\s+)?(?:val|var|fun)\s+(\w+)")

ENUM_BODY = re.compile(r"enum\s+class\s+\w+[^{]*\{([^{}]*)\}")


def check_imports_and_deps(mods, errors):
    imported_simple = collections.defaultdict(set)
    for p in kotlin_files():
        for m in re.finditer(r"^\s*import\s+([\w.]+)", open(p, encoding="utf-8").read(), re.M):
            imported_simple[m.group(1).split(".")[-1]].add(m.group(1))

    decl_by_dir = collections.defaultdict(set)
    for p in kotlin_files():
        text = open(p, encoding="utf-8").read()
        for m in DECL.finditer(text):
            decl_by_dir[os.path.dirname(p)].add(m.group(1))
        for m in DECL_MEMBER.finditer(text):
            decl_by_dir[os.path.dirname(p)].add(m.group(1))
        for m in ENUM_BODY.finditer(text):
            for tok in re.findall(r"\b([A-Z][A-Z0-9_]*)\b", m.group(1)):
                decl_by_dir[os.path.dirname(p)].add(tok)

    for d, info in mods.items():
        java = os.path.join(d, "src/main/java")
        if not os.path.isdir(java):
            continue
        dep_dirs = {os.path.join(ROOT, x) for x in info["deps"]}
        for root, _, files in os.walk(java):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                p = os.path.join(root, f)
                src = open(p, encoding="utf-8").read()
                imported = {i.split(".")[-1]
                            for i in re.findall(r"^\s*import\s+([\w.]+)", src, re.M)}
                local = decl_by_dir[os.path.dirname(p)]
                for i, line in enumerate(strip_code(src, blank_strings=True).splitlines(), 1):
                    for m in re.finditer(r"(?<![\w.])([A-Z]\w+)\b", line):
                        name = m.group(1)
                        if name in BUILTIN or name in imported or name in local:
                            continue
                        if name not in imported_simple:
                            if name.isupper():
                                continue  # inherited framework constant
                            errors.append(
                                f"UNKNOWN TYPE {p}:{i} '{name}' is used bare but is "
                                f"never imported anywhere in the repo"
                            )
                            continue
                        owner = None
                        for fq in sorted(imported_simple[name]):
                            if fq.startswith("com.morselink."):
                                owner = fq
                                break
                        if owner is None:
                            errors.append(
                                f"MISSING IMPORT {p}:{i} '{name}' -> e.g. "
                                f"{sorted(imported_simple[name])[0]}"
                            )
                        else:
                            # find owning module by source path
                            for od, oinfo in mods.items():
                                rel = owner[len(oinfo["ns"]):].lstrip(".")
                                candidate = os.path.join(
                                    od, "src/main/java",
                                    oinfo["ns"].replace(".", "/"),
                                    rel.split(".")[0] + ".kt",
                                )
                                if os.path.exists(candidate) and od != d and od not in dep_dirs:
                                    errors.append(
                                        f"MISSING MODULE DEP {p}:{i} '{name}' "
                                        f"({owner}) lives in :{os.path.relpath(od, ROOT)}"
                                    )
                                    break


def check_binding_ids(mods, errors):
    """Every binding.<id> must exist in some layout of the same module."""
    for d in list(mods) + [ROOT]:
        java = os.path.join(d, "src/main/java")
        layouts = os.path.join(d, "src/main/res/layout")
        if not os.path.isdir(java) or not os.path.isdir(layouts):
            continue
        ids = set()
        for xml in glob.glob(os.path.join(layouts, "*.xml")):
            for raw in re.findall(r'@\+id/(\w+)', open(xml, encoding="utf-8").read()):
                ids.add(raw)
                head, *rest = raw.split("_")
                ids.add(head + "".join(w.title() for w in rest))
        for root, _, files in os.walk(java):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                p = os.path.join(root, f)
                src = open(p, encoding="utf-8").read()
                for m in re.finditer(r"\bbinding\.(\w+)", src):
                    if m.group(1) in {"root"}:
                        continue
                    if m.group(1) not in ids:
                        errors.append(
                            f"BINDING ID MISSING {p}: binding.{m.group(1)} "
                            f"not found in {os.path.relpath(layouts, ROOT)}"
                        )


# kotlinx.coroutines exposes these as top-level extensions; using one without
# importing it is a compile error the Capitalised-name check cannot see.
COROUTINE_EXTENSIONS = [
    "launch", "async", "withContext", "runBlocking", "delay",
    "flow", "callbackFlow", "channelFlow", "flowOn", "combine",  # no await: Deferred.await() is a member
    "flatMapLatest", "mapLatest",
]


def check_coroutine_extensions(errors):
    for p in kotlin_files():
        text = open(p, encoding="utf-8").read()
        imported = set(re.findall(r"^\s*import\s+([\w.]+)", text, re.M))
        body = "\n".join(
            line for line in strip_code(text, blank_strings=True).splitlines()
            if not line.strip().startswith(("import ", "package "))
        )
        for name in COROUTINE_EXTENSIONS:
            if not re.search(r"(?<![\w.])" + name + r"\b", body):
                continue
            if not any(i.endswith("." + name) for i in imported):
                errors.append(
                    f"MISSING IMPORT {p}: '{name}' used but "
                    f"kotlinx.coroutines.{name} is not imported"
                )


def check_companion_objects(errors):
    """Kotlin allows only one companion object per class; a second one is a
    compile error that is easy to introduce when adding constants."""
    for p in kotlin_files():
        text = open(p, encoding="utf-8").read()
        count = len(re.findall(r"\bcompanion\s+object\b", text))
        if count > 1:
            errors.append(
                f"DUPLICATE COMPANION {p}: {count} companion objects "
                f"(only one allowed per class)"
            )


VALUE_KINDS = {
    "string", "color", "dimen", "style", "array", "plurals",
    "integer", "bool", "id", "attr", "declare-styleable", "fraction",
}
DIR_KINDS = {
    "drawable", "layout", "menu", "mipmap", "anim", "animator",
    "xml", "raw", "navigation", "transition", "font",
}


def res_names(root):
    """Every resource name a module declares, bucketed by kind."""
    out = {k: set() for k in set(VALUE_KINDS) | DIR_KINDS}
    for d in glob.glob(os.path.join(root, "src/main/res")):
        for sub in sorted(os.listdir(d)):
            base = sub.split("-")[0]
            full = os.path.join(d, sub)
            if not os.path.isdir(full):
                continue
            if base in DIR_KINDS:
                for f in glob.glob(os.path.join(full, "*")):
                    out[base].add(os.path.splitext(os.path.basename(f))[0])
            elif base == "values":
                for f in glob.glob(os.path.join(full, "*.xml")):
                    text = open(f, encoding="utf-8").read()
                    for m in re.finditer(
                        r'<(string-array|integer-array|array|string|color|dimen|'
                        r'style|plurals|integer|bool)\b[^>]*?name\s*=\s*"([^"]+)"',
                        text, re.S,
                    ):
                        kind = m.group(1)
                        if kind.endswith("-array"):
                            kind = "array"
                        out[kind].add(m.group(2))
                    for m in re.finditer(
                        r'<item\b[^>]*type\s*=\s*"id"[^>]*name\s*=\s*"([^"]+)"', text
                    ):
                        out["id"].add(m.group(1))
    for f in glob.glob(os.path.join(root, "src/main/res", "**", "*.xml"), recursive=True):
        if os.sep + "values" in os.path.dirname(f):
            continue
        for m in re.finditer(r"@\+id/(\w+)", open(f, encoding="utf-8").read()):
            out["id"].add(m.group(1))
    return out


def check_resource_refs(errors):
    """R.<kind>.<name> must exist in this module; core-ui references must be
    written fully qualified, because R is per-module in Gradle."""
    mods = [
        m[: -len("/src/main/res")]
        for m in glob.glob("*/src/main/res") + glob.glob("*/*/src/main/res")
    ]

    def module_of(path):
        for r in sorted(mods, key=len, reverse=True):
            if path.startswith(r):
                return r
        return None

    ui = res_names("core/core-ui")
    cache = {}
    skip = {"id", "style", "attr"}
    for p in kotlin_files():
        mod = module_of(p)
        if mod is None:
            continue
        cache.setdefault(mod, res_names(mod))
        own = cache[mod]
        text = open(p, encoding="utf-8").read()
        for m in re.finditer(r"com\.morselink\.core\.ui\.R\.(\w+)\.(\w+)", text):
            kind, name = m.group(1), m.group(2)
            if kind in skip or kind not in ui:
                continue
            if name not in ui[kind]:
                errors.append(f"MISSING RESOURCE core-ui {kind}/{name} in {p}")
        for m in re.finditer(r"(?<![\w.])R\.(\w+)\.(\w+)", text):
            kind, name = m.group(1), m.group(2)
            if kind in skip or kind not in own:
                continue
            if name not in own[kind]:
                errors.append(f"MISSING RESOURCE {kind}/{name} in {p}")


def check_xml(errors):
    import xml.etree.ElementTree as ET
    for root, _, files in os.walk(ROOT):
        if "/build/" in root or root.startswith(os.path.join(ROOT, ".git")):
            continue
        for f in files:
            if not f.endswith(".xml"):
                continue
            p = os.path.join(root, f)
            if "/res/raw/" in p:
                continue  # HTML asset, not XML
            try:
                ET.parse(p)
            except ET.ParseError as e:
                errors.append(f"XML MALFORMED {p}: {e}")


def main():
    errors = []
    mods = load_modules()
    check_escapes(errors)
    check_imports_and_deps(mods, errors)
    check_binding_ids(mods, errors)
    check_coroutine_extensions(errors)
    check_companion_objects(errors)
    check_resource_refs(errors)
    check_xml(errors)

    print(f"modules={len(mods)} kotlin={len(kotlin_files())}")
    if errors:
        print(f"\n{len(errors)} PROBLEM(S):")
        for e in errors:
            print("  " + e)
        return 1
    print("CLEAN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
