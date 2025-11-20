/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"staff", "avoid", "mod detector", "auto leave"})
public final class AvoidStaffHack extends Hack implements UpdateListener
{
	// ===== Staff lists =====
	private final TextFieldSetting onlineStaffNames = new TextFieldSetting(
		"Online staff names",
		"Comma-separated list of staff usernames to detect in the TAB / player list.\n"
			+ "Example: Admin,Mod,Helper",
		"");
	
	private final TextFieldSetting nearbyStaffNames = new TextFieldSetting(
		"Nearby staff names",
		"Comma-separated list of staff usernames to detect when they are nearby / rendered.\n"
			+ "Example: Admin,Mod,Helper",
		"");
	
	// ===== Detection mode =====
	private final EnumSetting<DetectionMode> detectionMode = new EnumSetting<>(
		"Detection mode", "Which method(s) to use to detect staff.",
		DetectionMode.values(), DetectionMode.BOTH);
	
	private final EnumSetting<ReactionMode> reactionMode = new EnumSetting<>(
		"Reaction", "What to do if a listed staff is detected.",
		ReactionMode.values(), ReactionMode.DISABLE);
	
	// ===== Hacks to disable =====
	private final CheckboxSetting disableSneak =
		new CheckboxSetting("Disable Sneak hack", true);
	private final CheckboxSetting disableTriggerBot =
		new CheckboxSetting("Disable TriggerBot hack", true);
	private final CheckboxSetting disableKillAura =
		new CheckboxSetting("Disable KillAuraLegit hack", true);
	
	private final CheckboxSetting disableChatGptResponder =
		new CheckboxSetting("Disable ChatGptResponder hack", true);
	
	private final CheckboxSetting disableAntiAfkFishing =
		new CheckboxSetting("Disable AntiAfkFishing hack", true);
	
	private final CheckboxSetting disableAutoFish =
		new CheckboxSetting("Disable AutoFish hack", true);
	
	// ===== State =====
	private boolean modsDisabled = false;
	
	private boolean prevSneak;
	private boolean prevTriggerBot;
	private boolean prevKillAura;
	
	private boolean prevChatGptResponder;
	private boolean prevAntiAfkFishing;
	private boolean prevAutoFish;
	
	public AvoidStaffHack()
	{
		super("AvoidStaff");
		setCategory(Category.OTHER);
		
		addSetting(onlineStaffNames);
		addSetting(nearbyStaffNames);
		addSetting(detectionMode);
		addSetting(reactionMode);
		
		addSetting(disableSneak);
		addSetting(disableTriggerBot);
		addSetting(disableKillAura);
		addSetting(disableChatGptResponder);
		addSetting(disableAntiAfkFishing);
		addSetting(disableAutoFish);
	}
	
	private enum DetectionMode
	{
		ONLINE("Online staff only"),
		NEARBY("Nearby / rendered staff only"),
		BOTH("Both online & nearby");
		
		private final String name;
		
