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
}
