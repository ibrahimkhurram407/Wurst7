/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;

import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

public final class NoSlowFallHack extends Hack implements UpdateListener
{
	private static final MinecraftClient MC = WurstClient.MC;
	
	/** If ON, only applies when you actually have the Slow Falling effect. */
	private final CheckboxSetting onlyWithEffect = new CheckboxSetting(
		"Only with Slow Falling",
		"If ON, the hack activates only while the Slow Falling status effect is active.\n"
			+ "If OFF, it applies the extra gravity whenever you are falling.",
		true);
	
	/**
	 * Extra gravity to apply each tick.
	 * Vanilla gravity is ~0.08/tick; 0.08–0.12 gives “normal” or slightly
	 * faster fall.
	 */
	private final SliderSetting extraGravity = new SliderSetting(
		"Extra gravity per tick",
		"How much downward speed to add back each tick.\n0.08 ≈ vanilla gravity.",
		0.10, 0.00, 0.20, 0.005, ValueDisplay.DECIMAL);
	
	/** Clamp the maximum downward speed to avoid speeding flags. */
	private final SliderSetting maxDownSpeed = new SliderSetting(
		"Max downward speed", "Caps your downward velocity while active.", 0.60,
		0.05, 1.20, 0.01, ValueDisplay.DECIMAL);
	
	/** Slightly ease landing if enabled. */
	private final CheckboxSetting smoothLandings = new CheckboxSetting(
		"Smoother landings",
		"If ON, eases the last bit of descent by not forcing max speed near the ground.",
		false);
	
	// Cached reflection handle for cross-mapping elytra check
	private java.lang.reflect.Method isFallFlyingM;
	private boolean hasIsFallFlyingM;
	
	public NoSlowFallHack()
	{
		super("NoSlowFall");
		setCategory(Category.MOVEMENT);
		addSetting(onlyWithEffect);
		addSetting(extraGravity);
		addSetting(maxDownSpeed);
		addSetting(smoothLandings);
	}
	
	@Override
	protected void onEnable()
	{
		// Cache reflection for isFallFlying() if available in this mapping
		try
		{
			isFallFlyingM = MC.player != null
				? MC.player.getClass().getMethod("isFallFlying") : null;
			if(isFallFlyingM != null)
				isFallFlyingM.setAccessible(true);
			hasIsFallFlyingM = isFallFlyingM != null;
		}catch(Throwable ignored)
		{
			isFallFlyingM = null;
			hasIsFallFlyingM = false;
		}
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		isFallFlyingM = null;
		hasIsFallFlyingM = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null || MC.isPaused())
			return;
		
		// Respect the "Only with Slow Falling" toggle
		if(onlyWithEffect.isChecked()
			&& !MC.player.hasStatusEffect(StatusEffects.SLOW_FALLING))
			return;
		
		// Skip states where forcing descent is undesirable or could conflict
		if(MC.player.isOnGround() || MC.player.isTouchingWater()
			|| MC.player.isInLava() || MC.player.isClimbing())
			return;
		
		// Cross-version: elytra/flying guard
		if(hasIsFallFlyingM)
		{
			try
			{
				if((boolean)isFallFlyingM.invoke(MC.player))
					return;
			}catch(Throwable ignored)
			{}
		}else
		{
			if(MC.player.getAbilities().flying || MC.player.isUsingRiptide())
				return;
		}
		
		// Only apply while actually falling downward
		Vec3d v = MC.player.getVelocity();
		double y = v.y;
		if(y >= 0.0)
			return;
		
		// Apply extra gravity
		y -= extraGravity.getValue();
		
		// Optional soft cap near ground
		if(!smoothLandings.isChecked()
			|| MC.player.getY() - MC.player.getBlockY() > 1.0)
		{
			double cap = -maxDownSpeed.getValue();
			if(y < cap)
				y = cap;
		}
		
		MC.player.setVelocity(v.x, y, v.z);
		MC.player.velocityModified = true; // ensure downstream sync
	}
}
