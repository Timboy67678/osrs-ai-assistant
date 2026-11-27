package com.osrsai.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.runelite.api.InstanceTemplates;
import net.runelite.api.coords.WorldPoint;

/**
 * Names the place at a game coordinate, using location data generated from the OSRS Wiki's
 * Infobox Location pages ({@code wiki-locations.tsv}, built by
 * {@code scripts/generate_wiki_locations.py}).
 * <p>
 * Each wiki location is either an exact outline (polygon, square or rectangle drawn on the wiki
 * map), a circle around the point the wiki map centres on, or an outline estimated from the
 * monster spawns the wiki places there. The most specific match wins: land
 * before water, then the smallest area. Circle matches are reported as "near", since they are
 * estimates. When nothing matches, the nearest known place and its direction are given instead
 * of a guess.
 */
public final class LocationResolver {
    private static final String DATA_RESOURCE = "/com/osrsai/wiki-locations.tsv";
    /** How far to look for the nearest known place when the point matches no location. */
    private static final int NEAREST_SEARCH_TILES = 120;

    private static final List<WikiLocation> LOCATIONS = load();
    private static final Map<String, List<WikiLocation>> BY_NAME = indexByName(LOCATIONS);

    /** Route targets that aren't wiki location pages (bosses, minigames) or need a specific spot. */
    private static final Map<String, WorldPoint> POI_COORDINATES = new HashMap<>();

    static {
        // Banks and guild entrances where the wiki page centre is not where you'd walk to
        POI_COORDINATES.put("edgeville bank", new WorldPoint(3096, 3494, 0));
        POI_COORDINATES.put("lumbridge", new WorldPoint(3222, 3218, 0));
        POI_COORDINATES.put("woodcutting guild", new WorldPoint(1658, 3505, 0));
        POI_COORDINATES.put("mining guild", new WorldPoint(3019, 3339, 0));
        POI_COORDINATES.put("fishing guild", new WorldPoint(2611, 3393, 0));
        POI_COORDINATES.put("warriors guild", new WorldPoint(2843, 3543, 0));

        // Minigames & activities
        POI_COORDINATES.put("wintertodt", new WorldPoint(1630, 3965, 0));
        POI_COORDINATES.put("barrows", new WorldPoint(3565, 3298, 0));
        POI_COORDINATES.put("blast furnace", new WorldPoint(2716, 3713, 0)); // Keldagrim entrance
        POI_COORDINATES.put("motherlode mine", new WorldPoint(3059, 9764, 0)); // Falador mine entrance
        POI_COORDINATES.put("barbarian assault", new WorldPoint(2526, 3577, 0));
        POI_COORDINATES.put("nightmare zone", new WorldPoint(2609, 3115, 0));
        POI_COORDINATES.put("tithe farm", new WorldPoint(1798, 3596, 0));
        POI_COORDINATES.put("giants foundry", new WorldPoint(3361, 3147, 0));
        POI_COORDINATES.put("guardians of the rift", new WorldPoint(3109, 3169, 0)); // Wizard cellar entrance
        POI_COORDINATES.put("pest control", new WorldPoint(2659, 2676, 0)); // Void Knights' Outpost
        POI_COORDINATES.put("tempoross", new WorldPoint(3155, 2835, 0));
        POI_COORDINATES.put("tears of guthix", new WorldPoint(3169, 3173, 0)); // Lumbridge swamp entrance
        POI_COORDINATES.put("chaos altar", new WorldPoint(2948, 3821, 0));
        POI_COORDINATES.put("fortis colosseum", new WorldPoint(1800, 3105, 0));
        POI_COORDINATES.put("perilous moons", new WorldPoint(1450, 3120, 0));

        // Raids & bosses (surface entrances)
        POI_COORDINATES.put("chambers of xeric", new WorldPoint(1255, 3560, 0));
        POI_COORDINATES.put("theatre of blood", new WorldPoint(3659, 3217, 0));
        POI_COORDINATES.put("tombs of amascut", new WorldPoint(3356, 2698, 0));
        POI_COORDINATES.put("chasm of fire", new WorldPoint(1435, 3671, 0));
        POI_COORDINATES.put("vorkath", new WorldPoint(2640, 3693, 0)); // Rellekka boat
        POI_COORDINATES.put("zulrah", new WorldPoint(2200, 3056, 0)); // Zul-Andra boat
        POI_COORDINATES.put("muspah", new WorldPoint(2848, 3964, 0)); // Weiss
        POI_COORDINATES.put("vardorvis", new WorldPoint(1220, 3420, 0));
        POI_COORDINATES.put("duke sucellus", new WorldPoint(1560, 3780, 0));
        POI_COORDINATES.put("tormented demons", new WorldPoint(2540, 3170, 0));
        POI_COORDINATES.put("hueycoatl", new WorldPoint(1412, 3266, 0));
        POI_COORDINATES.put("amoxliatl", new WorldPoint(1402, 3080, 0));
        POI_COORDINATES.put("prifddinas", new WorldPoint(2240, 3260, 0)); // Gates; the city itself is elsewhere
    }

