/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Locale;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"auto bow storer", "pv storer", "bow stash", "automatic storer"})
public final class AutoBowPVHack extends Hack implements UpdateListener
{
    private final TextFieldSetting pvCommand =
            new TextFieldSetting("PV command", "/pv 1",
                    "Command to open your private vault 1.\n"
                            + "Example: /pv 1 or /pv 1;open");

    // ----- State -----
    // We just looted at least one bow and want to open PV
    private boolean wantingPv = false;
    // We have sent the PV command and are waiting for the PV GUI to appear
    private boolean pvPending = false;
    // We are currently in the PV screen and should deposit bows
    private boolean inPv = false;

    public AutoBowPVHack()
    {
        super("AutoBowPV");
        setCategory(Category.OTHER);
        addSetting(pvCommand);
    }

    @Override
    protected void onEnable()
    {
        if(MC.player == null || MC.world == null)
        {
            setEnabled(false);
            return;
        }

        wantingPv = false;
        pvPending = false;
        inPv = false;

        EVENTS.add(UpdateListener.class, this);
    }

    @Override
    protected void onDisable()
    {
        EVENTS.remove(UpdateListener.class, this);
        wantingPv = false;
        pvPending = false;
        inPv = false;
    }

    @Override
    public void onUpdate()
    {
        if(MC.player == null || MC.world == null)
        {
            setEnabled(false);
            return;
        }

        // No GUI open -> reset PV mode until next time
        if(!(MC.currentScreen instanceof HandledScreen<?> screen))
        {
            // If PV was pending but never opened, just cancel
            if(pvPending)
            {
                pvPending = false;
                inPv = false;
            }
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        ClientPlayerEntity player = MC.player;

        // If we were waiting for PV to open and now a container is open,
        // treat this as the PV GUI.
        if(pvPending && !inPv)
        {
            inPv = true;
            pvPending = false;
        }

        // ---- Mode 1: we are in PV -> dump bows from inventory into PV ----
        if(inPv)
        {
            int moved = moveBowsFromPlayerToContainer(handler, player);
            if(moved == 0)
            {
                // nothing left to move -> close PV and go back to normal
                player.closeHandledScreen();
                inPv = false;
                wantingPv = false;
            }
            return;
        }

        // ---- Mode 2: normal container (chest, barrel, menu, etc.) ----
        // Step 1: grab any bows from the container into our inventory
        int grabbed = moveBowsFromContainerToPlayer(handler, player);

        if(grabbed > 0)
        {
            // we successfully looted at least one bow
            wantingPv = true;
        }

        // Step 2: if we want PV and we actually have at least one bow in inv,
        // close the current GUI and open /pv 1
        if(wantingPv && countBowsInInventory() > 0)
        {
            wantingPv = false;
            // Close chest menu first so PV opens cleanly
            player.closeHandledScreen();
            openPv();
        }
    }

    // ===== Core helpers =====

    private void openPv()
    {
        String cmd = pvCommand.getValue().trim();
        if(cmd.isEmpty())
            return;

        sendChat(cmd);
        pvPending = true;
    }

    private int moveBowsFromContainerToPlayer(ScreenHandler h,
                                              ClientPlayerEntity player)
    {
        int moved = 0;
        int total = h.slots.size();
        int playerStart = Math.max(0, total - 36); // last 36 slots = player

        // container slots are [0, playerStart)
        for(int i = 0; i < playerStart; i++)
        {
            ItemStack st = h.getSlot(i).getStack();
            if(st.isEmpty() || !isBow(st))
                continue;

            MC.interactionManager.clickSlot(h.syncId, i, 1,
                    SlotActionType.QUICK_MOVE, player);
            moved++;
        }
        return moved;
    }

    private int moveBowsFromPlayerToContainer(ScreenHandler h,
                                              ClientPlayerEntity player)
    {
        int moved = 0;
        int total = h.slots.size();
        int playerStart = Math.max(0, total - 36); // last 36 slots = player

        // player slots are [playerStart, total)
        for(int i = playerStart; i < total; i++)
        {
            ItemStack st = h.getSlot(i).getStack();
            if(st.isEmpty() || !isBow(st))
                continue;

            MC.interactionManager.clickSlot(h.syncId, i, 1,
                    SlotActionType.QUICK_MOVE, player);
            moved++;
        }
        return moved;
    }

    private int countBowsInInventory()
    {
        int c = 0;
        var inv = MC.player.getInventory();
        int limit = Math.min(inv.size(), 36); // main + hotbar

        for(int i = 0; i < limit; i++)
        {
            ItemStack st = inv.getStack(i);
            if(st.isEmpty())
                continue;
            if(isBow(st))
                c += st.getCount();
        }
        return c;
    }

    // ===== Item matching =====

    private boolean isBow(ItemStack st)
    {
        if(st == null || st.isEmpty())
            return false;

        Item it = st.getItem();

        // Vanilla bows
        if(it == Items.BOW || it == Items.CROSSBOW)
            return true;

        // CE / custom named bows
        String n = safeName(st).toLowerCase(Locale.ROOT);
        // loosen this if your server uses funky names
        return n.contains(" bow");
    }

    // ===== Small utils =====

    private void sendChat(String msg)
    {
        if(MC == null)
            return;
        var nh = MC.getNetworkHandler();
        if(nh == null)
            return;

        msg = msg.trim();
        if(msg.startsWith("/"))
        {
            try
            {
                nh.sendChatCommand(msg.substring(1));
            }catch(Throwable ignored)
            {
                nh.sendChatMessage(msg);
            }
        }else
            nh.sendChatMessage(msg);
    }

    private static String safeName(ItemStack st)
    {
        Text t = st.getName();
        String s = (t == null) ? "" : t.getString();
        return s.replaceAll("§.", "");
    }
}
