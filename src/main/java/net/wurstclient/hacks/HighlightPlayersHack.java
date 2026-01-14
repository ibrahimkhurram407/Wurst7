/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.EspStyleSetting.EspStyle;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.FilterInvisibleSetting;
import net.wurstclient.settings.filters.FilterSleepingSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"highlight players", "ally highlight", "enemy highlight"})
public final class HighlightPlayersHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	// Visual style (same UX as PlayerESP)
	private final EspStyleSetting style =
		new EspStyleSetting(EspStyle.LINES_AND_BOXES);
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r shows exact hitboxes.\n\u00a7lFancy\u00a7r shows slightly larger boxes.");
	
	// Target list (only these names will be highlighted)
	private final TextFieldSetting targetNames = new TextFieldSetting(
		"Target players",
		"Comma-separated usernames to highlight (case-insensitive). Example: ibrahimtest, cyrusblood",
		"");
	
	// Color (RGBA 0–255) – strong default: bright purple, more visible
	private final SliderSetting colR = new SliderSetting("Red", 200, 0, 255, 1,
		SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting colG = new SliderSetting("Green", 80, 0, 255, 1,
		SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting colB = new SliderSetting("Blue", 255, 0, 255, 1,
		SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting colA = new SliderSetting("Alpha", 200, 0, 255,
		1, SliderSetting.ValueDisplay.INTEGER);
	
	// Optional filters (keep parity with PlayerESP—handy for invis/sleeping)
	private final EntityFilterList entityFilters = new EntityFilterList(
		new FilterSleepingSetting("Won't show sleeping players.", false),
		new FilterInvisibleSetting("Won't show invisible players.", false));
	
	private final ArrayList<PlayerEntity> targets = new ArrayList<>();
	
	public HighlightPlayersHack()
	{
		super("HighlightPlayers");
		setCategory(Category.RENDER);
		
		addSetting(style);
		addSetting(boxSize);
		addSetting(targetNames);
		addSetting(colR);
		addSetting(colG);
		addSetting(colB);
		addSetting(colA);
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		targets.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.world == null || MC.player == null)
		{
			targets.clear();
			return;
		}
		
		// Parse target list once per tick
		final Set<String> wanted =
			Arrays.stream(targetNames.getValue().split(",")).map(String::trim)
				.filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
		
		targets.clear();
		if(wanted.isEmpty())
			return; // nothing to show
			
		Stream<AbstractClientPlayerEntity> stream = MC.world.getPlayers()
			.parallelStream().filter(e -> !e.isRemoved() && e.getHealth() > 0)
			.filter(e -> e != MC.player)
			.filter(e -> !(e instanceof FakePlayerEntity))
			.filter(e -> Math.abs(e.getY() - MC.player.getY()) <= 1e6)
			.filter(e -> {
				String n = e.getName() != null ? e.getName().getString() : null;
				return n != null && wanted.contains(n.toLowerCase(Locale.ROOT));
			});
		
		stream = entityFilters.applyTo(stream);
		targets.addAll(stream.collect(Collectors.toList()));
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(targets.isEmpty())
			return;
		
		int color =
			RenderUtils
				.toIntColor(
					new float[]{colR.getValueI() / 255f,
						colG.getValueI() / 255f, colB.getValueI() / 255f},
					colA.getValueI() / 255f);
		
		if(style.hasBoxes())
		{
			double extraSize = boxSize.getExtraSize() / 2;
			ArrayList<ColoredBox> boxes = new ArrayList<>(targets.size());
			for(PlayerEntity e : targets)
			{
				Box box = EntityUtils.getLerpedBox(e, partialTicks)
					.offset(0, extraSize, 0).expand(extraSize);
				boxes.add(new ColoredBox(box, color));
			}
			// Outline only for clarity; change `false` to `true` to fill
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, false);
		}
		
		if(style.hasLines())
		{
			ArrayList<ColoredPoint> ends = new ArrayList<>(targets.size());
			for(PlayerEntity e : targets)
			{
				Vec3d point =
					EntityUtils.getLerpedBox(e, partialTicks).getCenter();
				ends.add(new ColoredPoint(point, color));
			}
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
		}
	}
}
