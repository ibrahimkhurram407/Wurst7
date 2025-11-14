/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.encryption.ClientPlayerSession;
import net.minecraft.network.message.MessageChain;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import net.wurstclient.Category;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

@DontBlock
@SearchTags({"no chat reports", "NoEncryption", "no encryption",
	"NoChatSigning", "no chat signing"})
public final class NoChatReportsOtf extends OtherFeature
	implements UpdateListener, ChatInputListener
{
	private final CheckboxSetting disableSignatures =
		new CheckboxSetting("Disable signatures", true)
		{
			@Override
			public void update()
			{
				EVENTS.add(UpdateListener.class, NoChatReportsOtf.this);
			}
		};
	
	public NoChatReportsOtf()
	{
		super("NoChatReports", "description.wurst.other_feature.nochatreports");
		addSetting(disableSignatures);
		
		ClientLoginConnectionEvents.INIT.register(this::onLoginStart);
		EVENTS.add(ChatInputListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		ClientPlayNetworkHandler netHandler = MC.getNetworkHandler();
		if(netHandler == null)
			return;
		
		if(isActive())
		{
			netHandler.session = null;
			netHandler.messagePacker = MessageChain.Packer.NONE;
			
		}else if(netHandler.session == null)
			MC.getProfileKeys().fetchKeyPair()
				.thenAcceptAsync(optional -> optional
					.ifPresent(profileKeys -> netHandler.session =
						ClientPlayerSession.create(profileKeys)),
					MC);
		
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		return; // completely suppresses the “chat disabled / click here”
				// message
	}
	
	private void onLoginStart(ClientLoginNetworkHandler handler,
		MinecraftClient client)
	{
		EVENTS.add(UpdateListener.class, NoChatReportsOtf.this);
	}
	
	public MessageIndicator modifyIndicator(Text message,
		MessageSignatureData signature, MessageIndicator indicator)
	{
		// Don’t add any indicator — leave whatever was already there.
		return indicator;
	}
	
	@Override
	public boolean isEnabled()
	{
		return disableSignatures.isChecked();
	}
	
	public boolean isActive()
	{
		return isEnabled() && WURST.isEnabled() && !MC.isInSingleplayer();
	}
	
	@Override
	public String getPrimaryAction()
	{
		return WURST.translate("button.wurst.nochatreports."
			+ (isEnabled() ? "re-enable_signatures" : "disable_signatures"));
	}
	
	@Override
	public void doPrimaryAction()
	{
		disableSignatures.setChecked(!disableSignatures.isChecked());
	}
	
	@Override
	public Category getCategory()
	{
		return Category.CHAT;
	}
	
	// See ChatHudMixin, ClientPlayNetworkHandlerMixin.onOnServerMetadata(),
	// MinecraftClientMixin.onGetProfileKeys()
}
