/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.autofish.AutoFishDebugDraw;
import net.wurstclient.hacks.autofish.AutoFishRodSelector;
import net.wurstclient.hacks.autofish.FishingSpotManager;
import net.wurstclient.hacks.autofish.ShallowWaterWarningCheckbox;
import net.wurstclient.settings.CheckboxSetting;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"AutoFishing", "auto fishing", "AutoFisher", "auto fisher",
	"AFKFishBot", "afk fish bot", "AFKFishingBot", "afk fishing bot",
	"AFKFisherBot", "afk fisher bot"})
public final class AutoFishHack extends Hack
	implements UpdateListener, PacketInputListener, RenderListener
{
	private final EnumSetting<AutoFishHack.BiteMode> biteMode =
		new EnumSetting<>("Bite mode",
			"\u00a7lSound\u00a7r mode detects bites by listening for the bite sound."
				+ " This method is less accurate, but is more resilient against"
				+ " anti-cheats. See the \"Valid range\" setting.\n\n"
				+ "\u00a7lEntity\u00a7r mode detects bites by checking for the"
				+ " fishing hook's entity update packet. It's more accurate than"
				+ " the sound method, but is less resilient against anti-cheats.",
			AutoFishHack.BiteMode.values(), AutoFishHack.BiteMode.SOUND);
	
	private final SliderSetting validRange = new SliderSetting("Valid range",
		"Any bites that occur outside of this range will be ignored.\n\n"
			+ "Increase your range if bites are not being detected, decrease it"
			+ " if other people's bites are being detected as yours.\n\n"
			+ "This setting has no effect when \"Bite mode\" is set to \"Entity\".",
		1.5, 0.25, 8, 0.25, ValueDisplay.DECIMAL);
	
	private final SliderSetting catchDelay = new SliderSetting("Catch delay",
		"How long AutoFish will wait after a bite before reeling in.", 0, 0, 60,
		1, ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting retryDelay = new SliderSetting("Retry delay",
		"If casting or reeling in the fishing rod fails, this is how long"
			+ " AutoFish will wait before trying again.",
		15, 0, 100, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting patience = new SliderSetting("Patience",
		"How long AutoFish will wait if it doesn't get a bite before reeling in.",
		60, 10, 120, 1, ValueDisplay.INTEGER.withSuffix("s"));
	
	// --- Auto-sell settings ---
	private final CheckboxSetting autoSell = new CheckboxSetting(
		"Auto-sell when full",
		"When inventory is full, run the command and click the configured button.",
		true);
	
	// The chat command that opens the shop GUI:
	private final TextFieldSetting sellCommand =
		new TextFieldSetting("Sell command", "/fishshop");
	
	// Either match a button by its visible name...
	private final TextFieldSetting sellButtonText =
		new TextFieldSetting("Button text (optional)", "Sell All");
	
	// ...or click by slot index (0..53 typical for 6x9 chest-like GUIs)
	private final SliderSetting sellButtonSlot = new SliderSetting(
		"Button slot index", "GUI slot to click if no text match is found.", 13,
		0, 53, 1, ValueDisplay.INTEGER);
	
	// quick manual test
	private final CheckboxSetting testSellButton =
		new CheckboxSetting("Test sell now", false);
	
	private final ShallowWaterWarningCheckbox shallowWaterWarning =
		new ShallowWaterWarningCheckbox();
	
	private final FishingSpotManager fishingSpots = new FishingSpotManager();
	private final AutoFishDebugDraw debugDraw =
		new AutoFishDebugDraw(validRange, fishingSpots);
	private final AutoFishRodSelector rodSelector =
		new AutoFishRodSelector(this);
	
	private int castRodTimer;
	private int reelInTimer;
	private boolean biteDetected;
	
	private enum SellState
	{
		IDLE,
		SENT_CMD,
		AWAIT_SCREEN,
		CLICKED,
		CONFIRM,
		DONE
	}
	
	private SellState sellState = SellState.IDLE;
	private long sellTickUntil = 0; // small wait windows
	
	public AutoFishHack()
	{
		super("AutoFish");
		setCategory(Category.OTHER);
		addSetting(biteMode);
		addSetting(validRange);
		addSetting(catchDelay);
		addSetting(retryDelay);
		addSetting(patience);
		
		addSetting(autoSell);
		addSetting(sellCommand);
		addSetting(sellButtonText);
		addSetting(sellButtonSlot);
		addSetting(testSellButton);
		
		debugDraw.getSettings().forEach(this::addSetting);
		rodSelector.getSettings().forEach(this::addSetting);
		addSetting(shallowWaterWarning);
		fishingSpots.getSettings().forEach(this::addSetting);
	}
	
	@Override
	public String getRenderName()
	{
		if(rodSelector.isOutOfRods())
			return getName() + " [out of rods]";
		
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		castRodTimer = 0;
		reelInTimer = 0;
		biteDetected = false;
		rodSelector.reset();
		debugDraw.reset();
		fishingSpots.reset();
		shallowWaterWarning.reset();
		
		// WURST.getHax().antiAfkHack.setEnabled(false);
		WURST.getHax().aimAssistHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		// update timers
		if(castRodTimer > 0)
			castRodTimer--;
		if(reelInTimer > 0)
			reelInTimer--;
		
		// update inventory
		if(!rodSelector.update())
			return;
		
		// if not fishing, cast rod
		if(!isFishing())
		{
			if(castRodTimer > 0)
				return;
			
			reelInTimer = 20 * patience.getValueI();
			if(!fishingSpots.onCast())
				return;
			
			MC.doItemUse();
			castRodTimer = retryDelay.getValueI();
			return;
		}
		
		// if a bite was detected, check water type and reel in
		if(biteDetected)
		{
			shallowWaterWarning.checkWaterType();
			reelInTimer = catchDelay.getValueI();
			
			var hook = (MC.player == null) ? null : MC.player.fishHook;
			if(hook != null && !hook.isRemoved())
			{
				try
				{
					fishingSpots.onBite(hook);
				}catch(Throwable t)
				{
					// swallow to avoid client crash if a spot invalidates
					// mid-tick
					// (optional) WURST.getLogger().warn("AutoFish bite handling
					// failed", t);
				}
			}
			
			biteDetected = false;
			
			// also reel in if an entity was hooked
		}else if(MC.player.fishHook.getHookedEntity() != null)
			reelInTimer = catchDelay.getValueI();
		
		// otherwise, reel in when the timer runs out
		if(reelInTimer == 0)
		{
			MC.doItemUse();
			reelInTimer = retryDelay.getValueI();
			castRodTimer = retryDelay.getValueI();
		}
		
		// Manual test button in the GUI:
		if(testSellButton.isChecked())
		{
			testSellButton.setChecked(false);
			sellState = SellState.SENT_CMD;
			MC.getNetworkHandler().sendChatMessage(sellCommand.getValue());
			sellTickUntil = MC.world.getTime() + 10; // ~0.5s
		}
		
		// Auto-sell when full:
		if(autoSell.isChecked() && sellState == SellState.IDLE
			&& isInventoryFull())
		{
			sellState = SellState.SENT_CMD;
			MC.getNetworkHandler().sendChatMessage(sellCommand.getValue());
			sellTickUntil = MC.world.getTime() + 10; // wait a few ticks for GUI
		}
		
		// Selling state machine:
		switch(sellState)
		{
			case SENT_CMD:
			if(MC.world.getTime() >= sellTickUntil)
			{
				sellState = SellState.AWAIT_SCREEN;
				sellTickUntil = MC.world.getTime() + 40; // give up after ~2s
			}
			break;
			
			case AWAIT_SCREEN:
			if(MC.currentScreen instanceof HandledScreen<?>)
			{
				if(clickButtonByName("SELL ALL"))
				{
					sellState = SellState.CONFIRM;
					sellTickUntil = MC.world.getTime() + 20; // short wait for
																// next GUI
				}
			}else if(MC.world.getTime() >= sellTickUntil)
			{
				sellState = SellState.IDLE; // timeout
			}
			break;
			
			case CONFIRM:
			if(MC.currentScreen instanceof HandledScreen<?>)
			{
				if(clickButtonByName("CONFIRM SELL ALL"))
				{
					sellState = SellState.CLICKED;
					sellTickUntil = MC.world.getTime() + 8;
				}
			}else if(MC.world.getTime() >= sellTickUntil)
			{
				sellState = SellState.IDLE; // timeout
			}
			break;
			
			case CLICKED:
			// Close the screen and reset; Quick move/Pickup will have triggered
			// the sell
			if(MC.currentScreen instanceof HandledScreen<?>)
				MC.player.closeHandledScreen();
			sellState = SellState.DONE;
			sellTickUntil = MC.world.getTime() + 10;
			break;
			
			case DONE:
			if(MC.world.getTime() >= sellTickUntil)
				sellState = SellState.IDLE;
			break;
			
			case IDLE:
			default:
			break;
		}
		
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		switch(biteMode.getSelected())
		{
			case SOUND -> processSoundUpdate(event);
			case ENTITY -> processEntityUpdate(event);
		}
	}
	
	private boolean clickButtonByName(String targetName)
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return false;
		
		ScreenHandler handler = screen.getScreenHandler();
		
		for(int i = 0; i < handler.slots.size(); i++)
		{
			ItemStack stack = handler.getSlot(i).getStack();
			if(!stack.isEmpty())
			{
				String name = stack.getName().getString();
				if(name != null && name.equalsIgnoreCase(targetName))
				{
					MC.interactionManager.clickSlot(handler.syncId, i, 0,
						SlotActionType.PICKUP, MC.player);
					return true;
				}
			}
		}
		return false;
	}
	
	private boolean isInventoryFull()
	{
		return MC.player != null
			&& MC.player.getInventory().getEmptySlot() == -1;
	}
	
	private boolean clickConfiguredSellButton()
	{
		if(!(MC.currentScreen instanceof HandledScreen<?> screen))
			return false;
		
		ScreenHandler handler = screen.getScreenHandler();
		
		// Prefer text match if provided
		String want = sellButtonText.getValue().trim();
		if(!want.isEmpty())
		{
			for(int i = 0; i < handler.slots.size(); i++)
			{
				ItemStack stack = handler.getSlot(i).getStack();
				if(!stack.isEmpty())
				{
					// Be tolerant across mappings
					Text t = stack.getName(); // Yarn: ItemStack#getName()
					String name = (t == null) ? "" : t.getString();
					if(!name.isEmpty()
						&& name.toLowerCase().contains(want.toLowerCase()))
					{
						// button 0 = left click; PICKUP is fine for “button”
						// items
						MC.interactionManager.clickSlot(handler.syncId, i, 0,
							SlotActionType.PICKUP, MC.player);
						return true;
					}
				}
			}
		}
		
		// Fallback: click by configured slot index
		int idx = sellButtonSlot.getValueI();
		MC.interactionManager.clickSlot(handler.syncId, idx, 0,
			SlotActionType.PICKUP, MC.player);
		return true;
	}
	
	private void processSoundUpdate(PacketInputEvent event)
	{
		// check packet type
		if(!(event.getPacket() instanceof PlaySoundS2CPacket sound))
			return;
		
		// check sound type
		if(!SoundEvents.ENTITY_FISHING_BOBBER_SPLASH
			.equals(sound.getSound().value()))
			return;
		
		// check if player is fishing
		if(!isFishing())
			return;
		
		// register sound position
		debugDraw.updateSoundPos(sound);
		
		// check sound position (Chebyshev distance)
		Vec3d bobber = MC.player.fishHook.getPos();
		double dx = Math.abs(sound.getX() - bobber.getX());
		double dz = Math.abs(sound.getZ() - bobber.getZ());
		if(Math.max(dx, dz) > validRange.getValue())
			return;
		
		biteDetected = true;
	}
	
	private void processEntityUpdate(PacketInputEvent event)
	{
		// check packet type
		if(!(event.getPacket() instanceof EntityTrackerUpdateS2CPacket update))
			return;
		
		// check if the entity is a bobber
		if(!(MC.world
			.getEntityById(update.id()) instanceof FishingBobberEntity bobber))
			return;
		
		// check if it's our bobber
		if(bobber != MC.player.fishHook)
			return;
		
		// check if player is fishing
		if(!isFishing())
			return;
		
		biteDetected = true;
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		debugDraw.render(matrixStack, partialTicks);
	}
	
	private boolean isFishing()
	{
		ClientPlayerEntity player = MC.player;
		return player != null && player.fishHook != null
			&& !player.fishHook.isRemoved()
			&& player.getMainHandStack().isOf(Items.FISHING_ROD);
	}
	
	private enum BiteMode
	{
		SOUND("Sound"),
		ENTITY("Entity");
		
		private final String name;
		
		private BiteMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
