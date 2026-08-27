package com.osrsai.navigation;

import com.google.gson.JsonObject;
import com.osrsai.OsrsAiConfig;
import com.osrsai.util.LocationResolver;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles navigation communication with the RuneLite Shortest Path plugin via
 * PluginMessage events,
 * coordinate normalization for underground maps, and target marker dispatch.
 */
@Slf4j
public class ShortestPathHandler {
    private static final int MAX_SURFACE_WORLD_Y_COORDINATE = 5000;
    private static final int OSRS_UNDERGROUND_Y_OFFSET_STEP = 6400;

    private final EventBus eventBus;
    private final OsrsAiConfig config;
    private final LocationResolver locationResolver;

    public ShortestPathHandler(EventBus eventBus, OsrsAiConfig config, LocationResolver locationResolver) {
        this.eventBus = eventBus;
        this.config = config;
        this.locationResolver = locationResolver;
    }

    /**
     * Normalizes a WorldPoint coordinate for ShortestPath pathfinding.
     * If the Y coordinate is an underground offset (y >=
     * MAX_SURFACE_WORLD_Y_COORDINATE),
     * normalizes it down to the corresponding surface level coordinate so that the
     * map
     * overlay draws correctly on the surface world map.
     *
     * @param point coordinate to normalize
     * @return surface-level coordinate
     */
    public WorldPoint normalizeShortestPathPoint(WorldPoint point) {
        if (point == null) {
            return null;
        }
        int y = point.getY();
        if (y >= MAX_SURFACE_WORLD_Y_COORDINATE) {
            int surfaceY = y;
            while (surfaceY >= MAX_SURFACE_WORLD_Y_COORDINATE) {
                surfaceY -= OSRS_UNDERGROUND_Y_OFFSET_STEP;
            }
            if (surfaceY > 0) {
                log.info("Normalized underground coordinate WorldPoint({}, {}, {}) to surface WorldPoint({}, {}, {})",
                        point.getX(), y, point.getPlane(), point.getX(), surfaceY, point.getPlane());
                return new WorldPoint(point.getX(), surfaceY, point.getPlane());
            }
        }
        return point;
    }

