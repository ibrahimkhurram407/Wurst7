/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

public final class StatusHudHack extends Hack implements GUIRenderListener
{
	private static final MinecraftClient MC = WurstClient.MC;
	private static final WurstClient WURST = WurstClient.INSTANCE;
	
	private final SliderSetting x = new SliderSetting("X",
		"Horizontal HUD position.", 6, 0, 2000, 1, ValueDisplay.INTEGER);
	private final SliderSetting y = new SliderSetting("Y",
		"Vertical HUD position.", 6, 0, 2000, 1, ValueDisplay.INTEGER);
	
	// New: compact layout controls
	private final SliderSetting tileSize = new SliderSetting("Tile size",
		"Square tile side length (px).", 22, 16, 32, 1, ValueDisplay.INTEGER);
	private final SliderSetting gap = new SliderSetting("Gap",
		"Space between tiles (px).", 4, 0, 16, 1, ValueDisplay.INTEGER);
	private final SliderSetting columns = new SliderSetting("Columns",
		"How many tiles per row.", 7, 1, 12, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting showLabels =
		new CheckboxSetting("Show labels (tiny)",
			"Optional 2-letter labels; still compact.", false);
	
	public StatusHudHack()
	{
		super("StatusHUD");
		setCategory(Category.RENDER);
		addSetting(x);
		addSetting(y);
		addSetting(tileSize);
		addSetting(gap);
		addSetting(columns);
		addSetting(showLabels);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
	}
	
	@Override
	public void onRenderGUI(DrawContext ctx, float partialTicks)
	{
		if(MC.player == null || MC.options == null || MC.options.hudHidden)
			return;
		
		int baseX = x.getValueI();
		int baseY = y.getValueI();
		int size = tileSize.getValueI(); // square
		int pad = Math.max(1, Math.min(3, size / 10)); // inner padding
		int g = gap.getValueI();
		int cols = Math.max(1, columns.getValueI());
		
		// Gather statuses (defensive)
		boolean maceOn = false, preferElytra = false, preferFireworks = false,
			eatOn = false;
		boolean anchorOn = false, crystalOn = false, totemOn = false;
		
		try
		{
			AutoMaceHack am = WURST.getHax().autoMaceHack;
			if(am != null)
			{
				maceOn = am.isEnabled();
				preferElytra = am.isPreferElytraAir();
				preferFireworks = am.isPreferFireworks();
			}
		}catch(Throwable ignored)
		{}
		
		try
		{
			var ae = WURST.getHax().autoEatHack;
			if(ae != null)
				eatOn = ae.isEnabled();
		}catch(Throwable ignored)
		{}
		
		try
		{
			var aa = WURST.getHax().anchorAuraHack; // Anchor Aura
			if(aa != null)
				anchorOn = aa.isEnabled();
		}catch(Throwable ignored)
		{}
		
		try
		{
			var ca = WURST.getHax().crystalAuraHack; // Crystal Aura
			if(ca != null)
				crystalOn = ca.isEnabled();
		}catch(Throwable ignored)
		{}
		
		try
		{
			var at = WURST.getHax().autoTotemHack; // AutoTotem
			if(at != null)
				totemOn = at.isEnabled();
		}catch(Throwable ignored)
		{}
		
		// Build the tiles (icon + status)
		Tile[] tiles = new Tile[]{
			new Tile(new ItemStack(Items.MACE), maceOn, "MC"),
			new Tile(new ItemStack(Items.ELYTRA), preferElytra, "EL"),
			new Tile(new ItemStack(Items.FIREWORK_ROCKET), preferFireworks,
				"FW"),
			new Tile(new ItemStack(Items.COOKED_BEEF), eatOn, "ET"),
			new Tile(new ItemStack(Items.RESPAWN_ANCHOR), anchorOn, "AN"),
			new Tile(new ItemStack(Items.END_CRYSTAL), crystalOn, "CR"),
			new Tile(new ItemStack(Items.TOTEM_OF_UNDYING), totemOn, "TT")};
		
		// Render grid
		int xIdx = 0;
		int yIdx = 0;
		for(int i = 0; i < tiles.length; i++)
		{
			int drawX = baseX + xIdx * (size + g);
			int drawY = baseY + yIdx * (size + g);
			drawTile(ctx, drawX, drawY, size, pad, tiles[i]);
			xIdx++;
			if(xIdx >= cols)
			{
				xIdx = 0;
				yIdx++;
			}
		}
	}
	
	private static final class Tile
	{
		final ItemStack icon;
		final boolean on;
		final String label2; // tiny 2-letter label
		
		Tile(ItemStack icon, boolean on, String label2)
		{
			this.icon = icon;
			this.on = on;
			this.label2 = label2;
		}
	}
	
	private void drawTile(DrawContext ctx, int x, int y, int size, int pad,
		Tile t)
	{
		// Colors: subtle inner fill + status border
		int border = t.on ? 0xA000A040 /* green-ish */ : 0xA0404040 /* gray */;
		int fill = t.on ? 0x6000A040 : 0x50000000;
		
		// Outer border
		ctx.fill(x, y, x + size, y + size, border);
		// Inner area
		ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, fill);
		
		// Draw 16x16 item centered in the square
		int iconX = x + (size - 16) / 2;
		int iconY = y + (size - 16) / 2;
		ctx.drawItem(t.icon, iconX, iconY);
		
		// Optional teeny label in corner
		if(showLabels.isChecked() && MC.textRenderer != null)
		{
			int col = t.on ? 0xFFFFFF : 0xB0B0B0;
			String s = t.label2;
			int w = MC.textRenderer.getWidth(s);
			// Bottom-right corner, 1px inset
			ctx.drawTextWithShadow(MC.textRenderer, s, x + size - w - 2,
				y + size - 9, col);
		}
	}
}
