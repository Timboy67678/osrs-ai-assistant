package com.osrsai;

import com.osrsai.util.Utilities;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UtilitiesTest {

    @Test
    public void testDescribeAccountType() {
        Assert.assertEquals("Normal", Utilities.describeAccountTypeFromVarbit(0));
        Assert.assertEquals("Ironman", Utilities.describeAccountTypeFromVarbit(1));
        Assert.assertEquals("Ultimate Ironman (UIM)", Utilities.describeAccountTypeFromVarbit(2));
        Assert.assertEquals("Hardcore Ironman (HCIM)", Utilities.describeAccountTypeFromVarbit(3));
        Assert.assertEquals("Group Ironman (GIM)", Utilities.describeAccountTypeFromVarbit(4));
        Assert.assertEquals("Hardcore Group Ironman (HGIM)", Utilities.describeAccountTypeFromVarbit(5));
        Assert.assertEquals("Unranked Group Ironman (UGIM)", Utilities.describeAccountTypeFromVarbit(6));
        Assert.assertEquals("Unknown", Utilities.describeAccountTypeFromVarbit(99));
        Assert.assertEquals("Unknown", Utilities.describeAccountTypeFromVarbit(null));
    }

    @Test
    public void testAccountTypeEnumMethods() {
        Assert.assertEquals(Utilities.AccountType.NORMAL, Utilities.AccountType.fromVarbit(0));
        Assert.assertEquals(Utilities.AccountType.IRONMAN, Utilities.AccountType.fromVarbit(1));
        Assert.assertEquals(Utilities.AccountType.UNKNOWN, Utilities.AccountType.fromVarbit(null));
        Assert.assertEquals(Utilities.AccountType.UNKNOWN, Utilities.AccountType.fromVarbit(-1));
        Assert.assertEquals(Utilities.AccountType.UNKNOWN, Utilities.AccountType.fromVarbit(99));

        Assert.assertFalse(Utilities.AccountType.NORMAL.isIronman());
        Assert.assertFalse(Utilities.AccountType.UNKNOWN.isIronman());
        Assert.assertTrue(Utilities.AccountType.IRONMAN.isIronman());
        Assert.assertTrue(Utilities.AccountType.ULTIMATE_IRONMAN.isIronman());
        Assert.assertTrue(Utilities.AccountType.HARDCORE_IRONMAN.isIronman());
        Assert.assertTrue(Utilities.AccountType.GROUP_IRONMAN.isIronman());
        Assert.assertTrue(Utilities.AccountType.HARDCORE_GROUP_IRONMAN.isIronman());
        Assert.assertTrue(Utilities.AccountType.UNRANKED_GROUP_IRONMAN.isIronman());
    }

    @Test
    public void testIsIronman() {
        Client client = mock(Client.class);

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(0);
        Assert.assertFalse(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(1);
        Assert.assertTrue(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(2);
        Assert.assertTrue(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(3);
        Assert.assertTrue(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(4);
        Assert.assertTrue(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(5);
        Assert.assertTrue(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(6);
        Assert.assertTrue(Utilities.isIronman(client));

        when(client.getVarbitValue(Varbits.ACCOUNT_TYPE)).thenReturn(7);
        Assert.assertFalse(Utilities.isIronman(client));

        Assert.assertFalse(Utilities.isIronman(null));
    }

    @Test
    public void testDescribeSpellbook() {
        Assert.assertEquals("Standard", Utilities.describeSpellbook(0));
        Assert.assertEquals("Ancient Magicks", Utilities.describeSpellbook(1));
        Assert.assertEquals("Lunar", Utilities.describeSpellbook(2));
        Assert.assertEquals("Arceuus", Utilities.describeSpellbook(3));
        Assert.assertEquals("Unknown (4)", Utilities.describeSpellbook(4));
    }

    @Test
    public void testGetSlotName() {
        Assert.assertEquals("Head", Utilities.getSlotName(0));
        Assert.assertEquals("Cape", Utilities.getSlotName(1));
        Assert.assertEquals("Amulet", Utilities.getSlotName(2));
        Assert.assertEquals("Weapon", Utilities.getSlotName(3));
        Assert.assertEquals("Body", Utilities.getSlotName(4));
        Assert.assertEquals("Shield", Utilities.getSlotName(5));
        Assert.assertEquals("Legs", Utilities.getSlotName(6));
        Assert.assertEquals("Gloves", Utilities.getSlotName(7));
        Assert.assertEquals("Boots", Utilities.getSlotName(8));
        Assert.assertEquals("Ring", Utilities.getSlotName(9));
        Assert.assertEquals("Ammo", Utilities.getSlotName(10));
        Assert.assertEquals("Unknown (99)", Utilities.getSlotName(99));
    }

    @Test
    public void testDescribeDiaryStatus() {
        Assert.assertEquals("Not Started", Utilities.describeDiaryStatus(0, 10));
        Assert.assertEquals("In Progress (4/10 tasks)", Utilities.describeDiaryStatus(4, 10));
        Assert.assertEquals("Completed", Utilities.describeDiaryStatus(10, 10));
        Assert.assertEquals("Completed", Utilities.describeDiaryStatus(12, 10));
    }

    @Test
    public void testGetDiaryStatusWithClient() {
        Client client = mock(Client.class);
        when(client.getVarbitValue(100)).thenReturn(0);
        when(client.getVarbitValue(101)).thenReturn(5);
        when(client.getVarbitValue(102)).thenReturn(10);

        Assert.assertEquals("Not Started", Utilities.getDiaryStatus(client, 100, 10));
        Assert.assertEquals("In Progress (5/10 tasks)", Utilities.getDiaryStatus(client, 101, 10));
        Assert.assertEquals("Completed", Utilities.getDiaryStatus(client, 102, 10));
        Assert.assertEquals("Not Started", Utilities.getDiaryStatus(null, 100, 10));
    }

    @Test
    public void testTruncate() {
        Assert.assertEquals("", Utilities.truncate(null, 10));
        Assert.assertEquals("", Utilities.truncate("test", 0));
        Assert.assertEquals("test", Utilities.truncate("test", 10));
        Assert.assertEquals("Hello...", Utilities.truncate("Hello World", 8));
        Assert.assertEquals("He", Utilities.truncate("Hello World", 2));
    }
}
