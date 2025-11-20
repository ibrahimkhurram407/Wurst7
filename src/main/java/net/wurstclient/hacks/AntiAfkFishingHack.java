/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"anti afk fishing", "afk fish", "auto fishing position"})
@DontSaveState
public final class AntiAfkFishingHack extends Hack implements UpdateListener
{
	
	private final SliderSetting waitTime =
		new SliderSetting("Wait time", "Time to wait before changing position.",
			30, 5, 300, 1, ValueDisplay.DECIMAL.withSuffix("s"));
	private final SliderSetting waitTimeRand = new SliderSetting(
		"Wait time randomization", "Random variation in wait time.", 5, 0, 60,
		0.5, ValueDisplay.DECIMAL.withPrefix("\u00b1").withSuffix("s"));
	private final SliderSetting playerAvoidDistance =
		new SliderSetting("Player avoid distance",
			"How far to stay away from players (except ignored ones).", 10, 3,
			50, 1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting searchRadius = new SliderSetting(
		"Water search radius", "How far to search for water sources.", 32, 8,
		128, 1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting pitchMin = new SliderSetting("Min pitch",
		"Minimum pitch angle when looking at water.", 30, 0, 90, 1,
		ValueDisplay.DEGREES);
	private final SliderSetting pitchMax = new SliderSetting("Max pitch",
		"Maximum pitch angle when looking at water.", 60, 0, 90, 1,
		ValueDisplay.DEGREES);
	private final TextFieldSetting ignoreList = new TextFieldSetting(
		"Ignore players",
		"Comma-separated list of player names to ignore.\nExample: Friend1,Friend2,Ally3",
		"");
	private final TextFieldSetting adminTpPrompt = new TextFieldSetting(
		"Admin TP prompt",
		"Prompt to send to ChatGPTResponder when admin teleports you.",
		"An admin has teleported me. I should act natural and not suspicious.");
	
	private int timer;
	private Random random = Random.createLocal();
	private BlockPos currentFishingSpot;
	private BlockPos waterTarget;
	private BlockPos lastPlayerPos;
	private PathFinder pathFinder;
	private PathProcessor processor;
	private float targetPitch;
	private float targetYaw;
	private boolean movedToSpot;
	
	private boolean autoFishWasEnabled;
	private boolean walkingToSpot;
	
	public AntiAfkFishingHack()
	{
		super("AntiAfkFishing");
		setCategory(Category.OTHER);
		
		addSetting(waitTime);
		addSetting(waitTimeRand);
		addSetting(playerAvoidDistance);
		addSetting(searchRadius);
		addSetting(pitchMin);
		addSetting(pitchMax);
		addSetting(ignoreList);
		addSetting(adminTpPrompt);
	}
	
	@Override
	public String getRenderName()
	{
		if(timer > 0)
			return getName() + " [" + (timer / 20) + "s]";
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null)
			return;
		
		lastPlayerPos = MC.player.getBlockPos();
		currentFishingSpot = null;
		waterTarget = null;
		movedToSpot = false;
		pathFinder = null;
		processor = null;
		
		autoFishWasEnabled = false;
		walkingToSpot = false;
		
		findNewFishingSpot();
		setTimer();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		PathProcessor.releaseControls();
		pathFinder = null;
		processor = null;
		
		// if we turned AutoFish off for walking, restore it
		if(walkingToSpot && autoFishWasEnabled)
		{
			try
			{
				var hax = WurstClient.INSTANCE.getHax();
				if(hax.autoFishHack != null && !hax.autoFishHack.isEnabled())
					hax.autoFishHack.setEnabled(true);
			}catch(Throwable ignored)
			{}
		}
		
		walkingToSpot = false;
		autoFishWasEnabled = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.player.getHealth() <= 0)
		{
			setEnabled(false);
			return;
		}
		
		// Admin teleport check (>4 blocks)
		BlockPos currentPos = MC.player.getBlockPos();
		if(lastPlayerPos != null)
		{
			double distance =
				Math.sqrt(currentPos.getSquaredDistance(lastPlayerPos));
			if(distance > 4.0)
			{
				handleAdminTeleport();
				return;
			}
		}
		lastPlayerPos = currentPos;
		
		// Need new spot?
		if(currentFishingSpot == null)
		{
			findNewFishingSpot();
			if(currentFishingSpot == null)
			{
				if(timer <= 0)
					setTimer();
				timer--;
				return;
			}
		}
		
		// Avoid players nearby *only* when we are standing at a spot
		if(movedToSpot && shouldAvoidPlayers())
		{
			// Someone came too close -> pick a new spot and start walking there
			findNewFishingSpot();
			movedToSpot = false;
			return;
		}
		
		// Navigate to spot
		if(!movedToSpot)
		{
			if(!navigateToSpot())
				return;
		}
		
		// Face water properly
		if(movedToSpot)
		{
			faceWater();
		}
		
		// Timer logic
		if(movedToSpot && timer > 0)
			timer--;
		if(movedToSpot && timer <= 0)
		{
			findNewFishingSpot();
			movedToSpot = false;
			setTimer();
		}
	}
	
