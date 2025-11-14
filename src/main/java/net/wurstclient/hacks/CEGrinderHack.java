/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"CE", "custom enchants", "auto ce", "grind", "books", "stash"})
public final class CEGrinderHack extends Hack implements UpdateListener
{
	// ===== Settings =====
	private final TextFieldSetting ceCommand =
		new TextFieldSetting("Open command", "/ce");
	
	// Tier checkboxes (choose what to buy)
	private final CheckboxSetting tierSimple =
		new CheckboxSetting("Simple (white)", false);
	private final CheckboxSetting tierUnique =
		new CheckboxSetting("Unique (green)", true);
	private final CheckboxSetting tierElite =
		new CheckboxSetting("Elite (blue)", false);
	private final CheckboxSetting tierUltimate =
		new CheckboxSetting("Ultimate (yellow)", true);
	private final CheckboxSetting tierLegendary =
		new CheckboxSetting("Legendary (orange)", true);
	private final CheckboxSetting tierFabled =
		new CheckboxSetting("Fabled (purple)", true);
	
	// Keep/trash rules
	private final TextFieldSetting keepListCsv = new TextFieldSetting(
		"Keep list (comma-separated)",
		"hardened,immortal,overload,gears,wither,spirits,guardians,arrow deflect,obsidian shield,armored");
	
	// Orbs purchase + pacing
	private final SliderSetting batchOrbs = new SliderSetting(
		"Batch orbs per tier", 64, 1, 64, 1, ValueDisplay.INTEGER);
	private final SliderSetting guiWaitTicks = new SliderSetting(
		"GUI wait (ticks)", 10, 2, 40, 1, ValueDisplay.INTEGER);
	
	// Inventory/nearby containers
	private final SliderSetting stashRadius =
		new SliderSetting("Search radius for Barrel/Chest (blocks)", 5, 1, 12,
			1, ValueDisplay.INTEGER);
	
	private final SliderSetting stashWhenUnkeptAtLeast = new SliderSetting(
		"Stash when unkept books ≥", 12, 1, 64, 1, ValueDisplay.INTEGER);
	
	// ===== State =====
	private enum State
	{
		IDLE,
		OPEN_CE_MENU,
		PICK_TIER,
		CONFIRM_BUY,
		WAIT_RETURN_FROM_BUY,
		OPEN_ORBS,
		STASH_USEFUL_OPEN_BARREL,
		STASH_USEFUL_MOVE,
		STASH_TRASH_OPEN_CHEST,
		STASH_TRASH_MOVE,
		NEXT_TIER
	}
	
	private State state = State.IDLE;
	private long waitUntil = 0;
	private long lastCeOpenTick = 0;
	private int orbsThisTier = 0;
	
	private final List<Tier> grindOrder = new ArrayList<>();
	private int currentTierIndex = 0;
	
	private static final class Tier
	{
		final String displayKey; // e.g., "Unique"
		final String orbKey; // e.g., "Unique Enchantment Book"
		
		Tier(String d, String o)
		{
			displayKey = d;
			orbKey = o;
		}
	}
	
	public CEGrinderHack()
	{
		super("CEGrinder");
		setCategory(Category.OTHER);
		
		addSetting(ceCommand);
		addSetting(tierSimple);
		addSetting(tierUnique);
		addSetting(tierElite);
		addSetting(tierUltimate);
		addSetting(tierLegendary);
		addSetting(tierFabled);
		
		addSetting(keepListCsv);
		addSetting(batchOrbs);
		addSetting(guiWaitTicks);
		addSetting(stashRadius);
		addSetting(stashWhenUnkeptAtLeast);
	}
	
	@Override
	protected void onEnable()
	{
		// restore old behaviour
		super.onEnable();
		
		if(MC.player == null)
		{
			setEnabled(false);
			return;
		}
		buildGrindOrder();
		currentTierIndex = 0;
		orbsThisTier = 0;
		state = grindOrder.isEmpty() ? State.IDLE : State.OPEN_CE_MENU;
		waitFor(guiWaitTicks.getValueI());
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		state = State.IDLE;
	}
	
