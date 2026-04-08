#if defined(__ARM_NEON__) || defined(__ARM64_NEON__)

#include "vdrawhelper.h"

// Pure C fallback implementations (NEON assembly not available in Windows NDK build)

void memfill32(uint32_t *dest, uint32_t value, int length)
{
    for (int i = 0; i < length; i++) {
        dest[i] = value;
    }
}

void comp_func_solid_SourceOver_neon(uint32_t *dest, int length, uint32_t color,
                                     uint32_t const_alpha)
{
    if (const_alpha != 255) color = BYTE_MUL(color, const_alpha);
    uint32_t sa = color >> 24;
    if (sa == 255) {
        memfill32(dest, color, length);
        return;
    }
    uint32_t isa = 255 - sa;
    for (int i = 0; i < length; i++) {
        uint32_t d = dest[i];
        uint32_t rb = ((color & 0xFF00FF) + ((((d & 0xFF00FF) * isa) + 0x800080) >> 8)) & 0xFF00FF;
        uint32_t ag = ((((color >> 8) & 0xFF00FF) + (((((d >> 8) & 0xFF00FF) * isa) + 0x800080) >> 8))) & 0xFF00FF;
        dest[i] = rb | (ag << 8);
    }
}

#endif