    /**
     * Short place name for a position, e.g. "Rimmington mine".
     *
     * @param worldPoint       the player's {@link WorldPoint}
     * @param inInstance       {@code true} if the player is in an instanced area
     * @param instanceTemplate the active {@link InstanceTemplates} if instanced
     * @return display location string
     */
    public String describe(WorldPoint worldPoint, boolean inInstance, InstanceTemplates instanceTemplate) {
        return resolve(worldPoint, inInstance, instanceTemplate, false);
    }

    /**
     * Place name with its wider area and how certain the match is, for the AI's context, e.g.
     * "Near Rimmington mine (part of Rimmington, Asgarnia)". The wider areas are the wiki's own
     * "location" chain.
     *
     * @param worldPoint       the player's {@link WorldPoint}
     * @param inInstance       {@code true} if the player is in an instanced area
     * @param instanceTemplate the active {@link InstanceTemplates} if instanced
     * @return AI prompt location string
     */
    public String describeForAi(WorldPoint worldPoint, boolean inInstance, InstanceTemplates instanceTemplate) {
        return resolve(worldPoint, inInstance, instanceTemplate, true);
    }

    private String resolve(WorldPoint wp, boolean inInstance, InstanceTemplates template, boolean forAi) {
        if (wp == null) {
            return "Unknown";
        }
        if (inInstance) {
            return template != null ? describeInstanceTemplate(template) : "Instanced area";
        }

        int x = wp.getX();
        int y = wp.getY();
        WikiLocation best = null;
        for (WikiLocation loc : LOCATIONS) {
            if (loc.contains(x, y) && (best == null || loc.moreSpecificThan(best, x, y))) {
                best = loc;
            }
        }
        if (best != null) {
            if (!forAi) {
                return best.name;
            }
            return (best.exact ? best.name : "Near " + best.name) + best.context();
        }

        WikiLocation nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (WikiLocation loc : LOCATIONS) {
            double d = loc.distanceToCentre(x, y);
            if (d < nearestDist && (nearest == null || !loc.water || nearest.water)) {
                nearest = loc;
                nearestDist = d;
            }
        }
        if (nearest == null || nearestDist > NEAREST_SEARCH_TILES) {
            return "Unknown area (region " + wp.getRegionID() + ")";
        }
        String direction = direction(x - nearest.centreX, y - nearest.centreY);
        String text = "Unnamed area " + Math.round(nearestDist) + " tiles " + direction + " of " + nearest.name;
        return forAi ? text + nearest.context().replace(" (part of ", " (which is part of ") : text;
    }

