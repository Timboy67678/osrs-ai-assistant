package com.osrsai.context;

import net.runelite.api.Client;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/**
 * The player's boat state, read from the game's own Sailing varbits.
 * <p>
 * Only values with a clear meaning are read. Speed, wind direction, sail trim and the boat's
 * name and type are not exposed in a form this plugin can decode, so they are left out rather
 * than guessed. Values are -1 when the game hasn't sent them.
 * <p>
 * Must be read on the client thread.
 */
public final class SailingState {

    /** The cargo hold inventories of the player's boats, in boat order. */
    public static final int[] CARGO_HOLDS = {
            InventoryID.SAILING_BOAT_1_CARGOHOLD,
            InventoryID.SAILING_BOAT_2_CARGOHOLD,
            InventoryID.SAILING_BOAT_3_CARGOHOLD,
            InventoryID.SAILING_BOAT_4_CARGOHOLD,
            InventoryID.SAILING_BOAT_5_CARGOHOLD };

    public final boolean onBoat;
    public final int hullHp;
    public final int maxHullHp;
    public final int repairKits;
    public final int windCharges;
    public final int maxWindCharges;
    public final boolean atHelm;
    public final boolean atSails;

    private SailingState(boolean onBoat, int hullHp, int maxHullHp, int repairKits, int windCharges,
            int maxWindCharges, boolean atHelm, boolean atSails) {
        this.onBoat = onBoat;
        this.hullHp = hullHp;
        this.maxHullHp = maxHullHp;
        this.repairKits = repairKits;
        this.windCharges = windCharges;
        this.maxWindCharges = maxWindCharges;
        this.atHelm = atHelm;
        this.atSails = atSails;
    }

    /**
     * Reads the current state. Returns a "not on a boat" state if the client is unavailable.
     *
     * @param client game client
     * @return the player's sailing state
     */
    public static SailingState read(Client client) {
        if (client == null) {
            return new SailingState(false, -1, -1, -1, -1, -1, false, false);
        }
        boolean onBoat = varbit(client, VarbitID.SAILING_PLAYER_IS_ON_PLAYER_BOAT) > 0;
        return new SailingState(
                onBoat,
                varbit(client, VarbitID.SAILING_SIDEPANEL_BOAT_HP),
                varbit(client, VarbitID.SAILING_SIDEPANEL_BOAT_HP_MAX),
                varbit(client, VarbitID.SAILING_SIDEPANEL_REPAIRKITS),
                varbit(client, VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CHARGES),
                varbit(client, VarbitID.SAILING_SIDEPANEL_BOAT_MAX_WIND_CHARGES),
                varbit(client, VarbitID.SAILING_SIDEPANEL_PLAYER_AT_HELM) > 0,
                varbit(client, VarbitID.SAILING_SIDEPANEL_PLAYER_AT_SAILS) > 0);
    }

    /** Whether the hull HP values are populated. */
    public boolean hasHullHp() {
        return maxHullHp > 0 && hullHp >= 0;
    }

    private static int varbit(Client client, int id) {
        try {
            return client.getVarbitValue(id);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