	private void handleAdminTeleport()
	{
		var hax = WurstClient.INSTANCE.getHax();
		
		if(hax.autoFishHack != null && hax.autoFishHack.isEnabled())
			hax.autoFishHack.setEnabled(false);
		
		try
		{
			ChatGptResponderHack chatGPT = hax.chatGptResponderHack;
			if(chatGPT != null && chatGPT.isEnabled())
			{
				String promptToSet = adminTpPrompt.getValue();
				if(promptToSet != null && !promptToSet.trim().isEmpty())
				{
					chatGPT.getPromptTemplateSetting().setValue(promptToSet);
				}
			}
		}catch(Exception e)
		{
			if(MC.player != null)
				System.err.println(
					"[AntiAfkFishing] Failed to update ChatGPTResponder: "
						+ e.getMessage());
		}
		
		setEnabled(false);
	}
	
	private void startWalkingToSpot()
	{
		walkingToSpot = true;
		autoFishWasEnabled = false;
		
		try
		{
			var hax = WurstClient.INSTANCE.getHax();
			if(hax.autoFishHack != null && hax.autoFishHack.isEnabled())
			{
				autoFishWasEnabled = true;
				hax.autoFishHack.setEnabled(false);
			}
		}catch(Throwable ignored)
		{}
	}
	
	private void onArrivedAtSpot()
	{
		movedToSpot = true;
		walkingToSpot = false;
		PathProcessor.releaseControls();
		
		if(autoFishWasEnabled)
		{
			try
			{
				var hax = WurstClient.INSTANCE.getHax();
				if(hax.autoFishHack != null && !hax.autoFishHack.isEnabled())
					hax.autoFishHack.setEnabled(true);
			}catch(Throwable ignored)
			{}
		}
	}
	
	private boolean shouldAvoidPlayers()
	{
		if(MC.world == null || MC.player == null)
			return false;
		
		Set<String> ignored = getIgnoredPlayers();
		double avoidDist = playerAvoidDistance.getValue();
		Vec3d playerPos = MC.player.getPos();
		
		for(PlayerEntity player : MC.world.getPlayers())
		{
			if(player == MC.player)
				continue;
			String name = player.getGameProfile().getName();
			if(ignored.contains(name.toLowerCase()))
				continue;
			
			double distance = player.getPos().distanceTo(playerPos);
			if(distance < avoidDist)
				return true;
		}
		return false;
	}
	
	private Set<String> getIgnoredPlayers()
	{
		Set<String> ignored = new HashSet<>();
		for(String name : ignoreList.getValue().split(","))
		{
			String trimmed = name.trim().toLowerCase();
			if(!trimmed.isEmpty())
				ignored.add(trimmed);
		}
		return ignored;
	}
	
