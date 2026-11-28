package Vystorm.vystorm_core.packet.internal.view;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.packet.internal.view.ViewPlatform.Kind;

import org.junit.jupiter.api.Test;

/** Which server and version pass the first gate (the self-test decides after it). */
class ViewPlatformTest {
    @Test void paperAndPurpur26() {
        for (String mc : new String[] {"26.2", "26.2.1", "26.3", "26.3.2"}) {
            assertNull(ViewPlatform.check(Kind.PAPER, "Paper", mc), mc);
            assertNull(ViewPlatform.check(Kind.PAPER, "Purpur", mc), mc);
        }
    }

    @Test void spigot26() {
        assertNull(ViewPlatform.check(Kind.SPIGOT, "CraftBukkit", "26.3"));
        assertNull(ViewPlatform.check(Kind.SPIGOT, "CraftBukkit", "26.3.1"));
        assertNull(ViewPlatform.check(Kind.SPIGOT, "CraftBukkit", "26.2"));
    }

    @Test void olderVersionsStayOffAndNameWhatIsSupported() {
        String paper1206 = ViewPlatform.check(Kind.PAPER, "Paper", "1.20.6");
        assertNotNull(paper1206);
        assertTrue(paper1206.startsWith(ViewPlatform.SUPPORTED), paper1206);
        assertTrue(paper1206.contains("1.20.6"), paper1206);
        assertNotNull(ViewPlatform.check(Kind.SPIGOT, "CraftBukkit", "1.21.11"));
        assertNotNull(ViewPlatform.check(Kind.PAPER, "Paper", "26.1"));
        assertNotNull(ViewPlatform.check(Kind.PAPER, "Paper", "26.4"));
        assertNotNull(ViewPlatform.check(Kind.PAPER, "Paper", "26.20"));
    }

    @Test void unknownServersStayOff() {
        String r = ViewPlatform.check(Kind.OTHER, "Glowstone", "26.3");
        assertNotNull(r);
        assertTrue(r.startsWith(ViewPlatform.SUPPORTED) && r.contains("Glowstone"), r);
    }

    @Test void theLineNamesEveryPlatform() {
        assertTrue(ViewPlatform.SUPPORTED.contains("Paper") && ViewPlatform.SUPPORTED.contains("Purpur") && ViewPlatform.SUPPORTED.contains("Spigot"));
        assertTrue(ViewPlatform.SUPPORTED.contains("26.2") && ViewPlatform.SUPPORTED.contains("26.3"));
    }
}
