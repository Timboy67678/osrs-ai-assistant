package com.osrsai;

import java.util.*;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import net.runelite.api.InstanceTemplates;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

final class LocationResolver {
    private static final int[] UNDERGROUND_Y_OFFSETS = { 6400, 12800 };
    private static final String REGION_ALIAS_RESOURCE = "/com/osrsai/region-aliases.properties";

    // Region aliases are easier to maintain than large rectangle lists for named
    // dungeon zones.
    private static final Map<Integer, RegionAlias> REGION_ALIASES = loadRegionAliases();

    private static final List<NamedArea> KNOWN_AREAS = Arrays.asList(
            new NamedArea("Grand Exchange", new WorldArea(3153, 3472, 34, 28, 0)),
            new NamedArea("Varrock West Bank", new WorldArea(3176, 3422, 22, 20, 0)),
            new NamedArea("Varrock East Bank", new WorldArea(3247, 3415, 20, 22, 0)),
            new NamedArea("Edgeville", new WorldArea(3070, 3480, 42, 34, 0)),
            new NamedArea("Barbarian Village", new WorldArea(3060, 3400, 42, 36, 0)),
            new NamedArea("Cook's Guild", new WorldArea(3138, 3436, 20, 20, 0)),
            new NamedArea("Varrock", new WorldArea(3160, 3360, 120, 120, 0)),
            new NamedArea("Lumbridge", new WorldArea(3190, 3185, 72, 72, 0)),
            new NamedArea("Lumbridge Swamp", new WorldArea(3158, 3140, 80, 56, 0)),
            new NamedArea("Al Kharid Mine", new WorldArea(3288, 3270, 50, 36, 0)),
            new NamedArea("Draynor Village", new WorldArea(3060, 3210, 54, 44, 0)),
            new NamedArea("Port Sarim", new WorldArea(3000, 3160, 80, 64, 0)),
            new NamedArea("Rimmington", new WorldArea(2920, 3180, 56, 44, 0)),
            new NamedArea("Al Kharid", new WorldArea(3260, 3140, 70, 70, 0)),
            new NamedArea("Duel Arena", new WorldArea(3300, 3220, 80, 70, 0)),
            new NamedArea("Sorceress's Garden", new WorldArea(3300, 3110, 80, 54, 0)),
            new NamedArea("Shantay Pass", new WorldArea(3290, 3110, 36, 32, 0)),
            new NamedArea("Pollnivneach", new WorldArea(3330, 2920, 86, 78, 0)),
            new NamedArea("Nardah", new WorldArea(3370, 2860, 66, 64, 0)),
            new NamedArea("Sophanem", new WorldArea(3260, 2760, 88, 88, 0)),
            new NamedArea("Desert Bandit Camp", new WorldArea(3165, 2970, 64, 58, 0)),
            new NamedArea("Falador", new WorldArea(2940, 3280, 120, 110, 0)),
            new NamedArea("Falador Park", new WorldArea(2986, 3348, 54, 34, 0)),
            new NamedArea("Crafting Guild", new WorldArea(2920, 3260, 46, 40, 0)),
            new NamedArea("Mining Guild", new WorldArea(2996, 9720, 44, 40, 0)),
            new NamedArea("Taverley", new WorldArea(2870, 3400, 74, 96, 0)),
            new NamedArea("Burthorpe", new WorldArea(2860, 3520, 76, 56, 0)),
            new NamedArea("Heroes' Guild", new WorldArea(2870, 3490, 44, 40, 0)),
            new NamedArea("Taverley Dungeon", new WorldArea(2848, 9728, 96, 130, 0)),
            new NamedArea("Entrana Dungeon", new WorldArea(2816, 9792, 32, 64, 0)),
            new NamedArea("Dwarven Mine", new WorldArea(3008, 9670, 170, 110, 0)),
            new NamedArea("White Wolf Mountain", new WorldArea(2830, 3460, 80, 90, 0)),
            new NamedArea("Camelot", new WorldArea(2740, 3460, 46, 36, 0)),
            new NamedArea("Seers' Village", new WorldArea(2680, 3440, 68, 58, 0)),
            new NamedArea("Ranging Guild", new WorldArea(2646, 3410, 44, 34, 0)),
            new NamedArea("Fishing Guild", new WorldArea(2576, 3370, 52, 40, 0)),
            new NamedArea("Tree Gnome Village", new WorldArea(2490, 3130, 104, 90, 0)),
            new NamedArea("Tree Gnome Stronghold", new WorldArea(2390, 3380, 144, 140, 0)),
            new NamedArea("Grand Tree", new WorldArea(2458, 3460, 62, 62, 0)),
            new NamedArea("Catherby", new WorldArea(2780, 3420, 68, 52, 0)),
            new NamedArea("Legend's Guild", new WorldArea(2710, 3330, 56, 64, 0)),
            new NamedArea("Witchaven", new WorldArea(2680, 3260, 54, 52, 0)),
            new NamedArea("East Ardougne", new WorldArea(2580, 3280, 72, 90, 0)),
            new NamedArea("West Ardougne", new WorldArea(2500, 3280, 88, 90, 0)),
            new NamedArea("Ardougne Monastery", new WorldArea(2584, 3200, 62, 56, 0)),
            new NamedArea("Yanille", new WorldArea(2520, 3060, 76, 72, 0)),
            new NamedArea("Wizards' Guild", new WorldArea(2578, 3070, 26, 30, 0)),
            new NamedArea("Castle Wars", new WorldArea(2420, 3070, 90, 80, 0)),
            new NamedArea("Ourania", new WorldArea(2440, 3210, 64, 64, 0)),
            new NamedArea("Brimhaven", new WorldArea(2740, 3140, 92, 92, 0)),
            new NamedArea("Musa Point", new WorldArea(2890, 3140, 90, 90, 0)),
            new NamedArea("Karamja Volcano", new WorldArea(2820, 3150, 120, 100, 0)),
            new NamedArea("Tai Bwo Wannai", new WorldArea(2760, 3050, 76, 74, 0)),
            new NamedArea("Cairn Isle", new WorldArea(2760, 2960, 54, 58, 0)),
            new NamedArea("Shilo Village", new WorldArea(2800, 2920, 84, 88, 0)),
            new NamedArea("Kharazi Jungle", new WorldArea(2780, 2880, 170, 130, 0)),
            new NamedArea("Crandor", new WorldArea(2820, 3230, 80, 72, 0)),
            new NamedArea("Canifis", new WorldArea(3470, 3460, 54, 50, 0)),
            new NamedArea("Port Phasmatys", new WorldArea(3650, 3470, 60, 56, 0)),
            new NamedArea("Burgh de Rott", new WorldArea(3470, 3180, 72, 72, 0)),
            new NamedArea("Mort'ton", new WorldArea(3430, 3270, 72, 72, 0)),
            new NamedArea("Darkmeyer", new WorldArea(3580, 3320, 86, 78, 0)),
            new NamedArea("Slepe", new WorldArea(3650, 3240, 74, 70, 0)),
            new NamedArea("Barrows", new WorldArea(3540, 3270, 62, 54, 0)),
            new NamedArea("Paterdomus", new WorldArea(3400, 3460, 54, 52, 0)),
            new NamedArea("Digsite", new WorldArea(3320, 3380, 72, 72, 0)),
            new NamedArea("Mort Myre Swamp", new WorldArea(3390, 3370, 170, 170, 0)),
            new NamedArea("Fossil Island", new WorldArea(3648, 3712, 192, 200, 0)),
            new NamedArea("Ape Atoll", new WorldArea(2680, 2680, 170, 130, 0)),
            new NamedArea("Entrana", new WorldArea(2790, 3320, 90, 80, 0)),
            new NamedArea("Tutorial Island", new WorldArea(3080, 3080, 120, 100, 0)),
            new NamedArea("Rellekka", new WorldArea(2620, 3630, 96, 88, 0)),
            new NamedArea("Fremennik Slayer Cave", new WorldArea(2780, 10020, 160, 150, 0)),
            new NamedArea("Waterbirth Island", new WorldArea(2500, 3740, 160, 110, 0)),
            new NamedArea("Miscellania", new WorldArea(2480, 3840, 110, 100, 0)),
            new NamedArea("Etceteria", new WorldArea(2580, 3860, 120, 100, 0)),
            new NamedArea("Lunar Isle", new WorldArea(2060, 3890, 190, 180, 0)),
            new NamedArea("Neitiznot", new WorldArea(2290, 3790, 88, 84, 0)),
            new NamedArea("Jatizso", new WorldArea(2380, 3790, 84, 84, 0)),
            new NamedArea("Keldagrim", new WorldArea(2810, 10120, 180, 170, 0)),
            new NamedArea("Motherlode Mine", new WorldArea(3710, 5630, 220, 190, 0)),
            new NamedArea("Blast Mine", new WorldArea(1450, 3830, 100, 90, 0)),
            new NamedArea("Hosidius", new WorldArea(1670, 3530, 160, 190, 0)),
            new NamedArea("Shayzien", new WorldArea(1460, 3560, 170, 170, 0)),
            new NamedArea("Arceuus", new WorldArea(1570, 3750, 180, 170, 0)),
            new NamedArea("Lovakengj", new WorldArea(1410, 3690, 170, 170, 0)),
            new NamedArea("Piscarilius", new WorldArea(1740, 3670, 190, 180, 0)),
            new NamedArea("Mount Karuulm", new WorldArea(1240, 3770, 130, 130, 0)),
            new NamedArea("Wintertodt Camp", new WorldArea(1610, 3930, 70, 70, 0)),
            new NamedArea("Farming Guild", new WorldArea(1210, 3700, 100, 100, 0)),
            new NamedArea("Woodcutting Guild", new WorldArea(1540, 3440, 130, 120, 0)),
            new NamedArea("Kourend Woodland", new WorldArea(1480, 3440, 220, 210, 0)),
            new NamedArea("Ferox Enclave", new WorldArea(3110, 3620, 48, 42, 0)),
            new NamedArea("Wilderness Resource Area", new WorldArea(3160, 3920, 72, 56, 0)),
            new NamedArea("Mage Arena", new WorldArea(3080, 3920, 74, 68, 0)),
            new NamedArea("Chaos Temple", new WorldArea(2940, 3810, 72, 66, 0)),
            new NamedArea("Lava Maze", new WorldArea(3010, 3830, 94, 80, 0)),
            new NamedArea("Black Chinchompa Hunting Ground", new WorldArea(3100, 3770, 90, 80, 0)),
            new NamedArea("Varlamore Hunter Guild", new WorldArea(1530, 3410, 80, 70, 0)),
            new NamedArea("Civitas illa Fortis", new WorldArea(1600, 3000, 240, 270, 0)),
            new NamedArea("Aldarin", new WorldArea(1350, 2880, 130, 140, 0)),
            new NamedArea("Avium Savannah", new WorldArea(1470, 3000, 230, 450, 0)),
            new NamedArea("Sunset Coast", new WorldArea(1200, 2900, 160, 320, 0)),
            new NamedArea("Varlamore Mountain Range", new WorldArea(1400, 3100, 100, 150, 0)));

