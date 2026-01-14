/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.WurstClient;

import java.util.*;
import java.util.stream.Collectors;

@SearchTags({"tinkerer", "salvage", "debug", "gui", "ce"})
public final class TinkererOpenerHack extends Hack implements UpdateListener
{
	private static final net.minecraft.client.MinecraftClient MC =
		WurstClient.MC;
	
	// ===== Settings =====
	private final TextFieldSetting ceCommand =
		new TextFieldSetting("Open CE command", "/ce");
	
	// Names we try to click inside the CE menu to open salvage/tinkerer
	private final TextFieldSetting salvageEntryNames = new TextFieldSetting(
		"Salvage entry names (csv)", "open tinkerer,anvil,tinkerer,salvage");
	
	// If your server uses fixed slots for the entry, force-click them (csv of
	// ints)
	private final TextFieldSetting forceClickTopSlots =
		new TextFieldSetting("Force-click these TOP slot IDs (csv)", "");
	
	// Heuristic material targets to try (csv of vanilla item ids)
	private final TextFieldSetting materialIdsCsv = new TextFieldSetting(
		"Material fallbacks (csv)",
		"anvil,enchanting_table,name_tag,paper,lime_stained_glass_pane,green_stained_glass_pane");
	
	private final SliderSetting guiWaitTicks = new SliderSetting(
		"GUI wait (ticks)", 10, 2, 40, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting extraSwitchWait = new SliderSetting(
		"Extra switch wait (ticks)", 12, 0, 40, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting verbose =
		new CheckboxSetting("Verbose debug in chat", true);
	
	private final CheckboxSetting logGuiDumpOnEveryRetry =
		new CheckboxSetting("Dump GUI on every retry", true);
	
	private final CheckboxSetting tryAllClickTypes =
		new CheckboxSetting("Try PICKUP, QUICK_MOVE, SWAP, THROW", true);
	
	// ===== State machine =====
	private enum State
	{
		OPEN_CE,
		WAIT_MENU,
		CLICK_SALVAGE,
		WAIT_TINKERER,
		RETRY,
		DONE
	}
	
	private State state = State.OPEN_CE;
	private long waitUntil = 0;
	private int baselineSyncId = -1;
	private long switchDeadline = 0;
	private boolean extendedOnce = false;
	
	public TinkererOpenerHack()
	{
		super("Tinkerer Opener v2");
		setCategory(Category.OTHER);
		addSetting(ceCommand);
		addSetting(salvageEntryNames);
		addSetting(forceClickTopSlots);
		addSetting(materialIdsCsv);
		addSetting(guiWaitTicks);
		addSetting(extraSwitchWait);
		addSetting(verbose);
		addSetting(logGuiDumpOnEveryRetry);
		addSetting(tryAllClickTypes);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null || MC.world == null)
		{
			log("Not in a world; disabling.");
			setEnabled(false);
			return;
		}
		state = State.OPEN_CE;
		waitFor(guiWaitTicks.getValueI());
		startSwitchWatch(extraSwitchWait.getValueI());
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
		{
			setEnabled(false);
			return;
		}
		if(worldTime() < waitUntil)
			return;
		
		switch(state)
		{
			case OPEN_CE ->
			{
				log("Sending command: " + ceCommand.getValue());
				sendChat(ceCommand.getValue());
				startSwitchWatch(extraSwitchWait.getValueI());
				waitFor(guiWaitTicks.getValueI());
				state = State.WAIT_MENU;
			}
			
			case WAIT_MENU ->
			{
				if(switchedToNewScreen())
				{
					log("Menu likely opened (syncId changed). Attempting to click salvage entry...");
					waitFor(2);
					state = State.CLICK_SALVAGE;
				}else if(switchTimedOut())
				{
					log("Menu didn't open in time. RETRY.");
					state = State.RETRY;
				}else
				{
					waitFor(1);
				}
			}
			
			case CLICK_SALVAGE ->
			{
				if(!(MC.currentScreen instanceof HandledScreen<?> screen))
				{
					log("No GUI open; re-running /ce.");
					state = State.OPEN_CE;
					break;
				}
				clearCursorIfAny(screen);
				
				boolean clicked = false;
				
				// A) Force slots first (for stubborn servers)
				int topEnd = topAreaEnd(screen);
				for(Integer forced : parseInts(forceClickTopSlots.getValue()))
				{
					if(forced != null && forced >= 0 && forced < topEnd)
					{
						clicked |=
							robustClick(screen.getScreenHandler(), forced);
						if(clicked)
						{
							log("Force-clicked TOP slot #" + forced);
							break;
						}
					}
				}
				
				// B) Name matches
				if(!clicked)
				{
					clicked =
						clickByNames(screen, salvageEntryNames.getValue());
				}
				
				// C) Material fallbacks
				if(!clicked)
				{
					clicked =
						clickByMaterials(screen, materialIdsCsv.getValue());
				}
				
				if(clicked)
				{
					log("Clicked a salvage/tinkerer entry. Waiting for the tinkerer screen...");
					startSwitchWatch(extraSwitchWait.getValueI() + 6);
					state = State.WAIT_TINKERER;
					waitFor(guiWaitTicks.getValueI());
				}else
				{
					log("Couldn't find a suitable entry.");
					if(logGuiDumpOnEveryRetry.isChecked())
						dumpTopGui(screen);
					state = State.RETRY;
				}
			}
			
			case WAIT_TINKERER ->
			{
				if(isTinkererLikeScreen())
				{
					log("SUCCESS: Tinkerer/Salvage GUI detected. Stopping.");
					state = State.DONE;
					break;
				}
				if(switchedToNewScreen() && !extendedOnce)
				{
					switchDeadline = worldTime() + guiWaitTicks.getValueI();
					extendedOnce = true;
					waitFor(2);
					break;
				}
				if(switchTimedOut())
				{
					log("Timed out waiting for tinkerer; RETRY.");
					if(MC.currentScreen instanceof HandledScreen<?> s)
						dumpTopGui(s);
					state = State.RETRY;
					break;
				}
				waitFor(1);
			}
			
			case RETRY ->
			{
				if(MC.currentScreen instanceof HandledScreen<?>)
				{
					MC.player.closeHandledScreen();
					waitFor(3);
				}
				state = State.OPEN_CE;
			}
			
			case DONE -> setEnabled(false);
		}
	}
	
	// ===== Helpers =====
	private void log(String s)
	{
		if(verbose.isChecked())
			MC.inGameHud.getChatHud().addMessage(Text.of("[TinkOpener] " + s));
	}
	
	private long worldTime()
	{
		return (MC.world == null) ? 0 : MC.world.getTime();
	}
	
	private void waitFor(int ticks)
	{
		waitUntil = worldTime() + Math.max(1, ticks);
	}
	
	private void startSwitchWatch(int extraTicks)
	{
		baselineSyncId = currentSyncId();
		extendedOnce = false;
		switchDeadline = worldTime() + Math.max(guiWaitTicks.getValueI(), 4)
			+ Math.max(0, extraTicks);
	}
	
	private int currentSyncId()
	{
		if(MC.currentScreen instanceof HandledScreen<?> s)
			return s.getScreenHandler().syncId;
		return -1;
	}
	
	private boolean switchedToNewScreen()
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> s))
			return false;
		return baselineSyncId != -1
			&& s.getScreenHandler().syncId != baselineSyncId;
	}
	
	private boolean switchTimedOut()
	{
		return worldTime() >= switchDeadline;
	}
	
	private void sendChat(String msg)
	{
		if(MC.player == null)
			return;
		var nh = MC.player.networkHandler;
		if(nh == null)
			return;
		msg = msg.trim();
		if(msg.startsWith("/"))
		{
			try
			{
				nh.sendChatCommand(msg.substring(1));
			}catch(Throwable t)
			{
				nh.sendChatMessage(msg);
			}
		}else
			nh.sendChatMessage(msg);
	}
	
	private void clearCursorIfAny(HandledScreen<?> screen)
	{
		if(MC.player == null || MC.interactionManager == null)
			return;
		if(!screen.getScreenHandler().getCursorStack().isEmpty())
		{
			MC.interactionManager.clickSlot(screen.getScreenHandler().syncId,
				-999, 0, SlotActionType.PICKUP, MC.player);
		}
	}
	
	private int topAreaEnd(HandledScreen<?> screen)
	{
		return Math.max(0, screen.getScreenHandler().slots.size() - 36);
	}
	
	private boolean clickByNames(HandledScreen<?> screen, String csv)
	{
		ScreenHandler h = screen.getScreenHandler();
		Set<String> needles = Arrays.stream(csv.split(","))
			.map(s -> s.trim().toLowerCase(Locale.ROOT))
			.filter(s -> !s.isEmpty()).collect(Collectors.toSet());
		
		for(int i = 0; i < h.slots.size(); i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty())
				continue;
			String nm = safeName(st).toLowerCase(Locale.ROOT);
			for(String n : needles)
			{
				if(nm.contains(n))
				{
					if(robustClick(h, i))
					{
						log("Clicked by name match: #" + i + " -> " + nm);
						return true;
					}
				}
			}
		}
		return false;
	}
	
	private boolean clickByMaterials(HandledScreen<?> screen, String csv)
	{
		ScreenHandler h = screen.getScreenHandler();
		Set<Item> mats = parseItemIds(csv);
		
		int best = -1, bestX = Integer.MAX_VALUE, bestY = Integer.MAX_VALUE;
		outer: for(int i = 0; i < h.slots.size(); i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty())
				continue;
			for(Item t : mats)
			{
				if(st.isOf(t))
				{
					var s = h.getSlot(i);
					if(s.x < bestX || (s.x == bestX && s.y < bestY))
					{
						bestX = s.x;
						bestY = s.y;
						best = i;
					}
					continue outer;
				}
			}
		}
		if(best == -1)
			return false;
		if(robustClick(h, best))
		{
			log("Clicked by material fallback: #" + best + " ("
				+ safeName(h.getSlot(best).getStack()) + ")");
			return true;
		}
		return false;
	}
	
	/** Try multiple click paths that some GUIs require. */
	private boolean robustClick(ScreenHandler h, int slotIdx)
	{
		try
		{
			// 1) Normal left PICKUP
			MC.interactionManager.clickSlot(h.syncId, slotIdx, 0,
				SlotActionType.PICKUP, MC.player);
			if(afterClickHeuristic())
				return true;
			
			if(!tryAllClickTypes.isChecked())
				return false;
			
			// 2) QUICK_MOVE (shift-click)
			MC.interactionManager.clickSlot(h.syncId, slotIdx, 1,
				SlotActionType.QUICK_MOVE, MC.player);
			if(afterClickHeuristic())
				return true;
				
			// 3) Try SWAP with a few hotbar slots (some menus bind actions to
			// number keys)
			for(int hot = 0; hot < 3; hot++)
			{
				MC.interactionManager.clickSlot(h.syncId, slotIdx, hot,
					SlotActionType.SWAP, MC.player);
				if(afterClickHeuristic())
					return true;
			}
			
			// 4) THROW on the slot (rarely used, but some servers listen for
			// it)
			MC.interactionManager.clickSlot(h.syncId, slotIdx, 0,
				SlotActionType.THROW, MC.player);
			if(afterClickHeuristic())
				return true;
		}catch(Throwable ignored)
		{}
		return false;
	}
	
	/**
	 * After-click check: if screen switched or a typical confirm/dust pane
	 * appeared.
	 */
	private boolean afterClickHeuristic()
	{
		// Give MC a tick or two to react next onUpdate; here we just return
		// true if screen changed immediately
		if(switchedToNewScreen())
			return true;
		if(MC.currentScreen instanceof HandledScreen<?> s)
		{
			return tinkererHeuristicPresent(s.getScreenHandler());
		}
		return false;
	}
	
	private boolean tinkererHeuristicPresent(ScreenHandler h)
	{
		int lime = 0, green = 0, red = 0, fire = 0;
		int top = Math.max(0, h.slots.size() - 36);
		for(int i = 0; i < top; i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty())
				continue;
			Item it = st.getItem();
			if(it == Items.LIME_STAINED_GLASS_PANE)
				lime++;
			else if(it == Items.GREEN_STAINED_GLASS_PANE)
				green++;
			else if(it == Items.RED_STAINED_GLASS_PANE)
				red++;
			else if(it == Items.FIRE_CHARGE)
				fire++;
		}
		return (lime + green) >= 1 || red >= 1 || fire >= 1; // crude, good
																// enough for
																// post-click
																// check
	}
	
	private boolean isTinkererLikeScreen()
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return false;
		String title = screen.getTitle() == null ? ""
			: screen.getTitle().getString().toLowerCase(Locale.ROOT);
		if(title.contains("tinkerer") || title.contains("salvage")
			|| title.contains("open tinkerer"))
			return true;
		return tinkererHeuristicPresent(screen.getScreenHandler());
	}
	
	private void dumpTopGui(HandledScreen<?> screen)
	{
		ScreenHandler h = screen.getScreenHandler();
		int topEnd = Math.max(0, h.slots.size() - 36);
		log("GUI dump (top=" + topEnd + ", total=" + h.slots.size() + "):");
		for(int i = 0; i < h.slots.size(); i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			String nm = safeName(st);
			String area = (i < topEnd) ? "TOP" : "INV";
			if(!st.isEmpty())
				log("  [" + area + "] #" + i + " : " + nm + " x"
					+ st.getCount());
		}
	}
	
	private static String safeName(ItemStack st)
	{
		if(st == null || st.isEmpty())
			return "";
		Text t = st.getName();
		String s = (t == null) ? "" : t.getString();
		return s.replaceAll("§.", "");
	}
	
	private Set<Item> parseItemIds(String csv)
	{
		Set<Item> out = new HashSet<>();
		for(String raw : csv.split(","))
		{
			String id = raw.trim().toLowerCase(Locale.ROOT);
			if(id.isEmpty())
				continue;
			// Map a few known names manually (no registry lookups to keep it
			// simple/mappings-agnostic)
			Item i = switch(id)
			{
				case "anvil" -> Items.ANVIL;
				case "enchanting_table" -> Items.ENCHANTING_TABLE;
				case "name_tag" -> Items.NAME_TAG;
				case "paper" -> Items.PAPER;
				case "lime_stained_glass_pane" -> Items.LIME_STAINED_GLASS_PANE;
				case "green_stained_glass_pane" -> Items.GREEN_STAINED_GLASS_PANE;
				case "red_stained_glass_pane" -> Items.RED_STAINED_GLASS_PANE;
				default -> null;
			};
			if(i != null)
				out.add(i);
		}
		// Always include common confirms as backup
		out.add(Items.LIME_STAINED_GLASS_PANE);
		out.add(Items.GREEN_STAINED_GLASS_PANE);
		out.add(Items.ANVIL);
		out.add(Items.PAPER);
		return out;
	}
	
	private List<Integer> parseInts(String csv)
	{
		List<Integer> out = new ArrayList<>();
		for(String s : csv.split(","))
		{
			s = s.trim();
			if(s.isEmpty())
				continue;
			try
			{
				out.add(Integer.parseInt(s));
			}catch(Throwable ignored)
			{}
		}
		return out;
	}
}
