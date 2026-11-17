package Vystorm.vystorm_core.profile;

import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilesTest {
    private final Plugin a = plugin("Alpha", true), b = plugin("beta", true), off = plugin("Gamma", false), broken = plugin("Delta", true);

    private static Plugin plugin(String name, boolean enabled) {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn(name);
        when(p.isEnabled()).thenReturn(enabled);
        return p;
    }

    @AfterEach void clear() { for (Plugin p : List.of(a, b, off, broken)) Profiles.unregister(p); }

    @Test
    void givenProviders_whenSections_thenSortedSkippingEmptyDisabledAndFailing() {
        Profiles.register(b, (viewer, target) -> List.of(Component.text("b1")));
        Profiles.register(a, (viewer, target) -> List.of(Component.text("a1"), Component.text("a2")));
        Profiles.register(off, (viewer, target) -> List.of(Component.text("never")));
        Profiles.register(broken, (viewer, target) -> { throw new IllegalStateException("boom"); });
        var sections = Profiles.sections(null, null);
        assertEquals(List.of("Alpha", "beta"), sections.stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(2, sections.getFirst().getValue().size());
    }

    @Test
    void givenTooManyLines_whenSections_thenCapped() {
        Profiles.register(a, (viewer, target) -> java.util.stream.IntStream.range(0, 20).mapToObj(i -> (Component) Component.text("x" + i)).toList());
        assertEquals(Profiles.MAX_LINES, Profiles.sections(null, null).getFirst().getValue().size());
        Profiles.register(a, (viewer, target) -> List.of());
        assertTrue(Profiles.sections(null, null).isEmpty());
    }
}