    private static final List<NamedArea> PROVINCE_FALLBACKS = Arrays.asList(
            new NamedArea("Kebos Lowlands", new WorldArea(1100, 3500, 430, 420, 0)),
            new NamedArea("Great Kourend", new WorldArea(1560, 3450, 360, 360, 0)),
            new NamedArea("Wilderness", new WorldArea(2940, 3520, 360, 560, 0)),
            new NamedArea("Karamja", new WorldArea(2760, 2860, 260, 380, 0)),
            new NamedArea("Varlamore", new WorldArea(1200, 2700, 650, 780, 0)),
            new NamedArea("Tirannwn", new WorldArea(2100, 3050, 260, 300, 0)),
            new NamedArea("Kandarin", new WorldArea(2100, 2900, 760, 650, 0)),
            new NamedArea("Asgarnia", new WorldArea(2860, 3100, 200, 420, 0)),
            new NamedArea("Misthalin", new WorldArea(3060, 3140, 270, 380, 0)),
            new NamedArea("Kharidian Desert", new WorldArea(3100, 2600, 350, 510, 0)),
            new NamedArea("Morytania", new WorldArea(3380, 3140, 340, 460, 0)),
            new NamedArea("Fremennik Province", new WorldArea(2580, 3550, 270, 300, 0)));

