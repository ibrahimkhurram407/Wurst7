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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
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
import net.wurstclient.settings.filterlists.CrystalAuraFilterList;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"crystal aura"})
public final class CrystalAuraHack extends Hack implements UpdateListener
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final IMinecraftClient IMC = WurstClient.IMC;
	
	private final SliderSetting range =
		new SliderSetting("Range", "How far to place/break crystals.", 6, 1, 6,
			0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoPlace = new CheckboxSetting(
		"Auto-place crystals",
		"When ON, will place crystals near targets. When OFF, will only break existing crystals.",
		true);
	
	private final FacingSetting faceBlocks = FacingSetting.withPacketSpam(
		"Face crystals",
		"Face blocks/entities before placing/breaking (helps some anticheats).",
		Facing.OFF);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"Don’t reach through blocks when placing/left-clicking.", false);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private final EnumSetting<TakeItemsFrom> takeItemsFrom =
		new EnumSetting<>("Take items from", "Where to look for end crystals.",
			TakeItemsFrom.values(), TakeItemsFrom.INVENTORY);
	
	private final EntityFilterList entityFilters =
		CrystalAuraFilterList.create();
	
	/* ===== NEW: legit mode & speed control ===== */
	private final CheckboxSetting legitMode = new CheckboxSetting(
		"Legit mode (hotbar only)",
		"Visibly switch to End Crystal on hotbar and only act inside a cone in front of you.",
		false);
	
	private final SliderSetting frontConeDeg =
		new SliderSetting("Front cone (deg)",
			"Only place/break inside this cone in front of you (Legit mode).",
			45, 10, 70, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting legitPreDelayMs =
		new SliderSetting("Legit pre-switch delay (ms)",
			"Wait after selecting the crystal slot before using/breaking.", 120,
			0, 600, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting legitPostDwellMs = new SliderSetting(
		"Legit post-use dwell (ms)",
		"Keep the crystal slot selected after the action (stays on crystal).",
		120, 0, 1000, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting actionsPerSecond = new SliderSetting(
		"Actions per second", "Max rate of place/break actions.", 6, 1, 20, 1,
		ValueDisplay.INTEGER);
	
	/* ===== NEW: legit sequencer ===== */
	private enum LegitAction
	{
		NONE,
		PLACE,
		BREAK
	}
	
	private LegitAction pending = LegitAction.NONE;
	private BlockPos pendingPlacePos = null; // for PLACE: target block position
												// to click
	private Direction pendingPlaceSide = null; // for PLACE: neighbor side we
												// click on
	private EndCrystalEntity pendingBreak = null; // for BREAK
	private int crystalSlot = -1;
	private long useAtNs = 0L;
	private long restoreAtNs = 0L;
	
	// global rate limit (applies to both legit & non-legit)
	private long lastActionNs = 0L;
	
	public CrystalAuraHack()
	{
		super("CrystalAura");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(autoPlace);
		addSetting(faceBlocks);
		addSetting(checkLOS);
		addSetting(swingHand);
		addSetting(takeItemsFrom);
		
		// new settings
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
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		clearLegit();
		lastActionNs = 0L;
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
		pendingPlacePos = null;
		pendingPlaceSide = null;
		pendingBreak = null;
		crystalSlot = -1;
		useAtNs = 0L;
		restoreAtNs = 0L;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
			return;
		
		final long now = System.nanoTime();
		driveLegit(now);
		
		// respect actions-per-second (skip when sequencer is mid-action)
		long minGap = (long)(1_000_000_000d / actionsPerSecond.getValue());
		boolean canAct =
			pending == LegitAction.NONE && (now - lastActionNs >= minGap);
		
		// 1) Try breaking crystals first
		ArrayList<Entity> crystals = getNearbyCrystals();
		if(canAct && !crystals.isEmpty())
		{
			if(legitOrInstantBreak(crystals))
				return;
		}
		
		// 2) Place crystals if allowed & available
		if(!autoPlace.isChecked())
			return;
		
		if(InventoryUtils.indexOf(Items.END_CRYSTAL,
			takeItemsFrom.getSelected().maxInvSlot) == -1)
			return;
		
		if(canAct)
		{
			ArrayList<Entity> targets = getNearbyTargets();
			legitOrInstantPlaceNear(targets);
		}
	}
	
	/* ==================== Legit driver ==================== */
	
	private boolean legitActive()
	{
		return pending != LegitAction.NONE;
	}
	
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
	
	private boolean inFrontCone(Vec3d worldPoint)
	{
		if(!legitMode.isChecked())
			return true;
		Vec3d eye = RotationUtils.getEyesPos();
		Vec3d look = MC.player.getRotationVec(1.0F).normalize();
		Vec3d to = worldPoint.subtract(eye).normalize();
		double dot = look.dotProduct(to);
		double minDot = Math.cos(Math.toRadians(frontConeDeg.getValue()));
		return dot >= minDot;
	}
	
	private void startLegitPlace(BlockPos pos, Direction side,
		int crystalSlotIdx)
	{
		if(legitActive() || MC.player == null)
			return;
		pending = LegitAction.PLACE;
		pendingPlacePos = pos;
		pendingPlaceSide = side;
		crystalSlot = crystalSlotIdx; // we will stay on this slot
		selectHotbar(crystalSlot);
		
		int pre = Math.max(legitPreDelayMs.getValueI(), 60);
		useAtNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pre);
		restoreAtNs = 0L;
	}
	
	private void startLegitBreak(EndCrystalEntity crystal, int crystalSlotIdx)
	{
		if(legitActive() || MC.player == null)
			return;
		pending = LegitAction.BREAK;
		pendingBreak = crystal;
		crystalSlot = crystalSlotIdx; // stay on crystal slot even for breaking
		selectHotbar(crystalSlot);
		
		int pre = Math.max(legitPreDelayMs.getValueI(), 60);
		useAtNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pre);
		restoreAtNs = 0L;
	}
	
	private void driveLegit(long now)
	{
		if(!legitActive() || MC.player == null)
			return;
		
		if(restoreAtNs == 0L && now >= useAtNs)
		{
			try
			{
				switch(pending)
				{
					case PLACE:
					{
						if(pendingPlacePos != null && pendingPlaceSide != null)
						{
							Vec3d hit = Vec3d.ofCenter(pendingPlacePos)
								.add(Vec3d.of(pendingPlaceSide.getVector())
									.multiply(0.5));
							if(faceBlocks.getSelected() != Facing.OFF)
								faceBlocks.getSelected().face(hit);
							
							IMC.getInteractionManager().rightClickBlock(
								pendingPlacePos, pendingPlaceSide.getOpposite(),
								hit);
							swingHand.swing(Hand.MAIN_HAND);
							lastActionNs = now;
						}
						break;
					}
					case BREAK:
					{
						if(pendingBreak != null && !pendingBreak.isRemoved())
						{
							Vec3d center =
								pendingBreak.getBoundingBox().getCenter();
							if(faceBlocks.getSelected() != Facing.OFF)
								faceBlocks.getSelected().face(center);
							
							MC.interactionManager.attackEntity(MC.player,
								pendingBreak);
							swingHand.swing(Hand.MAIN_HAND);
							lastActionNs = now;
						}
						break;
					}
					default:
					break;
				}
			}catch(Throwable ignored)
			{}
			
			int post = Math.max(legitPostDwellMs.getValueI(), 60);
			restoreAtNs = now + TimeUnit.MILLISECONDS.toNanos(post);
		}
		
		// We “stay” on crystal slot -> just finish the sequence, no reselect.
		if(restoreAtNs != 0L && now >= restoreAtNs)
		{
			// ensure we’re still on crystal slot (harmless reselect)
			if(crystalSlot >= 0)
				selectHotbar(crystalSlot);
			clearLegit();
		}
	}
	
	/* ==================== Action wrappers ==================== */
	
	private boolean legitOrInstantBreak(ArrayList<Entity> crystals)
	{
		for(Entity e : crystals)
		{
			if(!(e instanceof EndCrystalEntity ec))
				continue;
			Vec3d center = e.getBoundingBox().getCenter();
			if(!inFrontCone(center))
				continue;
			
			if(legitMode.isChecked())
			{
				int slot = findHotbarSlot(s -> s.isOf(Items.END_CRYSTAL));
				if(slot == -1)
					return false; // legit requires crystal on hotbar
				// Optional LOS check to match behavior:
				if(checkLOS.isChecked() && !hasEntityLineOfSight(center))
					continue;
				
				startLegitBreak(ec, slot);
				return true; // scheduled
			}else
			{
				// Non-legit: no need to hold crystal, but we *can* if you
				// prefer
				faceBlocks.getSelected().face(center);
				MC.interactionManager.attackEntity(MC.player, e);
				swingHand.swing(Hand.MAIN_HAND);
				lastActionNs = System.nanoTime();
				return true;
			}
		}
		return false;
	}
	
	private void legitOrInstantPlaceNear(ArrayList<Entity> targets)
	{
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
						int slot =
							findHotbarSlot(s -> s.isOf(Items.END_CRYSTAL));
						if(slot == -1)
							return; // must be on hotbar
						startLegitPlace(neighbor, side, slot);
						return; // schedule one per tick
					}else
					{
						if(placeCrystalDirect(pos, neighbor, side))
						{
							swingHand.swing(Hand.MAIN_HAND);
							lastActionNs = System.nanoTime();
							return;
						}
					}
				}
			}
		}
	}
	
	/* ==================== Non-legit primitives ==================== */
	
	private boolean placeCrystalDirect(BlockPos pos, BlockPos neighbor,
		Direction side)
	{
		Vec3d eyesPos = RotationUtils.getEyesPos();
		double rangeSq = Math.pow(range.getValue(), 2);
		Vec3d posVec = Vec3d.ofCenter(pos);
		Vec3d dirVec = Vec3d.of(side.getVector());
		Vec3d hitVec = posVec.add(dirVec.multiply(0.5));
		
		if(eyesPos.squaredDistanceTo(hitVec) > rangeSq)
			return false;
		if(checkLOS.isChecked() && !BlockUtils.hasLineOfSight(eyesPos, hitVec))
			return false;
		
		InventoryUtils.selectItem(Items.END_CRYSTAL,
			takeItemsFrom.getSelected().maxInvSlot);
		if(!MC.player.isHolding(Items.END_CRYSTAL))
			return false;
		
		faceBlocks.getSelected().face(hitVec);
		IMC.getInteractionManager().rightClickBlock(neighbor,
			side.getOpposite(), hitVec);
		return true;
	}
	
	/*
	 * ==================== Original helpers (filtered to front cone in callers)
	 * ====================
	 */
	
	private ArrayList<Entity> getNearbyCrystals()
	{
		ClientPlayerEntity player = MC.player;
		double rangeSq = Math.pow(range.getValue(), 2);
		
		Comparator<Entity> furthestFromPlayer = Comparator
			.<Entity> comparingDouble(e -> MC.player.squaredDistanceTo(e))
			.reversed();
		
		return StreamSupport.stream(MC.world.getEntities().spliterator(), true)
			.filter(EndCrystalEntity.class::isInstance)
			.filter(e -> !e.isRemoved())
			.filter(e -> player.squaredDistanceTo(e) <= rangeSq)
			.sorted(furthestFromPlayer)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<Entity> getNearbyTargets()
	{
		double rangeSq = Math.pow(range.getValue(), 2);
		Comparator<Entity> furthestFromPlayer = Comparator
			.<Entity> comparingDouble(e -> MC.player.squaredDistanceTo(e))
			.reversed();
		
		Stream<Entity> stream =
			StreamSupport.stream(MC.world.getEntities().spliterator(), false)
				.filter(e -> !e.isRemoved())
				.filter(e -> e instanceof LivingEntity
					&& ((LivingEntity)e).getHealth() > 0)
				.filter(e -> e != MC.player)
				.filter(e -> !(e instanceof FakePlayerEntity))
				.filter(
					e -> !WURST.getFriends().contains(e.getName().getString()))
				.filter(e -> MC.player.squaredDistanceTo(e) <= rangeSq);
		
		stream = entityFilters.applyTo(stream);
		return stream.sorted(furthestFromPlayer)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<BlockPos> getFreeBlocksNear(Entity target)
	{
		Vec3d eyesVec = RotationUtils.getEyesPos().subtract(0.5, 0.5, 0.5);
		double rangeD = range.getValue();
		double rangeSq = Math.pow(rangeD + 0.5, 2);
		int rangeI = 2;
		
		BlockPos center = target.getBlockPos();
		BlockPos min = center.add(-rangeI, -rangeI, -rangeI);
		BlockPos max = center.add(rangeI, rangeI, rangeI);
		Box targetBB = target.getBoundingBox();
		
		Vec3d targetEyesVec =
			target.getPos().add(0, target.getEyeHeight(target.getPose()), 0);
		
		Comparator<BlockPos> closestToTarget =
			Comparator.<BlockPos> comparingDouble(
				pos -> targetEyesVec.squaredDistanceTo(Vec3d.ofCenter(pos)));
		
		return BlockUtils.getAllInBoxStream(min, max)
			.filter(pos -> eyesVec.squaredDistanceTo(Vec3d.of(pos)) <= rangeSq)
			.filter(this::isReplaceable).filter(this::hasCrystalBase)
			.filter(pos -> !targetBB.intersects(new Box(pos)))
			.sorted(closestToTarget)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private boolean isReplaceable(BlockPos pos)
	{
		return BlockUtils.getState(pos).isReplaceable();
	}
	
	private boolean hasCrystalBase(BlockPos pos)
	{
		Block block = BlockUtils.getBlock(pos.down());
		return block == Blocks.BEDROCK || block == Blocks.OBSIDIAN;
	}
	
	private boolean isClickableNeighbor(BlockPos pos)
	{
		return BlockUtils.canBeClicked(pos)
			&& !BlockUtils.getState(pos).isReplaceable();
	}
	
	private boolean hasEntityLineOfSight(Vec3d to)
	{
		if(MC.world == null)
			return true;
		if(MC.crosshairTarget != null
			&& MC.crosshairTarget.getType() == HitResult.Type.BLOCK)
			return false;
		// fallback to block LOS:
		return BlockUtils.hasLineOfSight(RotationUtils.getEyesPos(), to);
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
}
