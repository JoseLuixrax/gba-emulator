@ A tiny 3DS homebrew for the desktop test: it only counts frames in RAM
@ and sleeps, so the core boots, runs and produces frames and audio without
@ needing any real game.
.syntax unified
.arm
.global _start
_start:
    ldr r4, =counter
loop:
    ldr r1, [r4]
    add r1, r1, #1
    str r1, [r4]
    ldr r0, =16000000       @ svcSleepThread(16 ms)
    mov r1, #0
    svc 0x0A
    b loop
.ltorg
.data
counter:
    .word 0