    String describe(WorldPoint worldPoint, boolean inInstance, InstanceTemplates instanceTemplate) {
        return resolveLocation(worldPoint, inInstance, instanceTemplate, false);
    }

    String describeForAi(WorldPoint worldPoint, boolean inInstance, InstanceTemplates instanceTemplate) {
        return resolveLocation(worldPoint, inInstance, instanceTemplate, true);
    }

    private String resolveLocation(WorldPoint worldPoint, boolean inInstance, InstanceTemplates instanceTemplate,
            boolean forAi) {
        if (worldPoint == null) {
            return "Unknown";
        }

        if (inInstance) {
            return instanceTemplate != null
                    ? describeInstanceTemplate(instanceTemplate)
                    : "Instanced area";
        }

        RegionAlias alias = REGION_ALIASES.get(worldPoint.getRegionID());
        if (alias != null) {
            return forAi ? formatAlias(alias) : alias.displayName;
        }

        NamedArea directMatch = findArea(worldPoint);
        if (directMatch != null) {
            return directMatch.name;
        }

        NamedArea regionFallback = findAreaByRegion(worldPoint.getRegionID(), worldPoint.getPlane());
        if (regionFallback != null) {
            return regionFallback.name;
        }

        String underground = resolveUnderground(worldPoint, forAi);
        if (underground != null) {
            return underground;
        }

        NamedArea provinceFallback = findProvinceFallback(worldPoint);
        if (provinceFallback != null) {
            return provinceFallback.name;
        }

        String provinceUnderground = resolveProvinceUnderground(worldPoint, forAi);
        if (provinceUnderground != null) {
            return provinceUnderground;
        }

        return "Unknown area (region " + worldPoint.getRegionID() + ")";
    }