	private void findNewFishingSpot()
	{
		if(MC.world == null || MC.player == null)
			return;
		
		BlockPos playerPos = MC.player.getBlockPos();
		int radius = (int)searchRadius.getValue();
		
		// Find nearest water body
		BlockPos nearestWater = findNearestWater(playerPos, radius);
		if(nearestWater == null)
			return;
		
		// Find multiple shore spots and pick one randomly for variety
		BlockPos standingSpot = findRandomShoreSpot(nearestWater, playerPos);
		if(standingSpot == null)
			return;
		
		currentFishingSpot = standingSpot;
		waterTarget = findClosestWaterToSpot(standingSpot);
		
		if(waterTarget == null)
			waterTarget = nearestWater;
		
		// Calculate yaw and pitch to look at water from shore
		Vec3d playerStandPos = Vec3d.ofCenter(currentFishingSpot);
		Vec3d waterPos = Vec3d.ofCenter(waterTarget);
		Vec3d toWater = waterPos.subtract(playerStandPos);
		
		targetYaw =
			(float)Math.toDegrees(Math.atan2(toWater.z, toWater.x)) - 90F;
		
		float minP = (float)pitchMin.getValue();
		float maxP = (float)pitchMax.getValue();
		float idealPitch = (float)Math.toDegrees(Math.atan2(toWater.y,
			Math.sqrt(toWater.x * toWater.x + toWater.z * toWater.z)));
		idealPitch = MathHelper.clamp(idealPitch, minP, maxP);
		targetPitch = idealPitch + (random.nextFloat() - 0.5f) * 5f;
		
		pathFinder = new FishingPathFinder(currentFishingSpot);
		processor = null;
		
		movedToSpot = false;
		startWalkingToSpot();
	}
	
	private BlockPos findNearestWater(BlockPos center, int radius)
	{
		BlockPos nearest = null;
		double minDist = Double.MAX_VALUE;
		
		for(int y = -radius; y <= radius; y++)
		{
			for(int x = -radius; x <= radius; x++)
			{
				for(int z = -radius; z <= radius; z++)
				{
					BlockPos pos = center.add(x, y, z);
					if(isWater(pos))
					{
						double dist = pos.getSquaredDistance(center);
						if(dist < minDist)
						{
							minDist = dist;
							nearest = pos;
						}
					}
				}
			}
		}
		return nearest;
	}
	
	private boolean isWater(BlockPos pos)
	{
		if(MC.world == null)
			return false;
		BlockState state = MC.world.getBlockState(pos);
		return state.isOf(Blocks.WATER) || state.getBlock() == Blocks.WATER;
	}
	
	private BlockPos findStandingSpot(BlockPos waterPos)
	{
		// Check all horizontal neighbors
		BlockPos[] candidates = {waterPos.north(), waterPos.south(),
			waterPos.east(), waterPos.west()};
		
		for(BlockPos candidate : candidates)
		{
			// Make sure this spot is NOT water and IS valid for standing
			if(!isWater(candidate) && isValidStandingSpot(candidate))
				return candidate;
		}
		
		// Try one block up or down from horizontal neighbors
		for(BlockPos candidate : candidates)
		{
			BlockPos up = candidate.up();
			BlockPos down = candidate.down();
			
			if(!isWater(up) && isValidStandingSpot(up))
				return up;
			if(!isWater(down) && isValidStandingSpot(down))
				return down;
		}
		
		// Try diagonal positions at same level
		BlockPos[] diagonals =
			{waterPos.north().east(), waterPos.north().west(),
				waterPos.south().east(), waterPos.south().west()};
		
		for(BlockPos candidate : diagonals)
		{
			if(!isWater(candidate) && isValidStandingSpot(candidate))
				return candidate;
		}
		
		return null;
	}
	
