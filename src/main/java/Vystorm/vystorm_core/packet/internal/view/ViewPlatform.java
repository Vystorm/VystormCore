package Vystorm.vystorm_core.packet.internal.view;

import org.bukkit.Bukkit;

/**
 * Which servers the packet view runs on: Paper and Purpur 26.2 and 26.3, Spigot 26.2 and 26.3. Everything else keeps the
 * view off ({@code supported()} false, no handler attached). Passing this check only means "worth trying": the
 * self-test with a real chunk decides. Internal: not part of the public API (moved from VystormAntiXray).
 */
public final class ViewPlatform {
    private ViewPlatform() {}

    /** The server families this plugin tells apart. */
    public enum Kind { PAPER, SPIGOT, OTHER }

    /** The line that names what is supported (part of every "stays off" reason). */
    public static final String SUPPORTED = "the packet view runs on Paper, Purpur and Spigot 26.2 and 26.3 only";

    /** The Minecraft version of the server ("26.3", "26.2.1", "1.20.6" ...). */
    public static String minecraft() {
        String v = Bukkit.getBukkitVersion(); // e.g. 26.3-R0.1-SNAPSHOT
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    static boolean present(String className) {
        try { Class.forName(className, false, ViewPlatform.class.getClassLoader()); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    /** Paper (and its forks such as Purpur), Spigot, or something else. */
    public static Kind kind() {
        if (present("io.papermc.paper.configuration.Configuration") || present("com.destroystokyo.paper.PaperConfig")) return Kind.PAPER;
        if (present("org.spigotmc.SpigotConfig")) return Kind.SPIGOT;
        return Kind.OTHER;
    }

    /** Null when supported, otherwise why the plugin stays off (one line for the log). */
    public static String check() { return check(kind(), Bukkit.getName(), minecraft()); }

    /** The decision itself (tested without a server). */
    static String check(Kind kind, String serverName, String mc) {
        boolean version = mc.equals("26.2") || mc.startsWith("26.2.") || mc.equals("26.3") || mc.startsWith("26.3.");
        if (kind == Kind.OTHER) return SUPPORTED + " (server: " + serverName + " " + mc + ").";
        if (!version) return SUPPORTED + "; Minecraft " + mc + " is not supported (server: " + serverName + " " + mc + ").";
        return null;
    }

    public static String describe() {
        String name = Bukkit.getName();
        if (kind() == Kind.SPIGOT && !name.toLowerCase(java.util.Locale.ROOT).contains("spigot")) name += " (Spigot)";
        return name + " " + minecraft();
    }
}
