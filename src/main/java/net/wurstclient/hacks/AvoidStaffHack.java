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
	private final TextFieldSetting staffNames = new TextFieldSetting(
		"Staff names",
		"Comma-separated list of usernames to watch for.\nExample: Admin,Mod,Helper",
		"");
	
	private final EnumSetting<ReactionMode> reactionMode =
		new EnumSetting<>("Reaction", "What to do if a listed staff joins.",
			ReactionMode.values(), ReactionMode.LEAVE);
	
	// Mods to disable (can be extended)
	private final CheckboxSetting disableSneak =
		new CheckboxSetting("Disable Sneak hack", true);
	private final CheckboxSetting disableTriggerBot =
		new CheckboxSetting("Disable TriggerBot hack", true);
	private final CheckboxSetting disableKillAura =
		new CheckboxSetting("Disable KillAuraLegit hack", true);
	
	private boolean modsDisabled = false; // track if we disabled mods
	private boolean prevSneak, prevTriggerBot, prevKillAura;
	
	public AvoidStaffHack()
	{
		super("AvoidStaff");
		setCategory(Category.OTHER);
		
		addSetting(staffNames);
		addSetting(reactionMode);
		addSetting(disableSneak);
		addSetting(disableTriggerBot);
		addSetting(disableKillAura);
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
		// restore hacks if they were disabled by us
		restoreMods();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getNetworkHandler() == null)
			return;
		
		// parse staff names
		Set<String> watch = new HashSet<>();
		Arrays.stream(staffNames.getValue().split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).forEach(s -> watch.add(s.toLowerCase()));
		
		if(watch.isEmpty())
			return;
		
		boolean staffFound = false;
		
		// check player list
		ClientPlayNetworkHandler nh = MC.getNetworkHandler();
		for(PlayerListEntry entry : nh.getPlayerList())
		{
			if(entry == null || entry.getProfile() == null)
				continue;
			String name = entry.getProfile().getName();
			if(name == null)
				continue;
			
			if(watch.contains(name.toLowerCase()))
			{
				staffFound = true;
				react(name);
				break;
			}
		}
		
		// If staff left → restore hacks
		if(!staffFound && modsDisabled)
		{
			restoreMods();
		}
	}
	
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
			if(!modsDisabled) // only disable once
			{
				var hax = WurstClient.INSTANCE.getHax();
				
				prevSneak = hax.sneakHack.isEnabled();
				prevTriggerBot = hax.triggerBotHack.isEnabled();
				prevKillAura = hax.killauraLegitHack.isEnabled();
				
				if(disableSneak.isChecked())
					hax.sneakHack.setEnabled(false);
				if(disableTriggerBot.isChecked())
					hax.triggerBotHack.setEnabled(false);
				if(disableKillAura.isChecked())
					hax.killauraLegitHack.setEnabled(false);
				
				modsDisabled = true;
			}
			break;
		}
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
		
		modsDisabled = false;
	}
}
