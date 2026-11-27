package com.osrsai;

import com.osrsai.util.LocationResolver;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.coords.WorldPoint;
import org.junit.Assert;
import org.junit.Test;

/**
 * Checks the resolver against the bundled wiki location data. Coordinates are chosen well inside
 * each place so the tests survive small changes when the data is regenerated.
 */
public class LocationResolverTest {
    private final LocationResolver resolver = new LocationResolver();

    private String ai(int x, int y) {
        return resolver.describeForAi(new WorldPoint(x, y, 0), false, null);
    }

    @Test
    public void exactWikiOutlinesAreNamedWithoutNear() {
        Assert.assertEquals("Lumbridge Swamp", resolver.describe(new WorldPoint(3170, 3180, 0), false, null));
        Assert.assertEquals("Lumbridge Swamp (part of Lumbridge, Misthalin)", ai(3170, 3180));
        Assert.assertEquals("Draynor bank (part of Draynor Village, Misthalin)", ai(3093, 3243));
    }

    @Test
    public void centrePointMatchesAreMarkedNear() {
        Assert.assertEquals("Near Rimmington mine (part of Rimmington, Asgarnia)", ai(2977, 3240));
        Assert.assertTrue(ai(2530, 3300).startsWith("Near West Ardougne"));
    }

    @Test
    public void undergroundAreasUseTheirOwnCoordinates() {
        Assert.assertTrue(ai(2884, 9800).startsWith("Near Taverley Dungeon"));
        // Outlined from monster spawns; the wiki page only has a map image.
        Assert.assertTrue(ai(1666, 10050).startsWith("Near Catacombs of Kourend"));
        Assert.assertTrue(ai(3240, 10140).contains("Revenant Caves"));
    }

    @Test
    public void mostSpecificPlaceWins() {
        Assert.assertTrue(ai(3222, 3218).startsWith("Lumbridge Castle"));
        Assert.assertTrue(ai(3222, 3218).contains("Lumbridge"));
    }

    @Test
    public void unmatchedPointsNameTheNearestPlaceInsteadOfGuessing() {
        String text = ai(3040, 3600);
        Assert.assertTrue(text, text.startsWith("Unnamed area "));
        Assert.assertTrue(text, text.matches("Unnamed area \\d+ tiles [a-z-]+ of .+"));
        Assert.assertTrue(text, !text.contains("(part of") || text.contains("(which is part of"));
    }

    @Test
    public void describesUnknownAndNullAreas() {
        Assert.assertEquals("Unknown", resolver.describe(null, false, null));
        Assert.assertTrue(resolver.describe(new WorldPoint(2000, 2000, 0), false, null)
                .startsWith("Unknown area (region "));
    }

    @Test
    public void describesKnownInstances() {
        Assert.assertEquals("Chambers of Xeric - Lobby",
                resolver.describe(new WorldPoint(0, 0, 0), true, InstanceTemplates.RAIDS_LOBBY));
        Assert.assertEquals("Instanced area", resolver.describe(new WorldPoint(0, 0, 0), true, null));
    }

    @Test
    public void routeTargetsComeFromWikiLocations() {
        WorldPoint ge = resolver.findCoordinatesByPoiName("Grand Exchange");
        Assert.assertNotNull(ge);
        Assert.assertTrue(Math.abs(ge.getX() - 3165) <= 5 && Math.abs(ge.getY() - 3490) <= 5);

        // The old hand-typed point was 72 tiles off.
        WorldPoint hosidiusBank = resolver.findCoordinatesByPoiName("hosidius bank");
        Assert.assertNotNull(hosidiusBank);
        Assert.assertTrue(Math.abs(hosidiusBank.getX() - 1748) <= 5);

        Assert.assertNotNull(resolver.findCoordinatesByPoiName("Seers' Village bank"));
        Assert.assertNotNull("partial names match a place", resolver.findCoordinatesByPoiName("ardougne"));
    }

    @Test
    public void handPickedRouteTargetsStillWork() {
        WorldPoint barrows = resolver.findCoordinatesByPoiName("Barrows");
        Assert.assertEquals(new WorldPoint(3565, 3298, 0), barrows);
        Assert.assertEquals(new WorldPoint(1435, 3671, 0), resolver.findCoordinatesByPoiName("chasm of fire"));

        Assert.assertNull(resolver.findCoordinatesByPoiName("non_existent_place_xyz"));
        Assert.assertNull(resolver.findCoordinatesByPoiName(null));
        Assert.assertNull(resolver.findCoordinatesByPoiName("   "));
    }
}
