package Vystorm.vystorm_core._uiMethods.admin;

import java.util.*;

final class Catalog {
    private static AdminAction a(
            String id, String label, String perm, String fixed, String hint, String ex, boolean mut) {
        return new AdminAction(id, label, perm, fixed, hint, ex, mut);
    }

    private static AdminModule m(
            String id, String title, String plugin, String cmd, String perm, String nativeEntry, AdminAction... as) {
        return new AdminModule(id, title, plugin, cmd, perm, nativeEntry, List.of(as));
    }

    static List<AdminModule> modules() {
        return List.of(
                m(
                        "core", "Vystorm Core", "vystorm_core", "admin", "vystorm_core.admin", "menu",
                        a("status", "Dashboard-Status", "vystorm_core.admin", "status", "", "/admin status", false)),
                m(
                        "economy", "XP Vault Economy", "XPVaultEconomy", "economy", "xpeconomy.admin",
                        "economy balance",
                        a(
                                "info", "Wirtschaftsstatus", "xpeconomy.admin", "admin info", "", "/economy admin info",
                                false),
                        a(
                                "set", "Guthaben setzen", "xpeconomy.admin", "admin set", "<spieler> <xp>", "Steve 500",
                                true)),
                m(
                        "market", "Vystorm Market", "VystormMarket", "market", "deepmarket.admin", "market open",
                        a("open", "Markt öffnen", "deepmarket.use", "open", "", "/market open", false)),
                m(
                        "cities", "CityClaim", "VystormCityClaim", "cities", "stadtclaim.admin", "cities",
                        a("info", "Stadt am Standort", "stadtclaim.admin", "info", "", "/cities info", false)),
                m(
                        "blocks", "BlockIntegrations", "BlockIntegrations", "blockintegrations",
                        "blockintegrations.admin", "",
                        a(
                                "status", "Status", "blockintegrations.admin", "status", "",
                                "/blockintegrations status", false)),
                m(
                        "inventory", "DeepInventory", "DeepInventory", "inventory", "deepinventory.admin",
                        "inventory menu",
                        a(
                                "status", "Transaktionsstatus", "deepinventory.admin", "admin status", "",
                                "/inventory admin status", false)),
                m(
                        "trees", "CustomTrees", "CustomTrees", "customtrees", "customtrees.admin", "",
                        a("status", "Status", "customtrees.admin", "status", "", "/customtrees status", false)),
                m(
                        "arena", "Arena", "Arena", "arena", "arena.admin", "arena admin",
                        a("admin", "Arena-Admin-GUI", "arena.admin", "admin", "", "/arena admin", false),
                        a("start", "Arena starten", "arena.admin", "start", "<arena>", "arena-1", true),
                        a("stop", "Arena stoppen", "arena.admin", "stop", "<arena>", "arena-1", true)),
                m(
                        "bounty", "Bounty", "VystormBounty", "bounty", "vystormbounty.admin", "",
                        a("list", "Aktive Kopfgelder", "vystormbounty.list", "list", "", "/bounty list", false),
                        a(
                                "set", "Kopfgeld setzen", "vystormbounty.admin.set", "admin set", "<spieler> <betrag>",
                                "Steve 100", true)),
                m(
                        "bloodmoon", "BloodMoon", "BloodMoon", "bloodmoon", "bloodmoon.admin", "",
                        a("status", "Status", "bloodmoon.use", "status", "", "/bloodmoon status", false),
                        a("start", "BloodMoon starten", "bloodmoon.admin", "start", "[welt]", "world", true),
                        a("stop", "BloodMoon stoppen", "bloodmoon.admin", "stop", "[welt]", "world", true)),
                m(
                        "insomnia", "Insomnia", "VystormInsomnia", "insomnia", "insomnia.admin", "",
                        a("get", "Spielerstatus", "insomnia.admin", "admin get", "<spieler>", "Steve", false),
                        a("rest", "Spieler erholen", "insomnia.admin", "admin sleep", "<spieler>", "Steve", true)),
                m(
                        "director", "SpawnDirector", "SpawnDirector", "director", "spawndirector.admin", "",
                        a("status", "Status", "spawndirector.admin", "status", "", "/director status", false),
                        a("debug", "Spielerdebug", "spawndirector.admin", "debug", "<spieler>", "Steve", false),
                        a("events", "Events am Spieler", "spawndirector.admin", "events", "<spieler>", "Steve", false),
                        a("wave", "Welle auslösen", "spawndirector.admin", "wave", "<spieler>", "Steve", true),
                        a(
                                "population", "Strukturbelegung", "spawndirector.admin", "population", "",
                                "/director population", false),
                        a("reload", "Neu laden", "spawndirector.admin", "reload", "", "/director reload", true)),
                m(
                        "farms", "VystormFarms", "VystormFarms", "farms", "vystormfarms.admin", "",
                        a("status", "Status", "vystormfarms.admin", "status", "[welt]", "world", false),
                        a("inspect", "Anvisiertes Feld", "vystormfarms.admin", "inspect", "", "/farms inspect", false)),
                m(
                        "build", "VystormBuild", "VystormBuild", "build", "vystormbuild.use", "build dialog",
                        a("dialog", "Build-Dialog", "vystormbuild.use", "dialog", "", "/build dialog", false)),
                m(
                        "pets", "VystormPets", "VystormPets", "pet", "vystormpets.admin", "pet",
                        a("reload", "Neu laden", "vystormpets.admin", "admin reload", "", "/pet admin reload", true),
                        a(
                                "status", "Pet-Adminstatus", "vystormpets.admin", "admin status", "",
                                "/pet admin status", false),
                        a(
                                "list", "Pets eines Besitzers", "vystormpets.admin", "admin list",
                                "<online-player-name|UUID>", "Steve", false),
                        a("info", "Pet-Info", "vystormpets.admin", "admin info", "<petUUID>", "uuid", false)),
                m(
                        "worlddirector", "WorldDirector", "WorldDirector", "worlddirector", "worlddirector.admin", "",
                        a(
                                "active", "Aktive Events", "worlddirector.admin", "active", "", "/worlddirector active",
                                false),
                        a(
                                "reservations", "Reservierungen", "worlddirector.admin", "reservations", "",
                                "/worlddirector reservations", false),
                        a(
                                "modifiers", "Modifikatoren", "worlddirector.admin", "modifiers",
                                "[global|world|regional|local] [scopeKey]", "global", false),
                        a(
                                "select", "Auswahlvorschau", "worlddirector.admin", "select",
                                "[global|world|regional|local] [scopeKey]", "global", false),
                        a(
                                "weights", "Gewichtungen", "worlddirector.admin", "weights",
                                "[global|world|regional|local] [scopeKey]", "global", false),
                        a(
                                "start", "Event starten", "worlddirector.admin", "start",
                                "<eventId> <global|world|regional|local> [scopeKey]", "event global", true),
                        a("stop", "Event stoppen", "worlddirector.admin", "stop", "<instanceUuid>", "uuid", true),
                        a("save", "Speichern", "worlddirector.admin", "save", "", "/worlddirector save", true),
                        a(
                                "storyteller", "Storyteller", "worlddirector.admin", "storyteller", "[id|random]",
                                "random", true)),
                m(
                        "worldforge", "WorldForge", "Vystorm-WorldForge", "worldforge", "worldforge.admin", "",
                        a("info", "WorldForge-Info", "worldforge.use", "info", "", "/worldforge info", false),
                        a(
                                "population", "Population", "worldforge.admin", "population", "",
                                "/worldforge population", false),
                        a("events", "Events", "worldforge.admin", "events", "", "/worldforge events", false),
                        a("list", "Pläne und Instanzen", "worldforge.admin", "list", "", "/worldforge list", false),
                        a(
                                "town", "Stadt planen und bauen", "worldforge.admin", "town",
                                "<fraktion|thema> [module]", "human_crown 11", true),
                        a(
                                "dungeon", "Dungeon planen und bauen", "worldforge.admin", "dungeon",
                                "<thema> [module]", "crypt_undead 11", true),
                        a(
                                "plan", "Plan erstellen", "worldforge.admin", "plan",
                                "<thema> <typ> <anzahl> [fraktion]", "human_crown CITY 11 north", true),
                        a("place", "Plan platzieren", "worldforge.admin", "place", "<planUUID>", "uuid", true),
                        a("remove", "Struktur entfernen", "worldforge.admin", "remove", "<planUUID>", "uuid", true),
                        a("recover", "Wiederherstellen", "worldforge.admin", "recover", "<planUUID>", "uuid", true)),
                m(
                        "parties", "VystormParties", "VystormParties", "party", "vystormparties.admin", "party",
                        a(
                                "reload", "Neu laden", "vystormparties.admin", "admin reload", "",
                                "/party admin reload", true),
                        a(
                                "disband", "Party auflösen", "vystormparties.admin", "admin disband", "<spieler>",
                                "Steve", true),
                        a(
                                "spy", "Party-Chat mitlesen", "vystormparties.admin", "admin spy", "",
                                "/party admin spy", false)),
                m(
                        "skills", "VystormSkills", "VystormSkills", "progression", "vystormskills.admin", "skills",
                        a("inspect", "Spieler prüfen", "vystormskills.admin", "inspect", "<spieler>", "Steve", false),
                        a("reload", "Neu laden", "vystormskills.admin", "reload", "", "/progression reload", true)),
                m(
                        "factions", "Vystorm-Factions", "Vystorm-Factions", "vfactions", "vystormfactions.admin",
                        "vfactions menu",
                        a("list", "Fraktionen", "vystormfactions.admin", "list", "", "/vfactions list", false),
                        a("map", "Fraktionskarte", "vystormfactions.admin", "map", "", "/vfactions map", false),
                        a("regions", "Regionen", "vystormfactions.admin", "regions", "", "/vfactions regions", false),
                        a(
                                "region", "Region setzen", "vystormfactions.admin", "region",
                                "<id> <welt> <x> <z> <radiusChunks>", "north world 0 0 8", true),
                        a("info", "Info", "vystormfactions.admin", "info", "<id>", "human_crown", false),
                        a("members", "Mitglieder", "vystormfactions.admin", "members", "<id>", "human_crown", false),
                        a(
                                "member", "Mitglied ändern", "vystormfactions.admin", "member",
                                "<id> <spielerUUID> <add|remove>",
                                "human_crown 00000000-0000-0000-0000-000000000000 add", true),
                        a("pause", "Pausieren", "vystormfactions.admin", "pause", "<id>", "human_crown", true),
                        a("resume", "Fortsetzen", "vystormfactions.admin", "resume", "<id>", "human_crown", true),
                        a(
                                "capital", "Hauptstadt am Zielblock", "vystormfactions.admin", "capital", "<id>",
                                "human_crown", true),
                        a(
                                "capitalat", "Hauptstadt setzen", "vystormfactions.admin", "capitalat",
                                "<id> <welt> <x> <y> <z>", "human_crown world 0 80 0", true),
                        a("retry", "Erneut versuchen", "vystormfactions.admin", "retry", "<id>", "human_crown", true)),
                m(
                        "portals", "VystormPortals", "VystormPortals", "vportal", "vystormportals.admin", "",
                        a("list", "Portale", "vystormportals.admin", "list", "", "/vportal list", false)),
                m(
                        "veins", "VystormVeins", "VystormVeins", "vveins", "vystormveins.admin", "",
                        a("status", "Status", "vystormveins.admin", "status", "", "/vveins status", false)),
                m(
                        "winter", "Winter Is Coming", "WinterIsComing", "winter", "winter.admin", "",
                        a("status", "Status", "winter.admin", "status", "", "/winter status", false)),
                m(
                        "gallery", "Vystorm Gallery", "VystormGallery", "vgallery", "vystormgallery.admin", "",
                        a("reload", "Neu laden", "vystormgallery.admin", "reload", "", "/vgallery reload", true)),
                m(
                        "paddock", "Vystorm Paddock", "VystormPaddock", "paddock", "vystormpaddock.admin", "",
                        a("reload", "Neu laden", "vystormpaddock.admin", "reload", "", "/paddock reload", true)),
                m(
                        "snapshot", "Vystorm Snapshot", "VystormSnapshot", "snapshot", "vystormsnapshot.admin", "",
                        a("give", "Kamera geben", "vystormsnapshot.admin", "give", "<spieler>", "Steve", true)),
                m(
                        "boards", "Vystorm Boards", "VystormBoards", "boards", "vystormboards.admin", "",
                        a("list", "Tafeln", "vystormboards.admin", "list", "", "/boards list", false),
                        a("reload", "Neu laden", "vystormboards.admin", "reload", "", "/boards reload", true)),
                m(
                        "estates", "Vystorm Estates", "VystormEstates", "estates", "vystormestates.admin",
                        "estates market",
                        a("list", "Grundstücke", "vystormestates.admin", "list", "", "/estates list", false),
                        a("reload", "Neu laden", "vystormestates.admin", "reload", "", "/estates reload", true)));
    }
}