    private static String direction(double dx, double dy) {
        String[] names = { "east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east" };
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        int index = (int) Math.round(((angle + 360) % 360) / 45.0) % 8;
        return names[index];
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

    private static String humanize(String value) {
        StringBuilder sb = new StringBuilder();
        for (String word : value.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1).toLowerCase(Locale.ENGLISH));
        }
        return sb.toString();
    }

    /**
     * Resolves a place name into a route target: hand-picked targets first (bosses, minigames,
     * entrances), then wiki locations by name. For dungeons, the surface entrance is preferred.
     *
     * @param query POI or landmark name
     * @return matching {@link WorldPoint} or {@code null} if not found
     */
    public WorldPoint findCoordinatesByPoiName(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        String cleaned = normalize(query);
        if (cleaned.isEmpty()) {
            return null;
        }

        WorldPoint poi = POI_COORDINATES.get(cleaned);
        if (poi != null) {
            return poi;
        }

        WikiLocation loc = pick(BY_NAME.get(cleaned + " entrance"));
        if (loc == null) {
            loc = pick(BY_NAME.get(cleaned));
        }
        if (loc != null) {
            return new WorldPoint(loc.centreX, loc.centreY, loc.plane);
        }

        for (Map.Entry<String, WorldPoint> entry : POI_COORDINATES.entrySet()) {
            if (cleaned.contains(entry.getKey()) || entry.getKey().contains(cleaned)) {
                return entry.getValue();
            }
        }
        // Partial name match: the shortest wiki name containing the query (e.g. "ardougne" ->
        // "East Ardougne" rather than a building in it), or a name the query contains.
        String bestKey = null;
        for (String key : BY_NAME.keySet()) {
            if ((key.contains(cleaned) || cleaned.contains(key))
                    && (bestKey == null || key.length() < bestKey.length())) {
                bestKey = key;
            }
        }
        loc = bestKey != null ? pick(BY_NAME.get(bestKey)) : null;
        return loc != null ? new WorldPoint(loc.centreX, loc.centreY, loc.plane) : null;
    }

    /** The largest shape of a location, as the most representative target. */
    private static WikiLocation pick(List<WikiLocation> shapes) {
        if (shapes == null || shapes.isEmpty()) {
            return null;
        }
        WikiLocation best = shapes.get(0);
        for (WikiLocation loc : shapes) {
            if (loc.area > best.area) {
                best = loc;
            }
        }
        return best;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ").trim();
    }

    private static List<WikiLocation> load() {
        List<WikiLocation> out = new ArrayList<>();
        try (InputStream stream = LocationResolver.class.getResourceAsStream(DATA_RESOURCE)) {
            if (stream == null) {
                return Collections.emptyList();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                WikiLocation loc = WikiLocation.parse(line);
                if (loc != null) {
                    out.add(loc);
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, List<WikiLocation>> indexByName(List<WikiLocation> locations) {
        Map<String, List<WikiLocation>> map = new HashMap<>();
        for (WikiLocation loc : locations) {
            map.computeIfAbsent(normalize(loc.name), k -> new ArrayList<>()).add(loc);
        }
        return map;
    }

    /** One shape of a wiki location: a polygon outline or a circle around the map centre. */
    private static final class WikiLocation {
        final String name;
        final String[] parents;
        final String region;
        final boolean water;
        final int plane;
        /** Polygon outline (otherwise a circle). */
        final boolean polygon;
        /** Outline drawn on the wiki map, as opposed to a circle or an estimate from monster spawns. */
        final boolean exact;
        final int[] xs;
        final int[] ys;
        final int radius;
        final int centreX;
        final int centreY;
        final double area;

        private WikiLocation(String name, String[] parents, String region, boolean water, int plane,
                int[] xs, int[] ys, int radius, boolean estimated) {
            this.name = name;
            this.parents = parents;
            this.region = region;
            this.water = water;
            this.plane = plane;
            this.xs = xs;
            this.ys = ys;
            this.radius = radius;
            this.polygon = radius < 0;
            this.exact = polygon && !estimated;
            if (polygon) {
                long sx = 0;
                long sy = 0;
                double twiceArea = 0;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    sx += xs[i];
                    sy += ys[i];
                    twiceArea += (double) xs[j] * ys[i] - (double) xs[i] * ys[j];
                }
                this.centreX = (int) (sx / xs.length);
                this.centreY = (int) (sy / xs.length);
                this.area = Math.max(1, Math.abs(twiceArea) / 2);
            } else {
                this.centreX = xs[0];
                this.centreY = ys[0];
                this.area = Math.PI * radius * radius;
            }
        }

        static WikiLocation parse(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 9) {
                return null;
            }
            try {
                String[] parents = f[4].isEmpty() ? new String[0] : f[4].split("\\|");
                boolean water = f[3].startsWith("water");
                boolean estimated = f[3].endsWith("~");
                int plane = Integer.parseInt(f[6]);
                if ("C".equals(f[7])) {
                    String[] c = f[8].split(",");
                    return new WikiLocation(f[0], parents, f[5], water, plane,
                            new int[] { Integer.parseInt(c[0]) }, new int[] { Integer.parseInt(c[1]) },
                            Integer.parseInt(c[2]), estimated);
                }
                String[] pts = f[8].split(";");
                if (pts.length < 3) {
                    return null;
                }
                int[] xs = new int[pts.length];
                int[] ys = new int[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    String[] p = pts[i].split(",");
                    xs[i] = Integer.parseInt(p[0]);
                    ys[i] = Integer.parseInt(p[1]);
                }
                return new WikiLocation(f[0], parents, f[5], water, plane, xs, ys, -1, estimated);
            } catch (RuntimeException e) {
                return null;
            }
        }

        boolean contains(int x, int y) {
            if (!polygon) {
                return distanceToCentre(x, y) <= radius;
            }
            // Ray casting on the tile's centre.
            double px = x + 0.5;
            double py = y + 0.5;
            boolean inside = false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((ys[i] > py) != (ys[j] > py)
                        && px < (double) (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                    inside = !inside;
                }
            }
            return inside;
        }

        double distanceToCentre(int x, int y) {
            double dx = x - centreX;
            double dy = y - centreY;
            return Math.sqrt(dx * dx + dy * dy);
        }

        /** Land beats water; then the smaller area; ties go to the closer centre. */
        boolean moreSpecificThan(WikiLocation other, int x, int y) {
            if (water != other.water) {
                return !water;
            }
            if (Math.abs(area - other.area) > 1e-6) {
                return area < other.area;
            }
            return distanceToCentre(x, y) < other.distanceToCentre(x, y);
        }

        /** " (part of Rimmington, Asgarnia)", or "" when nothing is known. */
        String context() {
            List<String> parts = new ArrayList<>();
            for (String p : parents) {
                if (!p.isEmpty() && !p.equalsIgnoreCase(name) && !parts.contains(p)) {
                    parts.add(p);
                }
            }
            return parts.isEmpty() ? "" : " (part of " + String.join(", ", parts) + ")";
        }
    }
}
