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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.entity.EquipmentSlot;
import net.wurstclient.util.ItemUtils;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"auto bow storer", "pv storer", "bow stash", "automatic storer"})
public final class AutoBowPVHack extends Hack implements UpdateListener
{
	private final TextFieldSetting pvCommand = new TextFieldSetting(
		"PV command", "/pv 1", "Command to open your private vault 1.\n"
			+ "Example: /pv 1 or /pv 1;open");
	
	// What to move
	private final CheckboxSetting moveBows =
		new CheckboxSetting("Move Bows", true);
	private final CheckboxSetting moveSwords =
		new CheckboxSetting("Move Swords", false);
	private final CheckboxSetting moveMaces =
		new CheckboxSetting("Move Maces", false);
	
	private final CheckboxSetting moveHelmet =
		new CheckboxSetting("Move Helmets", false);
	private final CheckboxSetting moveChestplate =
		new CheckboxSetting("Move Chestplates", false);
	private final CheckboxSetting moveLeggings =
		new CheckboxSetting("Move Leggings", false);
	private final CheckboxSetting moveBoots =
		new CheckboxSetting("Move Boots", false);
	
	// Optional name filter (for custom items)
	private final TextFieldSetting nameKeywords = new TextFieldSetting(
		"Custom name keywords (CSV)", "",
		"Optional. If set, items whose name contains ANY keyword will be moved.\n"
			+ "Example: ce bow, god sword, immortal");
	
	// ----- State -----
	private boolean wantingPv = false;
	private boolean pvPending = false;
	private boolean inPv = false;
	private boolean reopenAfterPv = false;
	private int reopenDelay = 0;
	
	public AutoBowPVHack()
	{
		super("AutoBowPV");
		setCategory(Category.OTHER);
		
		addSetting(pvCommand);
		
		addSetting(moveBows);
		addSetting(moveSwords);
		addSetting(moveMaces);
		addSetting(moveHelmet);
		addSetting(moveChestplate);
		addSetting(moveLeggings);
		addSetting(moveBoots);
		
		addSetting(nameKeywords);
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
		reopenAfterPv = false;
		reopenDelay = 0;
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		wantingPv = false;
		pvPending = false;
		inPv = false;
		reopenAfterPv = false;
		reopenDelay = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
		{
			setEnabled(false);
			return;
		}
		
		// ----- No GUI open -----
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
		{
			if(pvPending && !inPv)
			{
				pvPending = false;
				wantingPv = false;
			}
			
			if(reopenAfterPv)
			{
				if(reopenDelay > 0)
					reopenDelay--;
				else
				{
					rightClickFront();
					reopenAfterPv = false;
				}
			}
			return;
		}
		
		ScreenHandler handler = screen.getScreenHandler();
		ClientPlayerEntity player = MC.player;
		
		if(pvPending && !inPv)
		{
			inPv = true;
			pvPending = false;
		}
		
		// ---- Mode 1: in PV -> dump selected items from inventory into PV ----
		if(inPv)
		{
			moveSelectedFromPlayerToContainer(handler, player);
			
			if(countSelectedInInventory() == 0)
			{
				player.closeHandledScreen();
				inPv = false;
				wantingPv = false;
				pvPending = false;
				
				reopenAfterPv = true;
				reopenDelay = 4;
			}
			return;
		}
		
		// ---- Mode 2: normal container ----
		int grabbed = moveSelectedFromContainerToPlayer(handler, player);
		
		if(grabbed > 0)
			wantingPv = true;
		
		if(wantingPv && countSelectedInInventory() > 0)
		{
			wantingPv = false;
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
	
	private void rightClickFront()
	{
		if(MC.player == null || MC.interactionManager == null)
			return;
		
		HitResult target = MC.crosshairTarget;
		if(target instanceof BlockHitResult bhr)
			MC.interactionManager.interactBlock(MC.player, Hand.MAIN_HAND, bhr);
		else
			MC.interactionManager.interactItem(MC.player, Hand.MAIN_HAND);
	}
	
	private int moveSelectedFromContainerToPlayer(ScreenHandler h,
		ClientPlayerEntity player)
	{
		int moved = 0;
		int total = h.slots.size();
		int playerStart = Math.max(0, total - 36);
		
		for(int i = 0; i < playerStart; i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty() || !shouldMove(st))
				continue;
			
			MC.interactionManager.clickSlot(h.syncId, i, 1,
				SlotActionType.QUICK_MOVE, player);
			moved++;
		}
		return moved;
	}
	
	private int moveSelectedFromPlayerToContainer(ScreenHandler h,
		ClientPlayerEntity player)
	{
		int moved = 0;
		int total = h.slots.size();
		int playerStart = Math.max(0, total - 36);
		
		for(int i = playerStart; i < total; i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty() || !shouldMove(st))
				continue;
			
			MC.interactionManager.clickSlot(h.syncId, i, 1,
				SlotActionType.QUICK_MOVE, player);
			moved++;
		}
		return moved;
	}
	
	private int countSelectedInInventory()
	{
		int c = 0;
		var inv = MC.player.getInventory();
		int limit = Math.min(inv.size(), 36);
		
		for(int i = 0; i < limit; i++)
		{
			ItemStack st = inv.getStack(i);
			if(st.isEmpty())
				continue;
			if(shouldMove(st))
				c += st.getCount();
		}
		return c;
	}
	
	// ===== Matching =====
	
	private boolean shouldMove(ItemStack st)
	{
		if(st == null || st.isEmpty())
			return false;
		
		Item it = st.getItem();
		
		// Optional keyword override (moves ANY item whose name matches)
		if(matchesKeyword(st))
			return true;
		
		if(moveBows.isChecked() && isBow(st))
			return true;
		
		if(moveSwords.isChecked()
			&& (it == Items.WOODEN_SWORD || it == Items.STONE_SWORD
				|| it == Items.IRON_SWORD || it == Items.GOLDEN_SWORD
				|| it == Items.DIAMOND_SWORD || it == Items.NETHERITE_SWORD))
			return true;
		
		if(moveMaces.isChecked() && it == Items.MACE)
			return true;
		
		if(moveHelmet.isChecked() && isHelmet(st))
			return true;
		
		if(moveChestplate.isChecked() && isChestplate(st))
			return true;
		
		if(moveLeggings.isChecked() && isLeggings(st))
			return true;
		
		if(moveBoots.isChecked() && isBoots(st))
			return true;
		
		return false;
	}
	
	private boolean matchesKeyword(ItemStack st)
	{
		String csv = nameKeywords.getValue();
		if(csv == null || csv.trim().isEmpty())
			return false;
		
		String name = safeName(st).toLowerCase(Locale.ROOT);
		for(String k : csv.split(","))
		{
			String kk = k.trim().toLowerCase(Locale.ROOT);
			if(!kk.isEmpty() && name.contains(kk))
				return true;
		}
		return false;
	}
	
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
	
	private boolean isHelmet(ItemStack st)
	{
		return ItemUtils.getArmorSlot(st.getItem()) == EquipmentSlot.HEAD;
	}
	
	private boolean isChestplate(ItemStack st)
	{
		return ItemUtils.getArmorSlot(st.getItem()) == EquipmentSlot.CHEST;
	}
	
	private boolean isLeggings(ItemStack st)
	{
		return ItemUtils.getArmorSlot(st.getItem()) == EquipmentSlot.LEGS;
	}
	
	private boolean isBoots(ItemStack st)
	{
		return ItemUtils.getArmorSlot(st.getItem()) == EquipmentSlot.FEET;
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
