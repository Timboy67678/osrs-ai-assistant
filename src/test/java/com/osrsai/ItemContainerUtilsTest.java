package com.osrsai;

import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ItemContainerUtilsTest {

    @Test
    public void testSafeItemNameFallback() {
        Assert.assertEquals("Item 4151", ItemContainerUtils.safeItemName(null, 4151));
    }

    @Test
    public void testFindItemIdInContainersGlobalSearchFallback() {
        net.runelite.client.game.ItemManager itemManager = mock(net.runelite.client.game.ItemManager.class);
        net.runelite.http.api.item.ItemPrice itemPrice = mock(net.runelite.http.api.item.ItemPrice.class);
        net.runelite.api.ItemComposition comp = mock(net.runelite.api.ItemComposition.class);

        when(itemPrice.getId()).thenReturn(22951);
        when(comp.getPlaceholderTemplateId()).thenReturn(-1);
        when(itemManager.search("Boots of brimstone")).thenReturn(java.util.Collections.singletonList(itemPrice));
        when(itemManager.getItemComposition(22951)).thenReturn(comp);

        Integer resolvedId = ItemContainerUtils.findItemIdInContainers(null, itemManager, "Boots of brimstone");
        Assert.assertEquals(Integer.valueOf(22951), resolvedId);
    }
}
