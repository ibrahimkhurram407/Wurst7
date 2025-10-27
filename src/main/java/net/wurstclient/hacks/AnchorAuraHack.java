/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FacingSetting;
import net.wurstclient.settings.FacingSetting.Facing;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.filterlists.AnchorAuraFilterList;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"anchor aura", "CrystalAura", "crystal aura"})
public final class AnchorAuraHack extends Hack implements UpdateListener
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final IMinecraftClient IMC = WurstClient.IMC;
	
	// ===== Existing settings =====
	private final SliderSetting range =
		new SliderSetting("Range", "description.wurst.setting.anchoraura.range",
			6, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoPlace =
		new CheckboxSetting("Auto-place anchors",
			"description.wurst.setting.anchoraura.auto-place_anchors", true);
	
	private final FacingSetting faceBlocks =
		FacingSetting.withPacketSpam("Face anchors",
			"description.wurst.setting.anchoraura.face_anchors", Facing.OFF);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"description.wurst.setting.anchoraura.check_line_of_sight", false);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private final EnumSetting<TakeItemsFrom> takeItemsFrom =
		new EnumSetting<>("Take items from",
			"description.wurst.setting.anchoraura.take_items_from",
			TakeItemsFrom.values(), TakeItemsFrom.INVENTORY);
	
	private final EntityFilterList entityFilters =
		AnchorAuraFilterList.create();
	
	/* ===== NEW: player targeting controls ===== */
	private enum PlayerTargetMode
	{
		ALL,
		ONLY_LIST,
		IGNORE_LIST
	}
	
	private final EnumSetting<PlayerTargetMode> playerTargetMode =
		new EnumSetting<>("Target players mode",
			"ALL = target all players (default)\n"
				+ "ONLY_LIST = target only players in the list (no mobs)\n"
				+ "IGNORE_LIST = target everyone except listed players",
			PlayerTargetMode.values(), PlayerTargetMode.ALL);
	
	private final TextFieldSetting playerListCsv = new TextFieldSetting(
		"Player list (comma-separated)",
		"Usernames separated by commas, case-insensitive.\nUsed by ONLY_LIST and IGNORE_LIST modes.",
		"");
	
	// ===== NEW: legit & speed control =====
	private final CheckboxSetting legitMode = new CheckboxSetting(
		"Legit mode (hotbar only)",
		"Visibly switches to items in hotbar and only interacts with blocks in a cone in front of you.",
		false);
	
	private final SliderSetting frontConeDeg = new SliderSetting(
		"Front cone (deg)",
		"Only place/charge/detonate anchors inside this cone in front of you when Legit mode is ON.",
		45, 10, 70, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting legitPreDelayMs =
		new SliderSetting("Legit pre-switch delay (ms)",
			"Wait after selecting a hotbar slot before using the item.", 120, 0,
			600, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting legitPostDwellMs =
		new SliderSetting("Legit post-use dwell (ms)",
			"Keep the selected slot for a bit after using (looks legit).", 150,
			0, 1000, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting actionsPerSecond = new SliderSetting(
		"Actions per second", "Max rate of place/charge/detonate actions.", 6,
		1, 20, 1, ValueDisplay.INTEGER);
	
	// ===== NEW: legit sequencer =====
	private enum LegitAction
	{
		NONE,
		PLACE,
		CHARGE,
		DETONATE
	}
	
	private LegitAction pending = LegitAction.NONE;
	private BlockPos pendingPos = null;
	private Direction pendingSide = null; // for PLACE/DETONATE click side
	private int pendingSelectSlot = -1;
	private int restoreSlot = -1;
	private long useAtNs = 0L;
	private long restoreAtNs = 0L;
	
	// rate limit (works for both legit & non-legit)
	private long lastActionNs = 0L;
	
	public AnchorAuraHack()
	{
		super("AnchorAura");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(autoPlace);
		addSetting(faceBlocks);
		addSetting(checkLOS);
		addSetting(swingHand);
		addSetting(takeItemsFrom);
		
		// new player targeting settings
		addSetting(playerTargetMode);
		addSetting(playerListCsv);
		
		// legit & speed
		addSetting(legitMode);
		addSetting(frontConeDeg);
		addSetting(legitPreDelayMs);
		addSetting(legitPostDwellMs);
		addSetting(actionsPerSecond);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		lastActionNs = 0L;
		clearLegit();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		clearLegit();
	}
	
	private void clearLegit()
	{
		pending = LegitAction.NONE;
		pendingPos = null;
		pendingSide = null;
		pendingSelectSlot = -1;
		restoreSlot = -1;
		useAtNs = 0L;
		restoreAtNs = 0L;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.world.getDimension().respawnAnchorWorks())
		{
			ChatUtils.error("Respawn anchors don't explode in this dimension.");
			setEnabled(false);
			return;
		}
		if(MC.player == null)
			return;
		
		final long now = System.nanoTime();
		driveLegit(now);
		
		// obey global action rate limit
		final long minGapNs =
			(long)(1_000_000_000d / actionsPerSecond.getValue());
		boolean canAct = now - lastActionNs >= minGapNs;
		if(legitMode.isChecked() && legitActive())
			canAct = false; // sequencer owns the action timing
			
		// collection
		ArrayList<BlockPos> anchors = getNearbyAnchors();
		Map<Boolean, ArrayList<BlockPos>> byCharge = anchors.stream()
			.collect(Collectors.partitioningBy(this::isChargedAnchor,
				Collectors.toCollection(ArrayList::new)));
		ArrayList<BlockPos> charged = byCharge.get(true);
		ArrayList<BlockPos> uncharged = byCharge.get(false);
		
		int maxInvSlot = takeItemsFrom.getSelected().maxInvSlot;
		
		// DETONATE first (front-cone gated in helpers)
		if(canAct && !charged.isEmpty())
		{
			if(legitOrInstantDetonate(charged))
				return;
		}
		
		// CHARGE second
		if(canAct && !uncharged.isEmpty()
			&& InventoryUtils.indexOf(Items.GLOWSTONE, maxInvSlot) >= 0)
		{
			if(legitOrInstantCharge(uncharged))
				return;
		}
		
		// PLACE → CHARGE → DETONATE on new blocks
		if(canAct && autoPlace.isChecked()
			&& InventoryUtils.indexOf(Items.RESPAWN_ANCHOR, maxInvSlot) != -1)
		{
			ArrayList<Entity> targets = getNearbyTargets();
			ArrayList<BlockPos> placed = legitOrInstantPlaceNear(targets);
			if(!placed.isEmpty()
				&& InventoryUtils.indexOf(Items.GLOWSTONE, maxInvSlot) >= 0)
			{
				// non-legit can chain immediately; legit timing is sequenced
				if(!legitMode.isChecked())
				{
					charge(placed);
					detonate(placed);
				}
			}
		}
	}
	
	// ===================== Player list helpers =====================
	private Set<String> parsePlayerListLower()
	{
		return Stream.of(playerListCsv.getValue().split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT))
			.collect(Collectors.toSet());
	}
	
	private boolean isPlayerAllowed(Entity e, Set<String> list)
	{
		if(!(e instanceof PlayerEntity p))
		{
			// ONLY_LIST: target players from the list only (exclude mobs)
			// ALL / IGNORE_LIST: allow non-players to pass (other filters
			// apply)
			return playerTargetMode.getSelected() != PlayerTargetMode.ONLY_LIST;
		}
		
		String name;
		try
		{
			name = p.getName().getString();
		}catch(Throwable t)
		{
			name = null;
		}
		String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
		
		switch(playerTargetMode.getSelected())
		{
			case ONLY_LIST:
			return list.contains(lower);
			case IGNORE_LIST:
			return !list.contains(lower);
			case ALL:
			default:
			return true;
		}
	}
	
	// ===================== Helpers: legit scheduler =====================
	private boolean legitActive()
	{
		return pending != LegitAction.NONE;
	}
	
	private void startLegit(BlockPos pos, Direction side, LegitAction act,
		int slotToUse)
	{
		if(MC.player == null || legitActive())
			return;
		
		pending = act;
		pendingPos = pos;
		pendingSide = side;
		restoreSlot = MC.player.getInventory().getSelectedSlot();
		pendingSelectSlot = slotToUse < 0 ? restoreSlot : slotToUse;
		
		// visible switch
		selectHotbar(pendingSelectSlot);
		
		int pre = Math.max(legitPreDelayMs.getValueI(), 60);
		useAtNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pre);
		restoreAtNs = 0L;
	}
	
	private void driveLegit(long now)
	{
		if(!legitActive() || MC.player == null)
			return;
		
		// Use phase
		if(restoreAtNs == 0L && now >= useAtNs)
		{
			try
			{
				switch(pending)
				{
					case PLACE:
					{
						if(pendingPos != null && pendingSide != null)
						{
							Vec3d hit = Vec3d.ofCenter(pendingPos).add(Vec3d
								.of(pendingSide.getVector()).multiply(0.5));
							IMC.getInteractionManager().rightClickBlock(
								pendingPos, pendingSide.getOpposite(), hit);
							swingHand.swing(Hand.MAIN_HAND);
							lastActionNs = now;
						}
						break;
					}
					case CHARGE:
					{
						IMC.getInteractionManager().rightClickBlock(pendingPos,
							Direction.UP, Vec3d.ofCenter(pendingPos));
						swingHand.swing(Hand.MAIN_HAND);
						lastActionNs = now;
						break;
					}
					case DETONATE:
					{
						IMC.getInteractionManager().rightClickBlock(pendingPos,
							Direction.UP, Vec3d.ofCenter(pendingPos));
						swingHand.swing(Hand.MAIN_HAND);
						lastActionNs = now;
						break;
					}
					default:
					break;
				}
			}catch(Throwable ignored)
			{}
			
			int post = Math.max(legitPostDwellMs.getValueI(), 100);
			restoreAtNs = now + TimeUnit.MILLISECONDS.toNanos(post);
		}
		
		// Restore phase
		if(restoreAtNs != 0L && now >= restoreAtNs)
		{
			selectHotbar(restoreSlot);
			clearLegit();
		}
	}
	
	// ===================== Front-cone check =====================
	private boolean inFrontCone(Vec3d worldPoint)
	{
		if(!legitMode.isChecked())
			return true; // no restriction in non-legit
		Vec3d eye = RotationUtils.getEyesPos();
		Vec3d look = MC.player.getRotationVec(1.0F).normalize();
		Vec3d to = worldPoint.subtract(eye).normalize();
		double dot = look.dotProduct(to);
		double minDot = Math.cos(Math.toRadians(frontConeDeg.getValue()));
		return dot >= minDot;
	}
	
	// ===================== Selection helpers =====================
	private int findHotbarSlot(java.util.function.Predicate<ItemStack> pred)
	{
		if(MC.player == null)
			return -1;
		for(int i = 0; i < 9; i++)
		{
			ItemStack s = MC.player.getInventory().getStack(i);
			if(s != null && !s.isEmpty() && pred.test(s))
				return i;
		}
		return -1;
	}
	
	private boolean selectHotbar(int slot)
	{
		if(MC.player == null || slot < 0 || slot > 8)
			return false;
		MC.player.getInventory().setSelectedSlot(slot);
		return true;
	}
	
	// ===================== Action wrappers (legit or instant)
	// =====================
	private boolean legitOrInstantDetonate(ArrayList<BlockPos> charged)
	{
		for(BlockPos pos : charged)
		{
			if(!inFrontCone(Vec3d.ofCenter(pos)))
				continue;
			if(checkLOS.isChecked()
				&& !BlockUtils.hasLineOfSight(RotationUtils.getEyesPos(),
					Vec3d.ofCenter(pos)))
				continue;
			
			if(legitMode.isChecked())
			{
				int anchorSlot = findHotbarSlot(s -> s != null && !s.isEmpty()
					&& s.isOf(Items.RESPAWN_ANCHOR));
				if(anchorSlot == -1)
					return false; // legit requires anchor on hotbar
				startLegit(pos, Direction.UP, LegitAction.DETONATE, anchorSlot);
				return true; // scheduled
			}else
			{
				InventoryUtils.selectItem(Items.RESPAWN_ANCHOR,
					takeItemsFrom.getSelected().maxInvSlot);
				if(!MC.player.isHolding(Items.RESPAWN_ANCHOR))
					return false;
				detonate(singleton(pos));
				return true;
			}
		}
		return false;
	}
	
	private boolean legitOrInstantCharge(ArrayList<BlockPos> uncharged)
	{
		for(BlockPos pos : uncharged)
		{
			if(!inFrontCone(Vec3d.ofCenter(pos)))
				continue;
			if(checkLOS.isChecked()
				&& !BlockUtils.hasLineOfSight(RotationUtils.getEyesPos(),
					Vec3d.ofCenter(pos)))
				continue;
			
			if(legitMode.isChecked())
			{
				int glowSlot = findHotbarSlot(s -> s.isOf(Items.GLOWSTONE));
				if(glowSlot == -1)
					return false;
				startLegit(pos, Direction.UP, LegitAction.CHARGE, glowSlot);
				return true;
			}else
			{
				charge(singleton(pos));
				return true;
			}
		}
		return false;
	}
	
	private ArrayList<BlockPos> legitOrInstantPlaceNear(
		ArrayList<Entity> targets)
	{
		ArrayList<BlockPos> newAnchors = new ArrayList<>();
		for(Entity target : targets)
		{
			for(BlockPos pos : getFreeBlocksNear(target))
			{
				for(Direction side : Direction.values())
				{
					BlockPos neighbor = pos.offset(side);
					if(!isClickableNeighbor(neighbor))
						continue;
					
					Vec3d hit = Vec3d.ofCenter(pos)
						.add(Vec3d.of(side.getVector()).multiply(0.5));
					if(!inFrontCone(hit))
						continue;
					
					if(checkLOS.isChecked() && !BlockUtils
						.hasLineOfSight(RotationUtils.getEyesPos(), hit))
						continue;
					
					if(legitMode.isChecked())
					{
						int anchorSlot =
							findHotbarSlot(s -> s.isOf(Items.RESPAWN_ANCHOR));
						if(anchorSlot == -1)
							return newAnchors; // must be in hotbar
						startLegit(neighbor, side, LegitAction.PLACE,
							anchorSlot);
						return newAnchors; // schedule one placement
					}else
					{
						if(placeAnchorDirect(pos, neighbor, side))
						{
							newAnchors.add(pos);
							swingHand.swing(Hand.MAIN_HAND);
							lastActionNs = System.nanoTime();
							return newAnchors; // 1 per tick due to rate limit
						}
					}
				}
			}
		}
		return newAnchors;
	}
	
	// ===================== Original actions (non-legit path)
	// =====================
	private boolean placeAnchorDirect(BlockPos pos, BlockPos neighbor,
		Direction side)
	{
		Vec3d eyesPos = RotationUtils.getEyesPos();
		double rangeSq = range.getValueSq();
		Vec3d posVec = Vec3d.ofCenter(pos);
		Vec3d dirVec = Vec3d.of(side.getVector());
		Vec3d hitVec = posVec.add(dirVec.multiply(0.5));
		
		if(eyesPos.squaredDistanceTo(hitVec) > rangeSq)
			return false;
		if(checkLOS.isChecked() && !BlockUtils.hasLineOfSight(eyesPos, hitVec))
			return false;
		
		InventoryUtils.selectItem(Items.RESPAWN_ANCHOR,
			takeItemsFrom.getSelected().maxInvSlot);
		if(!MC.player.isHolding(Items.RESPAWN_ANCHOR))
			return false;
		
		faceBlocks.getSelected().face(hitVec);
		IMC.getInteractionManager().rightClickBlock(neighbor,
			side.getOpposite(), hitVec);
		return true;
	}
	
	private void detonate(ArrayList<BlockPos> chargedAnchors)
	{
		if(isSneaking())
			return;
		
		InventoryUtils.selectItem(Items.RESPAWN_ANCHOR,
			takeItemsFrom.getSelected().maxInvSlot);
		if(!MC.player.isHolding(Items.RESPAWN_ANCHOR))
			return;
		
		boolean swing = false;
		for(BlockPos pos : chargedAnchors)
		{
			if(!inFrontCone(Vec3d.ofCenter(pos)))
				continue;
			if(rightClickBlock(pos))
				swing = true;
		}
		if(swing)
			swingHand.swing(Hand.MAIN_HAND);
		lastActionNs = System.nanoTime();
	}
	
	private void charge(ArrayList<BlockPos> unchargedAnchors)
	{
		if(isSneaking())
			return;
		InventoryUtils.selectItem(Items.GLOWSTONE,
			takeItemsFrom.getSelected().maxInvSlot);
		if(!MC.player.isHolding(Items.GLOWSTONE))
			return;
		
		boolean swing = false;
		for(BlockPos pos : unchargedAnchors)
		{
			if(!inFrontCone(Vec3d.ofCenter(pos)))
				continue;
			if(rightClickBlock(pos))
				swing = true;
		}
		if(swing)
			swingHand.swing(Hand.MAIN_HAND);
		lastActionNs = System.nanoTime();
	}
	
	private boolean rightClickBlock(BlockPos pos)
	{
		Vec3d eyesPos = RotationUtils.getEyesPos();
		Vec3d posVec = Vec3d.ofCenter(pos);
		double distanceSqPosVec = eyesPos.squaredDistanceTo(posVec);
		
		for(Direction side : Direction.values())
		{
			Vec3d hitVec = posVec.add(Vec3d.of(side.getVector()).multiply(0.5));
			double distanceSqHitVec = eyesPos.squaredDistanceTo(hitVec);
			
			if(distanceSqHitVec > 36)
				continue; // <=6 blocks
			if(distanceSqHitVec >= distanceSqPosVec)
				continue;
			if(checkLOS.isChecked()
				&& !BlockUtils.hasLineOfSight(eyesPos, hitVec))
				continue;
			if(!inFrontCone(hitVec))
				continue;
			
			faceBlocks.getSelected().face(hitVec);
			IMC.getInteractionManager().rightClickBlock(pos, side, hitVec);
			return true;
		}
		return false;
	}
	
	// ===================== Original discovery helpers =====================
	private ArrayList<BlockPos> getNearbyAnchors()
	{
		Vec3d eyesVec = RotationUtils.getEyesPos().subtract(0.5, 0.5, 0.5);
		BlockPos center = BlockPos.ofFloored(RotationUtils.getEyesPos());
		int rangeI = range.getValueCeil();
		double rangeSq = MathHelper.square(range.getValue() + 0.5);
		
		Comparator<BlockPos> furthestFromPlayer =
			Comparator.<BlockPos> comparingDouble(
				pos -> eyesVec.squaredDistanceTo(Vec3d.of(pos))).reversed();
		
		return BlockUtils.getAllInBoxStream(center, rangeI)
			.filter(pos -> eyesVec.squaredDistanceTo(Vec3d.of(pos)) <= rangeSq)
			.filter(pos -> BlockUtils.getBlock(pos) == Blocks.RESPAWN_ANCHOR)
			.sorted(furthestFromPlayer)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<Entity> getNearbyTargets()
	{
		double rangeSq = range.getValueSq();
		Comparator<Entity> furthestFromPlayer = Comparator
			.<Entity> comparingDouble(e -> MC.player.squaredDistanceTo(e))
			.reversed();
		
		final Set<String> list = parsePlayerListLower();
		
		Stream<Entity> stream = StreamSupport
			.stream(MC.world.getEntities().spliterator(), false)
			.filter(e -> !e.isRemoved())
			.filter(e -> e instanceof LivingEntity
				&& ((LivingEntity)e).getHealth() > 0)
			.filter(e -> e != MC.player)
			.filter(e -> !(e instanceof FakePlayerEntity))
			.filter(e -> !WURST.getFriends().contains(e.getName().getString()))
			.filter(e -> MC.player.squaredDistanceTo(e) <= rangeSq)
			// NEW: player allow/deny logic
			.filter(e -> isPlayerAllowed(e, list));
		
		stream = entityFilters.applyTo(stream);
		return stream.sorted(furthestFromPlayer)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<BlockPos> getFreeBlocksNear(Entity target)
	{
		Vec3d eyesVec = RotationUtils.getEyesPos().subtract(0.5, 0.5, 0.5);
		double rangeSq = MathHelper.square(range.getValue() + 0.5);
		BlockPos center = target.getBlockPos();
		int rangeI = 2;
		
		Box targetBB = target.getBoundingBox();
		Vec3d targetEyesVec =
			target.getPos().add(0, target.getEyeHeight(target.getPose()), 0);
		
		Comparator<BlockPos> closestToTarget =
			Comparator.<BlockPos> comparingDouble(
				pos -> targetEyesVec.squaredDistanceTo(Vec3d.ofCenter(pos)));
		
		return BlockUtils.getAllInBoxStream(center, rangeI)
			.filter(pos -> eyesVec.squaredDistanceTo(Vec3d.of(pos)) <= rangeSq)
			.filter(this::isReplaceable).filter(this::hasClickableNeighbor)
			.filter(pos -> !targetBB.intersects(new Box(pos)))
			.sorted(closestToTarget)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private boolean isReplaceable(BlockPos pos)
	{
		return BlockUtils.getState(pos).isReplaceable();
	}
	
	private boolean hasClickableNeighbor(BlockPos pos)
	{
		return isClickableNeighbor(pos.up()) || isClickableNeighbor(pos.down())
			|| isClickableNeighbor(pos.north())
			|| isClickableNeighbor(pos.east())
			|| isClickableNeighbor(pos.south())
			|| isClickableNeighbor(pos.west());
	}
	
	private boolean isClickableNeighbor(BlockPos pos)
	{
		return BlockUtils.canBeClicked(pos)
			&& !BlockUtils.getState(pos).isReplaceable();
	}
	
	private boolean isChargedAnchor(BlockPos pos)
	{
		return BlockUtils.getState(pos).getOrEmpty(RespawnAnchorBlock.CHARGES)
			.orElse(0) > 0;
	}
	
	private boolean isSneaking()
	{
		return MC.player.isSneaking() || WURST.getHax().sneakHack.isEnabled();
	}
	
	private enum TakeItemsFrom
	{
		HOTBAR("Hotbar", 9),
		INVENTORY("Inventory", 36);
		
		private final String name;
		private final int maxInvSlot;
		
		private TakeItemsFrom(String name, int maxInvSlot)
		{
			this.name = name;
			this.maxInvSlot = maxInvSlot;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	// tiny convenience
	private static ArrayList<BlockPos> singleton(BlockPos p)
	{
		ArrayList<BlockPos> l = new ArrayList<>(1);
		l.add(p);
		return l;
	}
}