    private NamedArea findArea(WorldPoint point) {
        for (NamedArea area : KNOWN_AREAS) {
            if (point.isInArea2D(area.worldArea)) {
                return area;
            }
        }

        return null;
    }

    private String resolveUnderground(WorldPoint worldPoint, boolean forAi) {
        for (int offset : UNDERGROUND_Y_OFFSETS) {
            if (worldPoint.getY() <= offset) {
                continue;
            }

            WorldPoint normalized = new WorldPoint(worldPoint.getX(), worldPoint.getY() - offset,
                    worldPoint.getPlane());

            RegionAlias alias = REGION_ALIASES.get(normalized.getRegionID());
            if (alias != null) {
                return (forAi ? formatAlias(alias) : alias.displayName) + " (underground)";
            }

            NamedArea area = findArea(normalized);
            if (area != null) {
                return area.name + " (underground)";
            }

            NamedArea regionFallback = findAreaByRegion(normalized.getRegionID(), normalized.getPlane());
            if (regionFallback != null) {
                return regionFallback.name + " (underground)";
            }
        }

        return null;
    }

    private String describeInstanceTemplate(InstanceTemplates instanceTemplate) {
        switch (instanceTemplate) {
            case RAIDS_LOBBY:
                return "Chambers of Xeric - Lobby";
            case RAIDS_START:
                return "Chambers of Xeric - Start";
            case RAIDS_END:
                return "Chambers of Xeric - Great Olm";
            default:
                return humanize(instanceTemplate.name());
        }
    }

