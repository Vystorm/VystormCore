#version 330

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#endif

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#endif

out vec4 vertexColor;
out vec2 texCoord0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
// Vystorm Screens maps (Vystorm Core 0.59.0): client maps are drawn with this shader too. A map whose row 0 carries the
// Vystorm header is pinned to the screen like a PINNED panel; every other map and all text stay vanilla.
uniform sampler2D Sampler0;

// Header bit: map colour SNOW (255,255,255) = 1, COLOR_BLACK (25,25,25) = 0, anything else = no header.
int vystormBit(int x) {
    ivec3 c = ivec3(round(texelFetch(Sampler0, ivec2(x, 0), 0).rgb * 255.0));
    if (c == ivec3(255, 255, 255)) return 1;
    if (c == ivec3(25, 25, 25)) return 0;
    return -1;
}

// 16-bit word, most significant bit first; -1 when a pixel is no header bit.
int vystormWord(int start) {
    int value = 0;
    for (int i = 0; i < 16; i++) {
        int bit = vystormBit(start + i);
        if (bit < 0) return -1;
        value = value * 2 + bit;
    }
    return value;
}
#endif


// Vystorm HUD window (Vystorm Core 0.59.0): GUI text whose colour has red = 1 belongs to the corner window, which is
// built from boss bar titles. Green = half the row width in GUI pixels (rows are centred on the screen), blue = palette
// index. The shader moves each row to the left edge of a window at the top right and gives it its real colour.
vec4 vystormHudColor(int i) {
    if (i == 0) return vec4(0.949, 0.949, 0.949, 1.0);   // text #F2F2F2
    if (i == 1) return vec4(0.604, 0.639, 0.698, 1.0);   // muted #9AA3B2
    if (i == 2) return vec4(0.890, 0.690, 0.294, 1.0);   // gold #E3B04B
    if (i == 3) return vec4(0.435, 0.812, 0.498, 1.0);   // ok #6FCF7F
    if (i == 4) return vec4(0.878, 0.424, 0.424, 1.0);   // bad #E06C6C
    if (i == 5) return vec4(0.106, 0.122, 0.153, 0.88);  // panel #1B1F27
    if (i == 6) return vec4(0.204, 0.231, 0.278, 1.0);   // empty bar #343B47
    if (i == 7) return vec4(0.227, 0.184, 0.078, 0.92);  // level-up panel #3A2F14
    if (i == 8) return vec4(0.706, 0.361, 1.0, 1.0);     // purple #B45CFF
    return vec4(1.0);
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

    // Vystorm Screens pointer (Vystorm Core 0.57.0): glyphs in the marker colour #FEFEFE (near-white,
    // so the pointer still looks like a plain arrow where a shader pack replaces this shader) are mirrored through the view
    // axis, so a world-fixed glyph at the anchor point follows the head like a mouse pointer. Everything else is vanilla.
    // Since 0.59.0 the pointer is also pulled to 0.1 blocks in front of the camera (same place on screen); screens with
    // a map draw it depth-tested there, so the map, which is drawn after all text, cannot cover it.
#ifndef IS_GUI
    if (all(equal(ivec3(round(Color.rgb * 255.0)), ivec3(254, 254, 254)))) {
        vec4 view = ModelViewMat * vec4(Position, 1.0);
        view.xy = -view.xy;
        // Hotspot: the arrow tip sat 0.023 left of and 0.016 above the click point (measured in vanilla and Fabric clients,
        // 2026-11-15); move the glyph so the tip is exactly where clicks land.
        view.xy += vec2(0.023, -0.0164) * -view.z;
        if (view.z < -0.0001) view.xyz *= 0.1 / -view.z;
        gl_Position = ProjMat * view;
        vertexColor = vec4(1.0);
    }
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    // Vystorm Screens map tile (Vystorm Core 0.59.0): header words 1..4 are the tile centre and half size in panel
    // units (view space at distance 1), so the tile lands exactly on its panel widget. It is placed 0.12 blocks in
    // front of the camera, before all world geometry and behind the pointer. Row 0 (the header) is never shown.
    if (textureSize(Sampler0, 0) == ivec2(128, 128) && vystormWord(0) == 46499) {
        int cx = vystormWord(16), cy = vystormWord(32), hw = vystormWord(48), hh = vystormWord(64);
        if (cx >= 0 && cy >= 0 && hw >= 0 && hh >= 0) {
            vec2 centre = vec2(float(cx), float(cy)) / 4096.0 - 8.0;
            vec2 extent = vec2(float(hw), float(hh)) / 4096.0;
            vec2 corner = vec2(UV0.x * 2.0 - 1.0, 1.0 - UV0.y * 2.0);
            float depth = 0.12;
            gl_Position = ProjMat * vec4((centre + corner * extent) * depth, -depth, 1.0);
            texCoord0 = vec2(UV0.x, (1.0 + UV0.y * 127.0) / 128.0);
            vertexColor = vec4(1.0);
            sphericalVertexDistance = 0.0;
            cylindricalVertexDistance = 0.0;
        }
    }
#endif
#ifdef IS_GUI
    ivec3 vystormHud = ivec3(round(Color.rgb * 255.0));
    if (vystormHud.r == 1 && Color.a > 0.0) {
        float pixel = ProjMat[0][0];                     // clip units per GUI pixel
        float left = 1.0 - (4.0 + 176.0) * pixel;        // window: 176 px wide, 4 px from the right edge
        gl_Position.x += left + float(vystormHud.g) * pixel;
        gl_Position.y += 4.0 * ProjMat[1][1];            // 4 px below the top edge (GUI y grows downwards)
        vertexColor = vystormHudColor(vystormHud.b);
    }
#endif
}