	@Override
	public void onUpdate()
	{
		// comment this out if it spams too much
		MC.inGameHud.getChatHud().addMessage(Text.of("[CE] state=" + state));
		
		if(MC.player == null || MC.world == null)
		{
			setEnabled(false);
			return;
		}
		if(MC.world.getTime() < waitUntil)
			return;
		
		switch(state)
		{
			case IDLE -> setEnabled(false);
			
			case OPEN_CE_MENU ->
			{
				// If any GUI is open, close it first, then try again next tick
				if(MC.currentScreen instanceof HandledScreen<?>)
				{
					MC.player.closeHandledScreen();
					waitFor(5);
					break;
				}
				
				// Just fire the /ce command and go to PICK_TIER
				sendChat(ceCommand.getValue());
				lastCeOpenTick = MC.world.getTime();
				waitFor(guiWaitTicks.getValueI());
				state = State.PICK_TIER;
			}
			
			case PICK_TIER ->
			{
				Tier t = currentTier();
				if(t == null)
				{
					state = State.IDLE;
					break;
				}
				// Try by name; fallback to common pane types
				if(clickSlotByNameContains(t.displayKey) || clickFirstOfTypes(
					Items.WHITE_STAINED_GLASS_PANE,
					Items.LIME_STAINED_GLASS_PANE,
					Items.GREEN_STAINED_GLASS_PANE,
					Items.CYAN_STAINED_GLASS_PANE,
					Items.LIGHT_BLUE_STAINED_GLASS_PANE, Items.ENCHANTED_BOOK))
				{
					waitFor(guiWaitTicks.getValueI());
					state = State.CONFIRM_BUY;
				}else
				{
					// couldn't find tier icon, try re-opening CE
					state = State.OPEN_CE_MENU;
				}
			}
			
			case CONFIRM_BUY ->
			{
				if(clickFirstGreenPaneOrByName("Confirm"))
				{
					waitFor(guiWaitTicks.getValueI());
					state = State.WAIT_RETURN_FROM_BUY;
				}else
				{
					state = State.OPEN_CE_MENU;
				}
			}
			
			case WAIT_RETURN_FROM_BUY ->
			{
				// If inventory is tight or many unkept books, stop buying and
				// start opening
				if(countFreeSlots() <= 2
					|| countUnkeptBooksInInv() >= stashWhenUnkeptAtLeast
						.getValueI())
				{
					state = State.OPEN_ORBS;
					waitFor(3); // small delay before first orb use
					break;
				}
				
				Tier t = currentTier();
				if(t == null)
				{
					state = State.IDLE;
					break;
				}
				
				// How many orbs of this tier do we currently have?
				orbsThisTier = countOrbsInInv(t.orbKey);
				
				// If we have ANY orbs, immediately go to opening them.
				// Only stay in the buy loop if we still have 0 orbs.
				if(orbsThisTier > 0)
				{
					state = State.OPEN_ORBS;
					waitFor(3); // quick hand-off into OPEN_ORBS
				}else
				{
					// Still no orbs – keep buying this tier
					state = State.PICK_TIER;
					waitFor(guiWaitTicks.getValueI()); // normal GUI pacing
				}
			}
			
			case OPEN_ORBS ->
			{
				if(isInventoryFull()
					|| countUnkeptBooksInInv() >= stashWhenUnkeptAtLeast
						.getValueI())
				{
					state = State.STASH_USEFUL_OPEN_BARREL;
					waitFor(guiWaitTicks.getValueI());
					break;
				}
				
				Tier t = currentTier();
				if(t == null)
				{
					state = State.IDLE;
					break;
				}
				
				// Close any GUI before using
				if(MC.currentScreen instanceof HandledScreen<?>)
				{
					MC.player.closeHandledScreen();
					waitFor(5);
					break;
				}
				
				// opens ONE orb, state machine loops until none
				forceOrbInHand(t.orbKey);
				
				boolean opened = openOneOrbFromInv(t.orbKey);
				if(opened)
				{
					waitFor(4 + randBetween(0, 3));
					break;
				}
				if(countOrbsInInv(t.orbKey) > 0)
				{
					waitFor(2);
					break;
				}
				
				// no orbs left -> stash then next tier
				state = State.STASH_USEFUL_OPEN_BARREL;
				waitFor(guiWaitTicks.getValueI());
			}
			
			case STASH_USEFUL_OPEN_BARREL ->
			{
				if(openNearestBarrel(stashRadius.getValueI()))
				{
					waitFor(guiWaitTicks.getValueI());
					state = State.STASH_USEFUL_MOVE;
				}else
				{
					// If no barrel found, skip to trash chest phase anyway
					state = State.STASH_TRASH_OPEN_CHEST;
				}
			}
			
			case STASH_USEFUL_MOVE ->
			{
				int moved =
					stashFromPlayerInvToOpenContainer(this::isUsefulBookOrDust);
				waitFor(guiWaitTicks.getValueI());
				closeIfScreen();
				state = State.STASH_TRASH_OPEN_CHEST;
			}
			
			case STASH_TRASH_OPEN_CHEST ->
			{
				if(openNearestChest(stashRadius.getValueI()))
				{
					waitFor(guiWaitTicks.getValueI());
					state = State.STASH_TRASH_MOVE;
				}else
				{
					// No chest found: if we still have orbs for this tier, keep
					// opening them
					Tier t = currentTier();
					if(t != null && countOrbsInInv(t.orbKey) > 0)
						state = State.OPEN_ORBS;
					else
						state = State.NEXT_TIER;
				}
			}
			
			case STASH_TRASH_MOVE ->
			{
				int moved =
					stashFromPlayerInvToOpenContainer(this::isTrashCEBook);
				waitFor(guiWaitTicks.getValueI());
				closeIfScreen();
				
				// After stashing trash, if there are still orbs of this tier,
				// go back to opening them
				Tier t = currentTier();
				if(t != null && countOrbsInInv(t.orbKey) > 0)
					state = State.OPEN_ORBS;
				else
					state = State.NEXT_TIER;
			}
			
			case NEXT_TIER ->
			{
				currentTierIndex =
					(currentTierIndex + 1) % Math.max(1, grindOrder.size());
				orbsThisTier = 0;
				if(grindOrder.isEmpty())
				{
					state = State.IDLE;
					setEnabled(false);
				}else
				{
					state = State.OPEN_CE_MENU;
					waitFor(guiWaitTicks.getValueI());
				}
			}
		}
	}
	
