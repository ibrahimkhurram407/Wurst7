/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;

public final class WindPearlHack extends Hack implements UpdateListener
{
	private static final MinecraftClient MC = WurstClient.MC;
	private static final IMinecraftClient IMC = WurstClient.IMC;
	private static final WurstClient WURST = WurstClient.INSTANCE;
	
	private enum Phase
	{
		IDLE,
		PEARLED,
		BURSTED
	}
	
	// Delay between PEARL and WIND BURST
	private final SliderSetting delayMs = new SliderSetting("Delay (ms)",
		"Delay between Ender Pearl throw and Wind Burst placement.", 300, 0,
		1500, 10, ValueDisplay.INTEGER);
	
	// New: optionally restore AutoMace after we finish (only if it was on
	// before)
	private final CheckboxSetting reenableAutoMace =
		new CheckboxSetting("Re-enable AutoMace when done",
			"If ON, AutoMace will be turned back on after WindPearl finishes\n"
				+ "(only if it was enabled before WindPearl started).",
			true);
	
	private Phase phase = Phase.IDLE;
	private long t0ns = 0L;
	private boolean autoMaceWasOn = false;
	
	public WindPearlHack()
	{
		super("WindPearl");
		setCategory(Category.COMBAT);
		addSetting(delayMs);
		addSetting(reenableAutoMace);
	}
	
	@Override
	protected void onEnable()
	{
		// Remember current AutoMace state and pause it while performing the
		// combo
		try
		{
			autoMaceWasOn = WURST.getHax().autoMaceHack.isEnabled();
			WURST.getHax().autoMaceHack.setEnabled(false);
		}catch(Throwable ignored)
		{}
		
		phase = Phase.IDLE;
		t0ns = 0L;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		// Restore AutoMace if user wants and it was previously on
		try
		{
			if(reenableAutoMace.isChecked() && autoMaceWasOn)
			{
				WURST.getHax().autoMaceHack.setEnabled(true);
			}
		}catch(Throwable ignored)
		{}
		
		autoMaceWasOn = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
		{
			setEnabled(false);
			return;
		}
		
		switch(phase)
		{
			case IDLE ->
			{
				// Step 1: throw pearl immediately
				if(throwPearl())
				{
					t0ns = System.nanoTime();
					phase = Phase.PEARLED;
				}else
				{
					setEnabled(false); // no pearl -> stop
				}
			}
			
			case PEARLED ->
			{
				// Step 2: after delay, place wind charge below feet
				long waitNs =
					TimeUnit.MILLISECONDS.toNanos(delayMs.getValueI());
				if(System.nanoTime() - t0ns >= waitNs)
				{
					tryWindBurstStraightUp();
					// end
					phase = Phase.BURSTED;
				}
			}
			
			case BURSTED ->
			{
				// Step 3: clean exit (onDisable will handle AutoMace
				// restoration)
				setEnabled(false);
			}
		}
	}
	
	private static void faceVecClient(Vec3d target)
	{
		if(MC.player == null)
			return;
		
		// If your project has RotationUtils.getEyesPos(), prefer that.
		Vec3d eyes;
		try
		{
			eyes = net.wurstclient.util.RotationUtils.getEyesPos();
		}catch(Throwable t)
		{
			eyes = MC.player.getEyePos();
		}
		
		Vec3d diff = target.subtract(eyes);
		
		double dx = diff.x;
		double dy = diff.y;
		double dz = diff.z;
		
		double distXZ = Math.sqrt(dx * dx + dz * dz);
		
		float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float)(-Math.toDegrees(Math.atan2(dy, distXZ)));
		
		MC.player.setYaw(yaw);
		MC.player.setPitch(pitch);
	}
	
	private static void lookStraightUp()
	{
		if(MC.player == null)
			return;
			
		// Optional: stabilize yaw too (doesn't matter for straight up, but
		// avoids weirdness in some clones)
		// MC.player.setYaw(MC.player.getYaw());
		
		MC.player.setPitch(-90.0F);
	}
	
	private boolean throwPearl()
	{
		Predicate<ItemStack> isPearl =
			s -> s != null && !s.isEmpty() && s.isOf(Items.ENDER_PEARL);
		
		if(!InventoryUtils.selectItem(isPearl, 36, true))
			return false;
		
		float oldYaw = MC.player.getYaw();
		float oldPitch = MC.player.getPitch();
		
		try
		{
			// Always throw straight up
			lookStraightUp();
			// sendLookPacketIfPossible(); // optional
			
			try
			{
				IMC.getInteractionManager().rightClickItem();
			}catch(Throwable t)
			{
				try
				{
					MC.interactionManager.interactItem(MC.player,
						Hand.MAIN_HAND);
				}catch(Throwable ignored)
				{}
			}
			
			return true;
			
		}finally
		{
			try
			{
				MC.player.setYaw(oldYaw);
				MC.player.setPitch(oldPitch);
			}catch(Throwable ignored)
			{}
		}
	}
	
	private void tryWindBurstStraightUp()
	{
		Predicate<ItemStack> isWind =
			s -> s != null && !s.isEmpty() && (s.isOf(Items.WIND_CHARGE)
				|| s.getName().getString().toLowerCase().contains("wind"));
		
		if(!InventoryUtils.selectItem(isWind, 36, true))
			return;
		
		float oldYaw = MC.player.getYaw();
		float oldPitch = MC.player.getPitch();
		
		try
		{
			// Force look straight up during the use
			lookStraightUp();
			// sendLookPacketIfPossible(); // optional (helps if server cares)
			
			BlockPos below = MC.player.getBlockPos().down();
			
			// Click the TOP face of the block below you.
			// Keep the hitVec on the top face; "straight up" is enforced by
			// rotation above.
			Vec3d hitVec = new Vec3d(MC.player.getX(),
				below.getY() + 1.0 - 1.0e-3, MC.player.getZ());
			
			IMC.getInteractionManager().rightClickBlock(below, Direction.UP,
				hitVec);
			
		}catch(Throwable t)
		{
			try
			{
				IMC.getInteractionManager().rightClickItem();
			}catch(Throwable ignored)
			{}
		}finally
		{
			try
			{
				MC.player.setYaw(oldYaw);
				MC.player.setPitch(oldPitch);
			}catch(Throwable ignored)
			{}
		}
	}
	
}