	private BlockPos findRandomShoreSpot(BlockPos waterStart,
		BlockPos playerPos)
	{
		if(MC.world == null)
			return null;
		
		java.util.ArrayList<BlockPos> validShoreSpots =
			new java.util.ArrayList<>();
		java.util.HashSet<BlockPos> checkedPositions =
			new java.util.HashSet<>();
		
		// Search in expanding circles from the water start position
		int searchSize = 10;
		
		// Shuffle the order we check positions for true randomness
		java.util.ArrayList<BlockPos> waterPositionsToCheck =
			new java.util.ArrayList<>();
		
		for(int x = -searchSize; x <= searchSize; x++)
		{
			for(int z = -searchSize; z <= searchSize; z++)
			{
				for(int y = -2; y <= 2; y++)
				{
					BlockPos waterPos = waterStart.add(x, y, z);
					waterPositionsToCheck.add(waterPos);
				}
			}
		}
		
		// Shuffle to check in random order
		java.util.Collections.shuffle(waterPositionsToCheck,
			new java.util.Random(random.nextLong()));
		
		// Check water positions randomly
		for(BlockPos waterPos : waterPositionsToCheck)
		{
			if(!isWater(waterPos))
				continue;
			
			// Check all adjacent blocks for valid shore spots in random order
			java.util.ArrayList<BlockPos> adjacentList =
				new java.util.ArrayList<>();
			adjacentList.add(waterPos.north());
			adjacentList.add(waterPos.south());
			adjacentList.add(waterPos.east());
			adjacentList.add(waterPos.west());
			adjacentList.add(waterPos.north().up());
			adjacentList.add(waterPos.south().up());
			adjacentList.add(waterPos.east().up());
			adjacentList.add(waterPos.west().up());
			adjacentList.add(waterPos.north().down());
			adjacentList.add(waterPos.south().down());
			adjacentList.add(waterPos.east().down());
			adjacentList.add(waterPos.west().down());
			
			for(BlockPos shore : adjacentList)
			{
				if(checkedPositions.contains(shore))
					continue;
				
				checkedPositions.add(shore);
				
				if(!isWater(shore) && isValidStandingSpot(shore))
				{
					validShoreSpots.add(shore);
					// Collect plenty of options for variety
					if(validShoreSpots.size() >= 30)
						break;
				}
			}
			
			if(validShoreSpots.size() >= 30)
				break;
		}
		
		// If we have spots, prioritize ones that aren't too close to current
		// position
		if(!validShoreSpots.isEmpty())
		{
			// Filter out spots too close to where we already are
			java.util.ArrayList<BlockPos> farSpots =
				new java.util.ArrayList<>();
			for(BlockPos spot : validShoreSpots)
			{
				double dist = Math.sqrt(spot.getSquaredDistance(playerPos));
				if(dist > 3.0) // At least 3 blocks away
					farSpots.add(spot);
			}
			
			// If we have far spots, use those; otherwise use any spot
			if(!farSpots.isEmpty())
				return farSpots.get(random.nextInt(farSpots.size()));
			else
				return validShoreSpots
					.get(random.nextInt(validShoreSpots.size()));
		}
		
		// Fallback to simple search
		return findStandingSpot(waterStart);
	}
	
	private BlockPos findClosestWaterToSpot(BlockPos shoreSpot)
	{
		if(MC.world == null)
			return null;
		
		BlockPos closestWater = null;
		double minDist = Double.MAX_VALUE;
		
		// Check immediate neighbors first
		BlockPos[] neighbors = {shoreSpot.north(), shoreSpot.south(),
			shoreSpot.east(), shoreSpot.west(), shoreSpot.north().down(),
			shoreSpot.south().down(), shoreSpot.east().down(),
			shoreSpot.west().down()};
		
		for(BlockPos pos : neighbors)
		{
			if(isWater(pos))
			{
				double dist = pos.getSquaredDistance(shoreSpot);
				if(dist < minDist)
				{
					minDist = dist;
					closestWater = pos;
				}
			}
		}
		
		// If no water found immediately adjacent, search a bit further
		if(closestWater == null)
		{
			for(int x = -2; x <= 2; x++)
			{
				for(int z = -2; z <= 2; z++)
				{
					for(int y = -1; y <= 1; y++)
					{
						BlockPos pos = shoreSpot.add(x, y, z);
						if(isWater(pos))
						{
							double dist = pos.getSquaredDistance(shoreSpot);
							if(dist < minDist)
							{
								minDist = dist;
								closestWater = pos;
							}
						}
					}
				}
			}
		}
		
		return closestWater;
	}
	