	// ===== Core helpers =====
	// Force-select an orb into hand BEFORE opening orbs
	private void forceOrbInHand(String orbNameKey)
	{
		if(MC.player == null)
			return;
		
		String key = orbNameKey.toLowerCase(Locale.ROOT);
		
		java.util.function.Predicate<ItemStack> isOrb = s -> {
			if(s == null || s.isEmpty())
				return false;
			return safeName(s).toLowerCase(Locale.ROOT).contains(key);
		};
		
		try
		{
			// force replace hand even if holding a sword
			net.wurstclient.util.InventoryUtils.selectItem(isOrb, 36, true);
		}catch(Throwable ignored)
		{}
	}
	
	// Treat anything that looks like an orb (by name) as an orb, regardless of
	// item type
	private boolean isOrbLike(ItemStack st)
	{
		if(st == null || st.isEmpty())
			return false;
		
		String n = safeName(st).toLowerCase(Locale.ROOT);
		if(n.contains("enchantment book"))
			return true;
		if(n.contains("mystery book"))
			return true;
		if((n.contains("simple") || n.contains("unique") || n.contains("elite")
			|| n.contains("ultimate") || n.contains("legendary")
			|| n.contains("fabled")) && n.contains("book"))
			return true;
		
		return false;
	}
	
	private boolean clickFirstOfTypes(Item... types)
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return false;
		clearCursorIfAny(screen);
		ScreenHandler h = screen.getScreenHandler();
		