    private String humanize(String value) {
        return Arrays.stream(value.split("_"))
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining(" "));
    }

    /**
     * Returns a location string enriched with the canonical OSRS name when it
     * differs from the
     * display name. This is the preferred method for building AI prompt context so
     * the model can
     * reason about well-known area names even when the in-game display differs.
     * e.g. "Kourend Underground (Catacombs of Kourend)"
     */

    private static String formatAlias(RegionAlias alias) {
        if (alias.canonicalName.equals(alias.displayName)) {
            return alias.displayName;
        }

        return alias.displayName + " (also known as: " + alias.canonicalName + ")";
    }

    private static Map<Integer, RegionAlias> loadRegionAliases() {
        Map<Integer, RegionAlias> aliases = new HashMap<>();
        try (InputStream stream = LocationResolver.class.getResourceAsStream(REGION_ALIAS_RESOURCE)) {
            if (stream == null) {
                return Collections.emptyMap();
            }

            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : properties.stringPropertyNames()) {
                try {
                    int regionId = Integer.parseInt(key.trim());
                    RegionAlias alias = parseRegionAliasValue(properties.getProperty(key));
                    if (alias != null) {
                        aliases.put(regionId, alias);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed region ids so a single bad entry does not break all aliases.
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(aliases);
    }

    private static RegionAlias parseRegionAliasValue(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        String[] parts = normalized.split("\\|", 2);
        String displayName = parts[0].trim();
        String canonicalName = parts.length > 1 ? parts[1].trim() : displayName;

        if (displayName.isEmpty()) {
            displayName = canonicalName;
        }

        if (displayName.isEmpty()) {
            return null;
        }

        if (canonicalName.isEmpty()) {
            canonicalName = displayName;
        }

        return new RegionAlias(displayName, canonicalName);
    }

    private NamedArea findAreaByRegion(int regionId, int plane) {
        int rx = regionId >> 8;
        int ry = regionId & 0xFF;
        int regionMinX = rx << 6;
        int regionMinY = ry << 6;

        List<NamedArea> overlapping = new ArrayList<>();
        for (NamedArea area : KNOWN_AREAS) {
            if (area.worldArea.getPlane() == plane) {
                if (overlaps(regionMinX, regionMinY, 64, 64,
                        area.worldArea.getX(), area.worldArea.getY(),
                        area.worldArea.getWidth(), area.worldArea.getHeight())) {
                    overlapping.add(area);
                }
            }
        }

        if (overlapping.isEmpty()) {
            return null;
        }

        overlapping.sort((a, b) -> Integer.compare(
                b.worldArea.getWidth() * b.worldArea.getHeight(),
                a.worldArea.getWidth() * a.worldArea.getHeight()));

        return overlapping.get(0);
    }

    private NamedArea findProvinceFallback(WorldPoint point) {
        for (NamedArea area : PROVINCE_FALLBACKS) {
            if (point.isInArea2D(area.worldArea)) {
                return area;
            }
        }
        return null;
    }

    private String resolveProvinceUnderground(WorldPoint worldPoint, boolean forAi) {
        for (int offset : UNDERGROUND_Y_OFFSETS) {
            if (worldPoint.getY() <= offset) {
                continue;
            }

            WorldPoint normalized = new WorldPoint(worldPoint.getX(), worldPoint.getY() - offset,
                    worldPoint.getPlane());

            NamedArea area = findProvinceFallback(normalized);
            if (area != null) {
                return area.name + " (underground)";
            }
        }
        return null;
    }

    private static boolean overlaps(int x1, int y1, int w1, int h1, int x2, int y2, int w2, int h2) {
        return x1 < x2 + w2 && x1 + w1 > x2 && y1 < y2 + h2 && y1 + h1 > y2;
    }

    private static final class NamedArea {
        private final String name;
        private final WorldArea worldArea;

        private NamedArea(String name, WorldArea worldArea) {
            this.name = name;
            this.worldArea = worldArea;
        }
    }

    private static final class RegionAlias {
        private final String displayName;
        private final String canonicalName;

        private RegionAlias(String displayName, String canonicalName) {
            this.displayName = displayName;
            this.canonicalName = canonicalName;
        }
    }
}
