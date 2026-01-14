/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Random;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;

@SearchTags({"afk", "realistic", "humanize", "randomize", "xp grind"})
public final class RealisticAfkGrindHack extends Hack implements UpdateListener
{
	
	// ===== Behavior toggles =====
	private final CheckboxSetting manageSneak = new CheckboxSetting(
		"Manage Sneak", "Randomly enable/disable Sneak over time.", true);
	private final CheckboxSetting manageTriggerBot =
		new CheckboxSetting("Manage TriggerBot",
			"Randomly enable/disable TriggerBot over time.", true);
	
	private final CheckboxSetting doTinyMoves = new CheckboxSetting(
		"Tiny movement nudges",
		"Occasional tiny movement so you don't look like a statue (keeps you on the same block).",
		true);
	private final CheckboxSetting doAimJitter = new CheckboxSetting(
		"Tiny aim jitter",
		"Small random yaw/pitch jitter around your starting look direction.",
		true);
	
	// ===== Timing & randomness =====
	private final SliderSetting toggleMeanMinutes =
		new SliderSetting("Mean toggle period (min)",
			"Average minutes between Sneak/TriggerBot toggles.", 2.0, 0.25,
			10.0, 0.25, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting toggleJitterPct = new SliderSetting(
		"Toggle jitter (%)", "Randomness around the mean toggle period.", 35, 0,
		100, 5, SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting moveEveryMs = new SliderSetting(
		"Move nudge every (ms)", "How often to attempt a tiny movement nudge.",
		3500, 500, 10000, 100, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting aimEveryMs = new SliderSetting(
		"Aim jitter every (ms)", "How often to slightly adjust yaw/pitch.",
		3000, 500, 10000, 100, SliderSetting.ValueDisplay.DECIMAL);
	
	// ===== Movement bounds & strengths =====
	private final SliderSetting maxOffsetBlock =
		new SliderSetting("Max offset within block",
			"Maximum allowed distance from your block center (in blocks).",
			0.18, 0.05, 0.49, 0.01, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting nudgeVelocity = new SliderSetting(
		"Nudge strength", "Velocity applied toward center or in tiny wiggles.",
		0.045, 0.005, 0.12, 0.005, SliderSetting.ValueDisplay.DECIMAL);
	
	// ===== Aim jitter bounds =====
	private final SliderSetting yawJitterDeg = new SliderSetting(
		"Yaw jitter (°)", "Max absolute yaw jitter (left/right) around start.",
		2.0, 0.2, 10.0, 0.2, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting pitchJitterDeg = new SliderSetting(
		"Pitch jitter (°)", "Max absolute pitch jitter (up/down) around start.",
		1.0, 0.2, 6.0, 0.2, SliderSetting.ValueDisplay.DECIMAL);
	
	private final CheckboxSetting keepPitchBounds = new CheckboxSetting(
		"Clamp pitch to [-89, 89]", "Avoids weird flips.", true);
	
	// ===== State =====
	private final Random rng = new Random();
	private long nextToggleAt = 0L;
	private long nextMoveAt = 0L;
	private long nextAimAt = 0L;
	
	private BlockPos anchorBlock; // block you started on
	private double anchorCx, anchorCz; // center X/Z of the anchor block
	private float baseYaw, basePitch; // starting look
	private boolean initialSneakEnabled;
	private boolean initialTriggerBotEnabled;
	
	public RealisticAfkGrindHack()
	{
		super("RealisticAfkGrind");
		setCategory(Category.OTHER);
		
		addSetting(manageSneak);
		addSetting(manageTriggerBot);
		addSetting(doTinyMoves);
		addSetting(doAimJitter);
		
		addSetting(toggleMeanMinutes);
		addSetting(toggleJitterPct);
		
		addSetting(moveEveryMs);
		addSetting(aimEveryMs);
		
		addSetting(maxOffsetBlock);
		addSetting(nudgeVelocity);
		
		addSetting(yawJitterDeg);
		addSetting(pitchJitterDeg);
		addSetting(keepPitchBounds);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		
		if(MC.player == null)
			return;
		
		// Capture anchors
		ClientPlayerEntity p = MC.player;
		anchorBlock = p.getBlockPos();
		anchorCx = anchorBlock.getX() + 0.5;
		anchorCz = anchorBlock.getZ() + 0.5;
		baseYaw = p.getYaw();
		basePitch = p.getPitch();
		
		// Remember current states (so your first random toggle isn't forced
		// right away)
		var hax = WurstClient.INSTANCE.getHax();
		initialSneakEnabled = hax.sneakHack.isEnabled();
		initialTriggerBotEnabled = hax.triggerBotHack.isEnabled();
		
		long now = System.currentTimeMillis();
		nextToggleAt = now + drawToggleDelayMs();
		nextMoveAt = now + (long)moveEveryMs.getValue() + jitter(250);
		nextAimAt = now + (long)aimEveryMs.getValue() + jitter(250);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null)
			return;
		
		long now = System.currentTimeMillis();
		
		// 1) Periodic random toggles for Sneak / TriggerBot
		if(now >= nextToggleAt)
		{
			randomToggleSelectedHacks();
			nextToggleAt = now + drawToggleDelayMs();
		}
		
		// 2) Keep player near anchor center with small nudges
		if(doTinyMoves.isChecked() && now >= nextMoveAt)
		{
			applyTinyMovementNudge();
			nextMoveAt = now + (long)moveEveryMs.getValue() + jitter(200);
		}
		
		// 3) Light aim jitter around base yaw/pitch
		if(doAimJitter.isChecked() && now >= nextAimAt)
		{
			applyAimJitter();
			nextAimAt = now + (long)aimEveryMs.getValue() + jitter(200);
		}
	}
	
	// ====== Implementation details ======
	
	private long drawToggleDelayMs()
	{
		// mean +/- jitter%
		double meanMs = toggleMeanMinutes.getValue() * 60_000.0;
		double pct = toggleJitterPct.getValue() / 100.0;
		double min = meanMs * (1.0 - pct);
		double max = meanMs * (1.0 + pct);
		long delay = (long)(min + rng.nextDouble() * (max - min));
		// Safety floor
		return Math.max(delay, 5_000L);
	}
	
	private int jitter(int maxAbsMs)
	{
		return rng.nextInt(maxAbsMs * 2 + 1) - maxAbsMs; // [-max, +max]
	}
	
	private void randomToggleSelectedHacks()
	{
		var hax = WurstClient.INSTANCE.getHax();
		
		// Sneak
		if(manageSneak.isChecked())
		{
			boolean flip = rng.nextBoolean();
			if(flip)
				hax.sneakHack.setEnabled(!hax.sneakHack.isEnabled());
		}
		
		// TriggerBot
		if(manageTriggerBot.isChecked())
		{
			boolean flip = rng.nextBoolean();
			if(flip)
				hax.triggerBotHack.setEnabled(!hax.triggerBotHack.isEnabled());
		}
	}
	
	private void applyTinyMovementNudge()
	{
		ClientPlayerEntity p = MC.player;
		if(p == null)
			return;
		
		// Compute offset vs. center of anchor block
		double dx = p.getX() - anchorCx;
		double dz = p.getZ() - anchorCz;
		double dist = Math.hypot(dx, dz);
		
		double maxR = maxOffsetBlock.getValue();
		
		// If we drifted too far, nudge back toward center; otherwise do a tiny
		// random wiggle
		double vx, vz;
		if(dist > maxR)
		{
			// vector toward center (normalized)
			double len = Math.max(dist, 1e-6);
			vx = -dx / len;
			vz = -dz / len;
		}else
		{
			// small random inside circle
			double ang = rng.nextDouble() * Math.PI * 2.0;
			vx = Math.cos(ang);
			vz = Math.sin(ang);
		}
		
		double mag = nudgeVelocity.getValue();
		// apply on XZ; keep Y velocity untouched
		p.setVelocity(vx * mag, p.getVelocity().y, vz * mag);
	}
	
	private void applyAimJitter()
	{
		ClientPlayerEntity p = MC.player;
		if(p == null)
			return;
		
		// draw small random around base yaw/pitch
		float yawMax = (float)yawJitterDeg.getValue();
		float pitchMax = (float)pitchJitterDeg.getValue();
		
		float newYaw = baseYaw + (float)(rng.nextGaussian() * (yawMax * 0.33f)); // normal-ish,
																					// mostly
																					// small
		float newPitch =
			basePitch + (float)(rng.nextGaussian() * (pitchMax * 0.33f));
		
		// clamp pitch if desired
		if(keepPitchBounds.isChecked())
		{
			if(newPitch > 89f)
				newPitch = 89f;
			if(newPitch < -89f)
				newPitch = -89f;
		}
		
		// Apply
		p.setYaw(newYaw);
		p.setPitch(newPitch);
	}
	
	// Expose base anchor reset if needed later (could add a keybind)
	@SuppressWarnings("unused")
	private void resetAnchor()
	{
		if(MC.player == null)
			return;
		ClientPlayerEntity p = MC.player;
		anchorBlock = p.getBlockPos();
		anchorCx = anchorBlock.getX() + 0.5;
		anchorCz = anchorBlock.getZ() + 0.5;
		baseYaw = p.getYaw();
		basePitch = p.getPitch();
	}
}
