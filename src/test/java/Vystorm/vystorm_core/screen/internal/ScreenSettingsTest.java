package Vystorm.vystorm_core.screen.internal;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.screen.ScreenProfile;
import org.junit.jupiter.api.Test;

class ScreenSettingsTest {
    @Test void givenPresetClick_thenAspectChanges() {
        ScreenProfile next = ScreenSettings.apply(ScreenProfile.DEFAULT, "aspect:3");
        assertEquals(21.0 / 9, next.aspect(), 1e-9);
        assertEquals("21:9", ScreenSettings.aspectText(next.aspect()));
    }

    @Test void givenFovAndScaleClicks_thenSteppedAndClamped() {
        ScreenProfile p = ScreenProfile.DEFAULT;
        assertEquals(75, ScreenSettings.apply(p, "fov+").fov());
        assertEquals(1.1, ScreenSettings.apply(p, "scale+").scale(), 1e-9);
        ScreenProfile max = new ScreenProfile(16.0 / 9, ScreenProfile.MAX_FOV, ScreenProfile.MAX_SCALE);
        assertEquals(ScreenProfile.MAX_FOV, ScreenSettings.apply(max, "fov+").fov());
        assertEquals(ScreenProfile.MAX_SCALE, ScreenSettings.apply(max, "scale+").scale(), 1e-9);
    }

    @Test void givenOtherClicks_thenNoChange() {
        assertNull(ScreenSettings.apply(ScreenProfile.DEFAULT, "done"));
        assertNull(ScreenSettings.apply(ScreenProfile.DEFAULT, null));
    }

    @Test void givenOwnResolution_thenParsedOrRejected() {
        assertEquals(3840.0 / 1009, ScreenProfile.parseResolution("3840x1009"), 1e-9);
        assertEquals(16.0 / 9, ScreenProfile.parseResolution(" 1920 × 1080 "), 1e-9);
        assertEquals(3840.0 / 1009, ScreenProfile.parseResolution("3840 1009"), 1e-9);
        assertNull(ScreenProfile.parseResolution("wide"));
        assertNull(ScreenProfile.parseResolution("0x100"));
        assertEquals("3.81:1", ScreenSettings.aspectText(3840.0 / 1009));
    }

    @Test void givenProfile_thenViewSizeFollowsFovAndAspect() {
        ScreenProfile p = new ScreenProfile(2.0, 90, 1.0);
        assertEquals(2.0, p.viewHeight(), 1e-9);
        assertEquals(4.0, p.viewWidth(), 1e-9);
        assertEquals(ScreenProfile.MAX_ASPECT, new ScreenProfile(99, 70, 1).aspect(), 1e-9);
    }
}