	private boolean isValidStandingSpot(BlockPos pos)
	{
		if(MC.world == null)
			return false;
		
		// Must have solid ground below
		BlockState below = MC.world.getBlockState(pos.down());
		if(!below.isSolidBlock(MC.world, pos.down()))
			return false;
		
		// Current position and above must be air/passable
		BlockState current = MC.world.getBlockState(pos);
		BlockState above = MC.world.getBlockState(pos.up());
		
		boolean currentPassable =
			!current.isSolidBlock(MC.world, pos) && !isWater(pos);
		boolean abovePassable = !above.isSolidBlock(MC.world, pos.up());
		
		return currentPassable && abovePassable;
	}
	
	private boolean navigateToSpot()
	{
		if(MC.player == null || currentFishingSpot == null)
			return false;
		
		BlockPos playerPos = MC.player.getBlockPos();
		double distance =
			Math.sqrt(playerPos.getSquaredDistance(currentFishingSpot));
		
		// close enough to the shore spot
		if(distance <= 0.8)
		{
			onArrivedAtSpot();
			return true;
		}
		
		// if pathfinder already gave up, discard this spot and try again next
		// tick
		if(pathFinder != null && pathFinder.isDone() && pathFinder.isFailed())
		{
			PathProcessor.releaseControls();
			pathFinder = null;
			processor = null;
			currentFishingSpot = null;
			walkingToSpot = false;
			return false;
		}
		
		// compute path if still thinking
		if(pathFinder != null && !pathFinder.isDone() && !pathFinder.isFailed())
		{
			PathProcessor.lockControls();
			pathFinder.think();
			
			if(!pathFinder.isDone())
				return false;
			
			if(!pathFinder.isFailed())
			{
				pathFinder.formatPath();
				processor = pathFinder.getProcessor();
			}
			
			// if it finished but failed, the failure branch above
			// will take care of it on the next tick
			return false;
		}
		
		// follow the path
		if(processor != null && !processor.isDone())
		{
			processor.process();
			return false;
		}
		
		// reached end of path successfully
		if(processor != null && processor.isDone())
		{
			onArrivedAtSpot();
			return true;
		}
		
		return false;
	}
	
	private void faceWater()
	{
		if(MC.player == null || waterTarget == null)
			return;
		
		// Recalculate angles to ensure we're looking at the water block
		Vec3d playerPos = MC.player.getEyePos();
		Vec3d waterPos = Vec3d.ofCenter(waterTarget);
		Vec3d toWater = waterPos.subtract(playerPos);
		
		float correctYaw =
			(float)Math.toDegrees(Math.atan2(toWater.z, toWater.x)) - 90F;
		float correctPitch = (float)Math.toDegrees(Math.atan2(toWater.y,
			Math.sqrt(toWater.x * toWater.x + toWater.z * toWater.z)));
		
		float minP = (float)pitchMin.getValue();
		float maxP = (float)pitchMax.getValue();
		correctPitch = MathHelper.clamp(correctPitch, minP, maxP);
		
		float yawDiff = MathHelper.wrapDegrees(correctYaw - MC.player.getYaw());
		MC.player.setYaw(MC.player.getYaw() + yawDiff * 0.2f);
		
		float pitchDiff = correctPitch - MC.player.getPitch();
		MC.player.setPitch(MC.player.getPitch() + pitchDiff * 0.2f);
	}
	
	private void setTimer()
	{
		int baseTime = (int)(waitTime.getValue() * 20);
		double randTime = waitTimeRand.getValue() * 20;
		int randOffset = (int)(random.nextGaussian() * randTime);
		randOffset = Math.max(randOffset, -baseTime);
		timer = baseTime + randOffset;
	}
	
	private class FishingPathFinder extends PathFinder
	{
		public FishingPathFinder(BlockPos goal)
		{
			super(goal);
			setThinkTime(10);
			setFallingAllowed(true);
			setDivingAllowed(false);
		}
	}
}