		DetectionMode(String n)
		{
			name = n;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum ReactionMode
	{
		LEAVE("Leave server"),
		DISABLE("Disable selected hacks");
		
		private final String name;
		
		ReactionMode(String n)
		{
			name = n;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		modsDisabled = false;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		restoreMods();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null
			|| MC.getNetworkHandler() == null)
			return;
		
		// parse staff lists
		Set<String> onlineWatch = parseNames(onlineStaffNames.getValue());
		Set<String> nearbyWatch = parseNames(nearbyStaffNames.getValue());
		
		if(onlineWatch.isEmpty() && nearbyWatch.isEmpty())
		{
			// nothing to watch -> just restore and idle
			if(modsDisabled)
				restoreMods();
			return;
		}
		
		boolean staffFound = false;
		String detectedName = null;
		
		switch(detectionMode.getSelected())
		{
			case ONLINE:
			detectedName = detectOnlineStaff(onlineWatch);
			staffFound = detectedName != null;
			break;
			
			case NEARBY:
			detectedName = detectNearbyStaff(nearbyWatch);
			staffFound = detectedName != null;
			break;
			
			case BOTH:
			detectedName = detectOnlineStaff(onlineWatch);
			if(detectedName == null)
				detectedName = detectNearbyStaff(nearbyWatch);
			staffFound = detectedName != null;
			break;
		}
		
		if(staffFound)
			react(detectedName);
		else if(modsDisabled)
			restoreMods();
	}
	
	// =========================================================
	// Detection helpers
	// =========================================================
	
	private Set<String> parseNames(String csv)
	{
		Set<String> set = new HashSet<>();
		Arrays.stream(csv.split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).forEach(s -> set.add(s.toLowerCase()));
		return set;
	}
	
	private String detectOnlineStaff(Set<String> watch)
	{
		if(watch.isEmpty())
			return null;
		
		ClientPlayNetworkHandler nh = MC.getNetworkHandler();
		for(PlayerListEntry entry : nh.getPlayerList())
		{
			if(entry == null || entry.getProfile() == null)
				continue;
			String name = entry.getProfile().getName();
			if(name == null)
				continue;
			
			if(watch.contains(name.toLowerCase()))
				return name;
		}
		return null;
	}
	
	private String detectNearbyStaff(Set<String> watch)
	{
		if(watch.isEmpty())
			return null;
		
		for(PlayerEntity player : MC.world.getPlayers())
		{
			if(player == null || player == MC.player)
				continue;
			
			String name = player.getGameProfile().getName();
			if(name == null)
				continue;
			
			if(watch.contains(name.toLowerCase()))
				return name;
		}
		return null;
	}
	
	// =========================================================
	// Reaction & mod toggling
	// =========================================================
	
	private void react(String staffName)
	{
		switch(reactionMode.getSelected())
		{
			case LEAVE:
			if(MC.getNetworkHandler() != null)
			{
				MC.player.networkHandler.getConnection()
					.disconnect(net.minecraft.text.Text
						.of("AvoidStaff: Detected staff " + staffName));
			}
			break;
			
			case DISABLE:
			if(!modsDisabled)
				disableMods();
			break;
		}
	}
	
	private void disableMods()
	{
		var hax = WurstClient.INSTANCE.getHax();
		
		// snapshot current states
		prevSneak = hax.sneakHack.isEnabled();
		prevTriggerBot = hax.triggerBotHack.isEnabled();
		prevKillAura = hax.killauraLegitHack.isEnabled();
		
		prevChatGptResponder = hax.chatGptResponderHack != null
			&& hax.chatGptResponderHack.isEnabled();
		prevAntiAfkFishing = hax.AntiAfkFishingHack != null
			&& hax.AntiAfkFishingHack.isEnabled();
		prevAutoFish = hax.autoFishHack != null && hax.autoFishHack.isEnabled();
		
		// apply disables according to settings
		if(disableSneak.isChecked())
			hax.sneakHack.setEnabled(false);
		if(disableTriggerBot.isChecked())
			hax.triggerBotHack.setEnabled(false);
		if(disableKillAura.isChecked())
			hax.killauraLegitHack.setEnabled(false);
		
		if(disableChatGptResponder.isChecked()
			&& hax.chatGptResponderHack != null)
			hax.chatGptResponderHack.setEnabled(false);
		
		if(disableAntiAfkFishing.isChecked() && hax.AntiAfkFishingHack != null)
			hax.AntiAfkFishingHack.setEnabled(false);
		
		if(disableAutoFish.isChecked() && hax.autoFishHack != null)
			hax.autoFishHack.setEnabled(false);
		
		modsDisabled = true;
	}
	
	private void restoreMods()
	{
		if(!modsDisabled)
			return;
		
		var hax = WurstClient.INSTANCE.getHax();
		
		// restore previous states
		if(disableSneak.isChecked())
			hax.sneakHack.setEnabled(prevSneak);
		if(disableTriggerBot.isChecked())
			hax.triggerBotHack.setEnabled(prevTriggerBot);
		if(disableKillAura.isChecked())
			hax.killauraLegitHack.setEnabled(prevKillAura);
		
		if(disableChatGptResponder.isChecked()
			&& hax.chatGptResponderHack != null)
			hax.chatGptResponderHack.setEnabled(prevChatGptResponder);
		
		if(disableAntiAfkFishing.isChecked() && hax.AntiAfkFishingHack != null)
			hax.AntiAfkFishingHack.setEnabled(prevAntiAfkFishing);
		
		if(disableAutoFish.isChecked() && hax.autoFishHack != null)
			hax.autoFishHack.setEnabled(prevAutoFish);
		
		modsDisabled = false;
	}
}
