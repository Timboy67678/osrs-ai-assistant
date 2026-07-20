package com.osrsai;

import net.runelite.api.Client;
import net.runelite.api.Varbits;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ItemContainerUtilsTest {

    @Test
    public void testGetSlotName() {
        Assert.assertEquals("Head", ItemContainerUtils.getSlotName(0));
        Assert.assertEquals("Cape", ItemContainerUtils.getSlotName(1));
        Assert.assertEquals("Amulet", ItemContainerUtils.getSlotName(2));
        Assert.assertEquals("Weapon", ItemContainerUtils.getSlotName(3));
        Assert.assertEquals("Body", ItemContainerUtils.getSlotName(4));
        Assert.assertEquals("Shield", ItemContainerUtils.getSlotName(5));
        Assert.assertEquals("Legs", ItemContainerUtils.getSlotName(6));
        Assert.assertEquals("Gloves", ItemContainerUtils.getSlotName(7));
        Assert.assertEquals("Boots", ItemContainerUtils.getSlotName(8));
        Assert.assertEquals("Ring", ItemContainerUtils.getSlotName(9));
        Assert.assertEquals("Ammo", ItemContainerUtils.getSlotName(10));
        Assert.assertEquals("Unknown (99)", ItemContainerUtils.getSlotName(99));
    }

    @Test
    public void testIsIronman() {
        Client client = mock(Client.class);

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(0);
        Assert.assertFalse(ItemContainerUtils.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(1);
        Assert.assertTrue(ItemContainerUtils.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(4);
        Assert.assertTrue(ItemContainerUtils.isIronman(client));

        Assert.assertFalse(ItemContainerUtils.isIronman(null));
    }

    @Test
    public void testSafeItemNameFallback() {
        Assert.assertEquals("Item 4151", ItemContainerUtils.safeItemName(null, 4151));
    }
}
