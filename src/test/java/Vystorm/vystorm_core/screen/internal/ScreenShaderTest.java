package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScreenShaderTest {
    private final Map<String, byte[]> files = ScreenShader.packFiles();

    private String shader() { return new String(files.get("assets/minecraft/shaders/core/text.vsh"), StandardCharsets.UTF_8); }

    @Test void givenPackFiles_thenShaderFontAndTextureExist() {
        assertTrue(files.containsKey("assets/minecraft/shaders/core/text.vsh"));
        assertTrue(files.containsKey("assets/vystorm_screens/font/screen.json"));
        byte[] png = files.get("assets/vystorm_screens/textures/pointer.png");
        assertNotNull(png);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
    }

    @Test void givenShader_thenContainsMarkerOfJavaConstant() {
        int r = ScreenShader.MARKER_RGB >> 16 & 0xFF, g = ScreenShader.MARKER_RGB >> 8 & 0xFF, b = ScreenShader.MARKER_RGB & 0xFF;
        assertTrue(shader().contains("ivec3(" + r + ", " + g + ", " + b + ")"), "shader marker must match MARKER_RGB");
    }

    @Test void givenShader_thenOriginalProjectionLineKeptForOtherText() {
        assertTrue(shader().contains("gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);"));
        assertTrue(shader().startsWith("#version 330"));
    }

    @Test void givenFontJson_thenMapsPointerChar() {
        String font = new String(files.get("assets/vystorm_screens/font/screen.json"), StandardCharsets.UTF_8);
        assertTrue(font.contains("vystorm_screens:pointer.png"));
        assertTrue(font.contains("\uE000") || font.contains("\\uE000"), "pointer char");
        assertTrue(font.contains("vystorm_screens:solid.png"), "solid block glyph");
        assertTrue(files.containsKey("assets/vystorm_screens/textures/solid.png"));
        assertEquals("\uE000", ScreenShader.POINTER);
    }

    @Test void givenRealAssetRegistry_whenRegistered_thenAccepted() {
        org.bukkit.plugin.Plugin core = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(core.getName()).thenReturn("vystorm_core");
        Vystorm.vystorm_core.pack.AssetRegistry registry = new Vystorm.vystorm_core.pack.AssetRegistry();
        assertDoesNotThrow(() -> registry.register(core, ScreenShader.MODULE, ScreenShader.NAMESPACES, ScreenShader.packFiles(), 0));
        assertTrue(registry.hasModule(ScreenShader.MODULE));
    }

    private String shader263() { return new String(files.get(ScreenShader.OVERLAY_26_3 + "/assets/minecraft/shaders/core/text.vsh"), StandardCharsets.UTF_8); }

    /** Declarations of the vanilla 26.3 core/text.vsh (client 26.3, resource format 97.1); its text.fsh reads these locations. */
    private static final String[] VANILLA_263 = {
            "#version 330\n#extension GL_ARB_separate_shader_objects : require\n",
            "#include <minecraft:fog.glsl>", "#include <minecraft:sample_lightmap.glsl>",
            "#include <minecraft:dynamictransforms.glsl>", "#include <minecraft:projection.glsl>",
            "layout(location = 0) in vec3 Position;", "layout(location = 1) in vec4 Color;", "layout(location = 2) in vec2 UV0;",
            "layout(location = 3) in ivec2 UV2;",
            "layout(location = 0) out float sphericalVertexDistance;", "layout(location = 1) out float cylindricalVertexDistance;",
            "layout(location = 2) out vec4 vertexColor;", "layout(location = 3) out vec2 texCoord0;",
    };

    @Test void given26_3Overlay_thenShaderUsesTheVanilla26_3Declarations() {
        String s = shader263();
        assertTrue(s.startsWith(VANILLA_263[0]), "version and extension line first");
        for (String line : VANILLA_263) assertTrue(s.contains(line), "missing: " + line);
        assertFalse(s.contains("#moj_import"), "26.3 rejects #moj_import (invalid directive) and the whole pack fails");
        assertFalse(s.matches("(?s).*\n(in|out) .*"), "every vertex input and output has a location");
    }

    @Test void given26_3Overlay_thenCoreBranchesAreTheSameAsIn26_2() {
        String base = shader(), s = shader263();
        for (String part : new String[] { "ivec3(254, 254, 254)", "vystormWord(0) == 46499", "vystormHudColor(vystormHud.b)",
                "gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);" }) {
            assertTrue(base.contains(part), part);
            assertTrue(s.contains(part), part);
        }
        assertEquals(base.substring(base.indexOf("void main()")).replace("\r\n", "\n"), s.substring(s.indexOf("void main()")));
    }

    @Test void given26_2Base_thenUnchanged() {
        assertTrue(shader().contains("#moj_import <minecraft:fog.glsl>"));
        assertFalse(shader().contains("layout(location"));
    }

    @Test void givenShaderWithoutAConvertedLine_thenConversionFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> ScreenShader.textFor263("#version 330\nvoid main() {}\n"));
    }
}
