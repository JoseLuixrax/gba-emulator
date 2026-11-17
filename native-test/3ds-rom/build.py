#!/usr/bin/env python3
"""Builds test.3dsx, a tiny 3DS homebrew used by native-test/n3ds_host_test.

Needs clang, ld.lld and llvm-objcopy. Usage: build.py <output.3dsx>

3DSX layout: header (0x2C bytes), three relocation headers, then the code,
rodata and data segments. Code goes at 0x00100000; no relocations needed
because everything is linked for that address.
"""
import os, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 0x00100000

def page(n):
    return (n + 0xFFF) & ~0xFFF

def build(out):
    with tempfile.TemporaryDirectory() as tmp:
        obj = os.path.join(tmp, "main.o")
        elf = os.path.join(tmp, "main.elf")
        subprocess.check_call(["clang", "--target=armv6k-none-eabi", "-c", os.path.join(HERE, "main.s"), "-o", obj])
        # Code at BASE, data on the next page boundary, like the 3DSX loader places them.
        script = os.path.join(tmp, "link.ld")
        with open(script, "w") as f:
            f.write("SECTIONS { . = 0x%x; .text : { *(.text*) } . = ALIGN(0x1000); .data : { *(.data*) } }\n" % BASE)
        subprocess.check_call(["ld.lld", "-T", script, "-e", "_start", obj, "-o", elf])
        code = os.path.join(tmp, "code.bin")
        data = os.path.join(tmp, "data.bin")
        subprocess.check_call(["llvm-objcopy", "-O", "binary", "--only-section=.text", elf, code])
        subprocess.check_call(["llvm-objcopy", "-O", "binary", "--only-section=.data", elf, data])
        code_bytes = open(code, "rb").read()
        data_bytes = open(data, "rb").read()
    code_bytes += b"\0" * (page(len(code_bytes)) - len(code_bytes))
    header_size = 0x2C
    reloc_hdr_size = 8  # two u32 per segment: absolute and relative counts
    header = struct.pack("<4sHHIIIIIIIII", b"3DSX", header_size, reloc_hdr_size, 0, 0,
                         len(code_bytes), 0, len(data_bytes), 0, 0, 0, 0)
    relocs = b"\0" * (reloc_hdr_size * 3)
    with open(out, "wb") as f:
        f.write(header + relocs + code_bytes + data_bytes)

if __name__ == "__main__":
    build(sys.argv[1])