		int best = -1, bestX = Integer.MAX_VALUE, bestY = Integer.MAX_VALUE;
		outer: for(int i = 0; i < h.slots.size(); i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty())
				continue;
			for(Item t : types)
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
		MC.interactionManager.clickSlot(h.syncId, best, 0,
			SlotActionType.PICKUP, MC.player);
		return true;
	}
	
	// make sure we are not holding a book before opening containers
	// make sure we are not holding an orb / enchantment book before opening
	// containers
	private void ensureNonBookInHand()
	{
		if(MC.player == null)
			return;
		
		ItemStack hand = MC.player.getMainHandStack();
		// Already holding something safe -> fine
		if(isGoodContainerItem(hand))
			return;
		
		// Prefer swords/axes that are also "safe"
		java.util.function.Predicate<ItemStack> isWeaponOrToolSafe = s -> {
			if(!isGoodContainerItem(s))
				return false;
			Item it = s.getItem();
			return it == Items.DIAMOND_SWORD || it == Items.NETHERITE_SWORD
				|| it == Items.IRON_SWORD || it == Items.STONE_SWORD
				|| it == Items.DIAMOND_AXE || it == Items.NETHERITE_AXE
				|| it == Items.IRON_AXE;
		};
		
		// Fallback: any safe non-orb, non-book item
		java.util.function.Predicate<ItemStack> isAnySafe =
			this::isGoodContainerItem;
		
		try
		{
			boolean switched = net.wurstclient.util.InventoryUtils
				.selectItem(isWeaponOrToolSafe, 36, true);
			
			if(!switched)
			{
				net.wurstclient.util.InventoryUtils.selectItem(isAnySafe, 36,
					true);
			}
		}catch(Throwable ignored)
		{
			// if InventoryUtils fails, just keep current hand
		}
	}
	
	// "Safe" item for opening containers = not empty, not enchanted book, not
	// orb-like
	private boolean isGoodContainerItem(ItemStack s)
	{
		if(s == null || s.isEmpty())
			return false;
		if(s.getItem() == Items.ENCHANTED_BOOK)
			return false;
		if(isOrbLike(s))
			return false;
		return true;
	}
	
	private boolean openNearestBarrel(int radius)
	{
		BlockPos bp =
			findNearest(be -> be instanceof BarrelBlockEntity, radius);
		if(bp == null)
			return false;
		
		ensureNonBookInHand();
		
		Vec3d hit = Vec3d.ofCenter(bp);
		MC.interactionManager.interactBlock(MC.player, Hand.MAIN_HAND,
			new BlockHitResult(hit, Direction.UP, bp, false));
		return true;
	}
	
	private boolean openNearestChest(int radius)
	{
		BlockPos bp = findNearest(be -> be instanceof ChestBlockEntity, radius);
		if(bp == null)
			return false;
		
		ensureNonBookInHand();
		
		Vec3d hit = Vec3d.ofCenter(bp);
		MC.interactionManager.interactBlock(MC.player, Hand.MAIN_HAND,
			new BlockHitResult(hit, Direction.UP, bp, false));
		return true;
	}
	
	private BlockPos findNearest(java.util.function.Predicate<BlockEntity> pred,
		int radius)
	{
		BlockPos me = MC.player.getBlockPos();
		BlockPos best = null;
		double bestD2 = Double.MAX_VALUE;
		int r = Math.max(1, radius);
		for(int dx = -r; dx <= r; dx++)
			for(int dy = -1; dy <= 1; dy++)
				for(int dz = -r; dz <= r; dz++)
				{
					BlockPos p = me.add(dx, dy, dz);
					BlockEntity be = MC.world.getBlockEntity(p);
					if(be != null && pred.test(be))
					{
						double d2 = p.getSquaredDistance(me);
						if(d2 < bestD2)
						{
							bestD2 = d2;
							best = p;
						}
					}
				}
		return best;
	}
	
	private int stashFromPlayerInvToOpenContainer(
		java.util.function.Predicate<ItemStack> select)
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return 0;
		ScreenHandler h = screen.getScreenHandler();
		int moved = 0;
		int total = h.slots.size();
		int start = Math.max(0, total - 36); // player inv region
		for(int i = start; i < total; i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(!st.isEmpty() && select.test(st))
			{
				MC.interactionManager.clickSlot(h.syncId, i, 1,
					SlotActionType.QUICK_MOVE, MC.player);
				moved++;
			}
		}
		return moved;
	}
	
	// ===== Inventory / books =====
	
	// EXACTLY the old implementation
	private int countOrbsInInv(String orbNameKey)
	{
		int c = 0;
		var inv = MC.player.getInventory();
		String key = orbNameKey.toLowerCase(Locale.ROOT);
		for(int i = 0; i < inv.size(); i++)
		{
			ItemStack st = inv.getStack(i);
			if(!st.isEmpty()
				&& safeName(st).toLowerCase(Locale.ROOT).contains(key))
				c += st.getCount();
		}
		return c;
	}
	
	// opens one orb (select into hand, right-click), state machine loops -> ALL
	private boolean openOneOrbFromInv(String orbNameKey)
	{
		final ClientPlayerEntity p = MC.player;
		if(p == null)
			return false;
		
		// Never operate while a GUI is open
		if(MC.currentScreen instanceof HandledScreen<?>)
		{
			p.closeHandledScreen();
			return false; // retry next tick
		}
		
		// Predicate that matches any stack whose name contains the orb key
		final String key = orbNameKey.toLowerCase(Locale.ROOT);
		java.util.function.Predicate<ItemStack> isOrb = s -> {
			if(s == null || s.isEmpty())
				return false;
			return safeName(s).toLowerCase(Locale.ROOT).contains(key);
		};
		
		// Select the orb into hand like AutoMace does
		int beforeCount = p.getMainHandStack().getCount();
		String beforeName = safeName(p.getMainHandStack());
		
		boolean selected = false;
		try
		{
			// search whole inventory, allow hotbar replace = true
			selected =
				net.wurstclient.util.InventoryUtils.selectItem(isOrb, 36, true);
		}catch(Throwable ignored)
		{}
		
		if(!selected)
		{
			return false; // no orb found anywhere
		}
		
		// Use it (AutoMace style)
		try
		{
			net.wurstclient.WurstClient.IMC.getInteractionManager()
				.rightClickItem();
		}catch(Throwable t)
		{
			try
			{
				MC.interactionManager.interactItem(p, Hand.MAIN_HAND);
			}catch(Throwable ignored)
			{}
		}
		
		// Give the server a couple ticks to convert orb -> book
		waitFor(guiWaitTicks.getValueI() + 1 + randBetween(0, 2));
		
		// Heuristic: if still the same item & count, try one more gentle
		// attempt
		ItemStack now = p.getMainHandStack();
		if(safeName(now).equals(beforeName) && now.getCount() == beforeCount)
		{
			try
			{
				net.wurstclient.WurstClient.IMC.getInteractionManager()
					.rightClickItem();
			}catch(Throwable t)
			{
				try
				{
					MC.interactionManager.interactItem(p, Hand.MAIN_HAND);
				}catch(Throwable ignored)
				{}
			}
			waitFor(2);
		}
		
		return true;
	}
	
	private boolean isUsefulBookOrDust(ItemStack st)
	{
		return isKeeperBook(st) || isSecretDust(st);
	}
	
	private boolean isTrashCEBook(ItemStack st)
	{
		if(st.isEmpty() || st.getItem() != Items.ENCHANTED_BOOK)
			return false;
		if(isKeeperBook(st))
			return false;
		if(isOrbBook(st))
			return false;
		return true;
	}
	
	private boolean isOrbBook(ItemStack st)
	{
		if(st.isEmpty() || st.getItem() != Items.ENCHANTED_BOOK)
			return false;
		String n = safeName(st).toLowerCase(Locale.ROOT);
		if(n.contains("enchantment book"))
			return true;
		if(n.contains("mystery book"))
			return true;
		if((n.contains("simple") || n.contains("unique") || n.contains("elite")
			|| n.contains("ultimate") || n.contains("legendary")
			|| n.contains("fabled")) && n.contains("book"))
			return true;
		return false;
	}
	
	private Set<String> keepKeywordsLower()
	{
		return Arrays.stream(keepListCsv.getValue().split(","))
			.map(s -> s.trim().toLowerCase(Locale.ROOT))
			.filter(s -> !s.isEmpty()).collect(Collectors.toSet());
	}
	
	private boolean isKeeperBook(ItemStack st)
	{
		if(st.isEmpty())
			return false;
		
		// Only treat actual enchanted books as CE books
		if(st.getItem() != Items.ENCHANTED_BOOK)
			return false;
		
		String name = safeName(st).toLowerCase(Locale.ROOT);
		
		// Don't accidentally keep orb / mystery books
		if(isOrbBook(st))
			return false;
		
		for(String k : keepKeywordsLower())
		{
			if(!k.isEmpty() && name.contains(k))
				return true; // e.g. "wither" matches "wither iv"
		}
		
		return false;
	}
	
	private boolean isSecretDust(ItemStack st)
	{
		if(st.isEmpty())
			return false;
		String n = safeName(st).toLowerCase(Locale.ROOT);
		return n.contains("secret dust")
			|| (st.getItem() == Items.FIRE_CHARGE && n.contains("dust"));
	}
	
	// Only treat main inventory + hotbar as storage (0–35)
	private boolean isInventoryFull()
	{
		var inv = MC.player.getInventory();
		int limit = Math.min(inv.size(), 36); // 36 = 27 main + 9 hotbar
		for(int i = 0; i < limit; i++)
		{
			if(inv.getStack(i).isEmpty())
				return false;
		}
		return true;
	}
	
	private int countFreeSlots()
	{
		int f = 0;
		var inv = MC.player.getInventory();
		int limit = Math.min(inv.size(), 36); // ignore armor/offhand
		for(int i = 0; i < limit; i++)
		{
			if(inv.getStack(i).isEmpty())
				f++;
		}
		return f;
	}
	
	private int countUnkeptBooksInInv()
	{
		int c = 0;
		var inv = MC.player.getInventory();
		
		for(int i = 0; i < inv.size(); i++)
		{
			ItemStack st = inv.getStack(i);
			if(st.isEmpty())
				continue;
			
			// Only care about actual enchanted books
			if(st.getItem() != Items.ENCHANTED_BOOK)
				continue;
			
			// Ignore CE orbs / mystery books in this count
			if(isOrbBook(st))
				continue;
			
			// Only count non-keeper result books as "unkept"
			if(!isKeeperBook(st))
				c += st.getCount();
		}
		return c;
	}
	
	// ===== Small utils =====
	
	private void waitFor(int ticks)
	{
		if(MC.world != null)
			waitUntil = MC.world.getTime() + Math.max(1, ticks);
	}
	
	private static int randBetween(int minIncl, int maxIncl)
	{
		return ThreadLocalRandom.current().nextInt(minIncl, maxIncl + 1);
	}
	
	private void buildGrindOrder()
	{
		grindOrder.clear();
		if(tierSimple.isChecked())
			grindOrder.add(new Tier("Simple", "Simple Enchantment Book"));
		if(tierUnique.isChecked())
			grindOrder.add(new Tier("Unique", "Unique Enchantment Book"));
		if(tierElite.isChecked())
			grindOrder.add(new Tier("Elite", "Elite Enchantment Book"));
		if(tierUltimate.isChecked())
			grindOrder.add(new Tier("Ultimate", "Ultimate Enchantment Book"));
		if(tierLegendary.isChecked())
			grindOrder.add(new Tier("Legendary", "Legendary Enchantment Book"));
		if(tierFabled.isChecked())
			grindOrder.add(new Tier("Fabled", "Fabled Enchantment Book"));
	}
	
	private Tier currentTier()
	{
		if(currentTierIndex < 0 || currentTierIndex >= grindOrder.size())
			return null;
		return grindOrder.get(currentTierIndex);
	}
	
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
	
	private void closeIfScreen()
	{
		if(MC.currentScreen instanceof HandledScreen<?>)
			MC.player.closeHandledScreen();
	}
	
	private boolean clickSlotByNameContains(String needleLower)
	{
		needleLower = needleLower.toLowerCase(Locale.ROOT);
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return false;
		ScreenHandler h = screen.getScreenHandler();
		for(int i = 0; i < h.slots.size(); i++)
		{
			ItemStack st = h.getSlot(i).getStack();
			if(st.isEmpty())
				continue;
			String name = safeName(st);
			if(name.toLowerCase(Locale.ROOT).contains(needleLower))
			{
				MC.interactionManager.clickSlot(h.syncId, i, 0,
					SlotActionType.PICKUP, MC.player);
				return true;
			}
		}
		return false;
	}
	
	private boolean clickFirstGreenPaneOrByName(String mayContain)
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return false;
		ScreenHandler h = screen.getScreenHandler();
		for(int i = 0; i < h.slots.size(); i++)
		{
			Item it = h.getSlot(i).getStack().getItem();
			if(it == Items.LIME_STAINED_GLASS_PANE
				|| it == Items.GREEN_STAINED_GLASS_PANE)
			{
				MC.interactionManager.clickSlot(h.syncId, i, 0,
					SlotActionType.PICKUP, MC.player);
				return true;
			}
		}
		if(mayContain != null && !mayContain.isEmpty())
			return clickSlotByNameContains(mayContain.toLowerCase(Locale.ROOT));
		return false;
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
	
	private static String safeName(ItemStack st)
	{
		Text t = st.getName();
		String s = (t == null) ? "" : t.getString();
		return s.replaceAll("§.", "");
	}
}