    /**
     * Dispatches a target coordinate to the Shortest Path plugin.
     *
     * @param targetPoint     destination coordinate
     * @param startPoint      optional custom start coordinate
     * @param configOverrides optional configuration overrides
     * @return {@code true} if event was posted successfully; {@code false}
     *         otherwise
     */
    public boolean setShortestPathTarget(WorldPoint targetPoint, WorldPoint startPoint,
            Map<String, Object> configOverrides) {
        try {
            if (eventBus != null && targetPoint != null) {
                targetPoint = normalizeShortestPathPoint(targetPoint);
                startPoint = normalizeShortestPathPoint(startPoint);

                Map<String, Object> data = new HashMap<>();
                if (startPoint != null) {
                    data.put("start", startPoint);
                }
                data.put("target", targetPoint);
                if (configOverrides != null && !configOverrides.isEmpty()) {
                    data.put("config", configOverrides);
                }
                eventBus.post(new PluginMessage("shortestpath", "path", data));
                log.info("Posted ShortestPath PluginMessage path event for target {} (start: {}, config: {})",
                        targetPoint, startPoint, configOverrides);
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to set Shortest Path target via PluginMessage event bus", e);
        }
        return false;
    }

    public boolean setShortestPathTarget(WorldPoint targetPoint) {
        return setShortestPathTarget(targetPoint, null, null);
    }

    /**
     * Clears any active route overlay in the Shortest Path plugin.
     *
     * @return {@code true} if event was posted successfully; {@code false}
     *         otherwise
     */
    public boolean clearShortestPathTarget() {
        try {
            if (eventBus != null) {
                eventBus.post(new PluginMessage("shortestpath", "clear"));
                log.info("Posted ShortestPath PluginMessage clear event");
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to post Shortest Path clear event", e);
        }
        return false;
    }

    /**
     * Executes the 'set_shortest_path_target' tool.
     *
     * @param args JSON arguments with target coordinates or POI name
     * @return JSON response string with status and outcome message
     */
    public String executeSetShortestPathTarget(JsonObject args) {
        JsonObject result = new JsonObject();
        if (args == null) {
            result.addProperty("status", "error");
            result.addProperty("message", "Missing required parameters.");
            return result.toString();
        }

        try {
            if (config != null && !config.useShortestPath()) {
                result.addProperty("status", "error");
                result.addProperty("message",
                        "Shortest Path target setting is disabled in the OSRS AI Assistant plugin config.");
                return result.toString();
            }

            WorldPoint targetPoint = null;
            String locationName = "Destination";

            if (args.has("x") && args.has("y") && !args.get("x").isJsonNull() && !args.get("y").isJsonNull()) {
                int x = args.get("x").getAsInt();
                int y = args.get("y").getAsInt();
                int plane = (args.has("plane") && !args.get("plane").isJsonNull()) ? args.get("plane").getAsInt() : 0;
                targetPoint = new WorldPoint(x, y, plane);
                if (args.has("locationName") && !args.get("locationName").isJsonNull()) {
                    locationName = args.get("locationName").getAsString();
                }
            } else {
                String poiQuery = null;
                if (args.has("poiName") && !args.get("poiName").isJsonNull()) {
                    poiQuery = args.get("poiName").getAsString();
                } else if (args.has("locationName") && !args.get("locationName").isJsonNull()) {
                    poiQuery = args.get("locationName").getAsString();
                }

                if (poiQuery != null && locationResolver != null) {
                    targetPoint = locationResolver.findCoordinatesByPoiName(poiQuery);
                    locationName = poiQuery;
                }
            }

            if (targetPoint == null) {
                result.addProperty("status", "error");
                result.addProperty("message",
                        "Missing coordinates (x, y) or unknown POI name. Please provide valid coordinates or a known POI name.");
                return result.toString();
            }

            WorldPoint startPoint = null;
            if (args.has("startX") && args.has("startY") && !args.get("startX").isJsonNull()
                    && !args.get("startY").isJsonNull()) {
                int startX = args.get("startX").getAsInt();
                int startY = args.get("startY").getAsInt();
                int startPlane = (args.has("startPlane") && !args.get("startPlane").isJsonNull())
                        ? args.get("startPlane").getAsInt()
                        : 0;
                startPoint = new WorldPoint(startX, startY, startPlane);
            }

            Map<String, Object> configOverrides = new HashMap<>();
            if (args.has("avoidWilderness") && !args.get("avoidWilderness").isJsonNull()) {
                configOverrides.put("avoidWilderness", args.get("avoidWilderness").getAsBoolean());
            }

            boolean success = setShortestPathTarget(targetPoint, startPoint,
                    configOverrides.isEmpty() ? null : configOverrides);

            if (success) {
                result.addProperty("status", "success");
                result.addProperty("message",
                        "Successfully set Shortest Path target to " + locationName + " at " + targetPoint.toString()
                                + (startPoint != null ? " (starting from " + startPoint.toString() + ")" : "")
                                + (!configOverrides.isEmpty() ? " with config overrides: " + configOverrides : ""));
            } else {
                result.addProperty("status", "error");
                result.addProperty("message", "Shortest Path plugin is not installed or enabled in RuneLite.");
            }
        } catch (Exception e) {
            log.error("Failed to execute set_shortest_path_target tool", e);
            result.addProperty("status", "error");
            result.addProperty("message", "Exception: " + e.getMessage());
        }

        return result.toString();
    }

    /**
     * Executes the 'clear_shortest_path_target' tool.
     *
     * @param args JSON arguments (unused)
     * @return JSON response string with status and outcome message
     */
    public String executeClearShortestPathTarget(JsonObject args) {
        JsonObject result = new JsonObject();
        try {
            if (config != null && !config.useShortestPath()) {
                result.addProperty("status", "error");
                result.addProperty("message", "Shortest Path integration is disabled in plugin config.");
                return result.toString();
            }

            boolean success = clearShortestPathTarget();
            if (success) {
                result.addProperty("status", "success");
                result.addProperty("message", "Successfully cleared Shortest Path target and route overlay.");
            } else {
                result.addProperty("status", "error");
                result.addProperty("message", "Failed to send clear message to Shortest Path plugin.");
            }
        } catch (Exception e) {
            log.error("Failed to execute clear_shortest_path_target tool", e);
            result.addProperty("status", "error");
            result.addProperty("message", "Exception: " + e.getMessage());
        }
        return result.toString();
    }
}
