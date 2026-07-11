package com.osrsai;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.coords.WorldPoint;
import org.junit.Assert;
import org.junit.Test;

public class LocationResolverTest
{
    private final LocationResolver locationResolver = new LocationResolver();

    @Test
    public void describesKnownSurfaceAreas()
    {
        Assert.assertEquals("Grand Exchange", locationResolver.describe(new WorldPoint(3165, 3486, 0), false, null));
        Assert.assertEquals("Lumbridge", locationResolver.describe(new WorldPoint(3222, 3218, 0), false, null));
        Assert.assertEquals("Falador", locationResolver.describe(new WorldPoint(2966, 3379, 0), false, null));
        Assert.assertEquals("Canifis", locationResolver.describe(new WorldPoint(3495, 3484, 0), false, null));
        Assert.assertEquals("Rellekka", locationResolver.describe(new WorldPoint(2665, 3645, 0), false, null));
        Assert.assertEquals("Shayzien", locationResolver.describe(new WorldPoint(1512, 3620, 0), false, null));
        Assert.assertEquals("Wilderness", locationResolver.describe(new WorldPoint(3040, 3600, 0), false, null));
    }

    @Test
    public void describesAreasUsingRegionBasedFallback()
    {
        // Coordinate 3766, 3915 is outside the exact box of Fossil Island (which goes to y=3912),
        // but within region 14909 (which overlaps with Fossil Island).
        Assert.assertEquals("Fossil Island", locationResolver.describe(new WorldPoint(3766, 3915, 0), false, null));

        // Coordinate 3190, 3490 is outside the Grand Exchange box, but in its overlapping region.
        // It should resolve to the larger overlapping area, "Varrock".
        Assert.assertEquals("Varrock", locationResolver.describe(new WorldPoint(3190, 3490, 0), false, null));
    }

    @Test
    public void describesUnknownAndNullAreas()
    {
        Assert.assertEquals("Unknown", locationResolver.describe(null, false, null));
        Assert.assertTrue(locationResolver.describe(new WorldPoint(2000, 2000, 0), false, null)
                .startsWith("Unknown area (region "));
    }

    @Test
    public void describesUndergroundAreasFromSurfaceOffset()
    {
        String description = locationResolver.describe(new WorldPoint(1459, 9889, 0), false, null);
        Assert.assertEquals("Kourend Underground", description);
    }

    @Test
    public void describeForAiIncludesCanonicalNameWhenDifferent()
    {
        String description = locationResolver.describeForAi(new WorldPoint(1459, 9889, 0), false, null);
        Assert.assertTrue(description.contains("Kourend Underground"));
        Assert.assertTrue(description.contains("Catacombs of Kourend"));
    }

    @Test
    public void describeForAiReturnsPlainNameWhenNoDifference()
    {
        String description = locationResolver.describeForAi(new WorldPoint(3165, 3486, 0), false, null);
        Assert.assertEquals("Grand Exchange", description);
    }

    @Test
    public void describesStarterRegionAliasesFromResource()
    {
        Assert.assertEquals("Taverley Dungeon", locationResolver.describe(new WorldPoint(2890, 9810, 0), false, null));
    }

    @Test
    public void describesNewRegionAliasesFromResource()
    {
        Assert.assertEquals("Stronghold of Security (Vault of War)",
                locationResolver.describe(new WorldPoint(1866, 5194, 0), false, null));
        Assert.assertEquals("Catacombs of Kourend",
                locationResolver.describe(new WorldPoint(1610, 9994, 0), false, null));
        Assert.assertEquals("Prifddinas",
                locationResolver.describe(new WorldPoint(2186, 3402, 0), false, null));
        Assert.assertEquals("Duke Sucellus Arena (Ghorrock Dungeon)",
                locationResolver.describe(new WorldPoint(3018, 6410, 0), false, null));
        Assert.assertEquals("Taverley Dungeon",
                locationResolver.describe(new WorldPoint(2868, 9817, 0), false, null));
        Assert.assertEquals("Entrana Dungeon",
                locationResolver.describe(new WorldPoint(2834, 9835, 0), false, null));
    }

    @Test
    public void describesKnownInstances()
    {
        Assert.assertEquals("Chambers of Xeric - Lobby",
                locationResolver.describe(new WorldPoint(0, 0, 0), true, InstanceTemplates.RAIDS_LOBBY));
        Assert.assertEquals("Instanced area",
                locationResolver.describe(new WorldPoint(0, 0, 0), true, null));
    }

    @Test
    public void parsesStructuredRegionAliasValues() throws Exception
    {
        Method parseMethod = LocationResolver.class.getDeclaredMethod("parseRegionAliasValue", String.class);
        parseMethod.setAccessible(true);

        Object alias = parseMethod.invoke(null, "Kourend Underground|Catacombs of Kourend");
        Assert.assertNotNull(alias);

        Field displayField = alias.getClass().getDeclaredField("displayName");
        displayField.setAccessible(true);
        Field canonicalField = alias.getClass().getDeclaredField("canonicalName");
        canonicalField.setAccessible(true);

        Assert.assertEquals("Kourend Underground", displayField.get(alias));
        Assert.assertEquals("Catacombs of Kourend", canonicalField.get(alias));
    }
}
