#!/usr/bin/env python3
"""Builds test.nds, a tiny homebrew DS ROM used by native-test.

Needs clang, ld.lld and llvm-objcopy. Usage: build.py <output.nds>
"""
import os, struct, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ARM9_ADDR = 0x02000000
ARM7_ADDR = 0x037F8000

def assemble(src, arch, addr, tmp):
    obj = os.path.join(tmp, src + ".o")
    elf = os.path.join(tmp, src + ".elf")
    binf = os.path.join(tmp, src + ".bin")
    subprocess.check_call(["clang", f"--target={arch}-none-eabi", "-c", os.path.join(HERE, src), "-o", obj])
    subprocess.check_call(["ld.lld", f"-Ttext=0x{addr:08X}", "-e", "_start", obj, "-o", elf])
    subprocess.check_call(["llvm-objcopy", "-O", "binary", elf, binf])
    return open(binf, "rb").read()

def crc16(data, crc=0xFFFF):
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc

def align(n, a=0x200):
    return (n + a - 1) // a * a

def main(out):
    with tempfile.TemporaryDirectory() as tmp:
        arm9 = assemble("arm9.s", "armv5te", ARM9_ADDR, tmp)
        arm7 = assemble("arm7.s", "armv4t", ARM7_ADDR, tmp)
    arm9_off = 0x200
    arm7_off = align(arm9_off + len(arm9))
    total = align(arm7_off + len(arm7))
    size = 0x20000  # 128 KiB, device capacity 0
    rom = bytearray(size)
    h = bytearray(0x200)
    h[0x00:0x0C] = b"ADVBOYTEST\0\0"
    h[0x0C:0x10] = b"####"
    h[0x10:0x12] = b"00"
    struct.pack_into("<IIII", h, 0x20, arm9_off, ARM9_ADDR, ARM9_ADDR, len(arm9))
    struct.pack_into("<IIII", h, 0x30, arm7_off, ARM7_ADDR, ARM7_ADDR, len(arm7))
    struct.pack_into("<I", h, 0x60, 0x00586000)  # normal card control
    struct.pack_into("<I", h, 0x64, 0x001808F8)  # secure card control
    struct.pack_into("<I", h, 0x80, total)
    struct.pack_into("<I", h, 0x84, 0x4000)
    struct.pack_into("<H", h, 0x15C, 0xCF56)  # logo CRC
    struct.pack_into("<H", h, 0x15E, crc16(h[:0x15E]))
    rom[0:0x200] = h
    rom[arm9_off:arm9_off + len(arm9)] = arm9
    rom[arm7_off:arm7_off + len(arm7)] = arm7
    open(out, "wb").write(rom)

if __name__ == "__main__":
    main(sys.argv[1])
