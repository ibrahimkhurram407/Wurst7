/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.ai.OpenAIClient;

@SearchTags({"auto reply", "chatgpt", "gpt", "responder"})
public final class ChatGptResponderHack extends Hack
	implements ChatInputListener
{
	// =========================
	// Settings
	// =========================
	private final TextFieldSetting apiKey =
		new TextFieldSetting("OpenAI API Key", "");
	
	/*
	 * Prompt template.
	 *
	 * Placeholders:
	 * {message} - latest incoming message text
	 * {sender} - name of the other player (or "Unknown")
	 * {me} - your Minecraft name
	 * {player} - same as {me}, convenience alias
	 * {is_dm} - "true"/"false" depending on DM or public
	 * {history} - log of recent DMs/mentions & replies
	 */
	private final TextFieldSetting promptTemplate = new TextFieldSetting(
		"Prompt Template",
		"You are roleplaying as {player} inside a Minecraft server. "
			+ "You are peacefully fishing to earn money and you prefer not to chat. "
			+ "If anyone accuses you of autofishing, you must politely deny it because you are NOT autofishing. "
			+ "Keep replies short and not suspicious.\n\n"
			+ "You MUST always answer in this JSON format only:\n" + "{\n"
			+ "  \"respondToCurrentMessage\": true/false,\n"
			+ "  \"listenToNextMessageOfThisPlayer\": true/false,\n"
			+ "  \"messageToSend\": \"your short response here\"\n" + "}\n\n"
			+ "Rules:\n" + "- Never break JSON.\n"
			+ "- messageToSend must be a single short sentence.\n"
			+ "- Set respondToCurrentMessage to false if the message doesn’t require a reply.\n"
			+ "- Set listenToNextMessageOfThisPlayer to true if you expect a follow-up from this same player.\n\n"
			+ "Conversation history:\n" + "{history}\n\n"
			+ "Incoming message:\n" + "From: {sender}\n" + "To: {player}\n"
			+ "Message: \"{message}\"\n" + "DM: {is_dm}\n");
	
	private final CheckboxSetting onlyWhenMentioned = new CheckboxSetting(
		"Only reply when mentioned",
		"Reply only if your name is in the incoming chat line (public chat). "
			+ "DMs are always allowed when 'Respond to DMs' is ON.",
		true);
	
	private final CheckboxSetting respondToDMs = new CheckboxSetting(
		"Respond to DMs/whispers",
		"Reply even if your name isn't mentioned when it's a private message (DM).",
		true);
	
	private final CheckboxSetting ignoreOwnMessages =
		new CheckboxSetting("Ignore my own messages",
			"Prevents loops if the server echoes your chat. "
				+ "(DMs with pattern like [Sender -> me] will NOT be ignored.)",
			true);
	
	private final CheckboxSetting testModeEcho =
		new CheckboxSetting("Test mode (echo instead of API)",
			"Send a simple echo reply to verify event & sending path.", false);
	
	private final CheckboxSetting debugLog = new CheckboxSetting(
		"Debug log to console", "Log decisions and outcomes.", false);
	
	// Rate control
	private final SliderSetting cooldownMs = new SliderSetting("Cooldown (ms)",
		"Minimum time between replies to the same sender.", 3000, 0, 60000, 250,
		SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting sendDelayMs =
		new SliderSetting("Send delay (ms)",
			"Delay before sending a reply (adds human-like latency).", 750, 0,
			4000, 50, SliderSetting.ValueDisplay.DECIMAL);
	
	// =========================
	// Mod disabling settings
	// =========================
	private final CheckboxSetting disableMods = new CheckboxSetting(
		"Disable mods when messaging",
		"Temporarily disable selected hacks while composing/sending a reply.",
		true);
	
	private final CheckboxSetting disableSneak = new CheckboxSetting(
		"  – Sneak", "Disable Sneak while messaging.", true);
	
	private final CheckboxSetting disableTriggerBot = new CheckboxSetting(
		"  – TriggerBot", "Disable TriggerBot while messaging.", true);
	
	private final CheckboxSetting disableKillauraLegit = new CheckboxSetting(
		"  – Killaura Legit", "Disable KillauraLegit while messaging.", true);
	
	private final SliderSetting reenableDelayMs =
		new SliderSetting("Re-enable delay (ms)",
			"How long after sending to re-enable disabled hacks.", 500, 0, 5000,
			50, SliderSetting.ValueDisplay.DECIMAL);
	
	// last reply timestamps per sender (lowercased)
	private final Map<String, Long> lastReplyAt = new HashMap<>();
	
	// conversation history (DMs, mentions and listened follow-ups)
	private static final int MAX_HISTORY_LINES = 40;
	private final Deque<String> history = new ArrayDeque<>();
	
	// which players we should listen to for their *next* message
	private final Map<String, Boolean> listenNextFrom = new HashMap<>();
	
	// ref-counted disabler so overlaps are safe
	private final ModDisabler modDisabler = new ModDisabler();
	
	// regex helpers for JSON parsing
	private static final Pattern BOOL_PATTERN_TEMPLATE = Pattern
		.compile("\"%s\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
	private static final Pattern STRING_PATTERN_TEMPLATE =
		Pattern.compile("\"%s\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
	
	public ChatGptResponderHack()
	{
		super("ChatGptResponder");
		setCategory(Category.CHAT);
		addSetting(apiKey);
		addSetting(promptTemplate);
		addSetting(onlyWhenMentioned);
		addSetting(respondToDMs);
		addSetting(ignoreOwnMessages);
		addSetting(testModeEcho);
		addSetting(debugLog);
		addSetting(cooldownMs);
		addSetting(sendDelayMs);
		
		addSetting(disableMods);
		addSetting(disableSneak);
		addSetting(disableTriggerBot);
		addSetting(disableKillauraLegit);
		addSetting(reenableDelayMs);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(ChatInputListener.class, this);
		if(debugLog.isChecked())
			System.out.println("[ChatGptResponder] enabled");
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ChatInputListener.class, this);
		if(debugLog.isChecked())
			System.out.println("[ChatGptResponder] disabled");
	}
	
	// Parsed result
	private static class Parsed
	{
		final String sender; // null if unknown
		final String msg; // the actual message content (no names)
		final boolean isDm; // true if a DM/whisper
		
		Parsed(String s, String m, boolean d)
		{
			sender = s;
			msg = m;
			isDm = d;
		}
	}
	
	// Try to detect DMs & extract (sender, message).
	// Return null for system/announcement lines we want to ignore.
	private Parsed parse(String raw, String myName)
	{
		// [Sender -> me] message (DM)
		int b1 = raw.indexOf('[');
		int b2 = raw.indexOf(']');
		if(b1 >= 0 && b2 > b1)
		{
			String bracket = raw.substring(b1 + 1, b2).trim();
			String lower = bracket.toLowerCase();
			if(lower.contains("->"))
			{
				String[] parts = bracket.split("->", 2);
				if(parts.length == 2)
				{
					String left = parts[0].trim();
					String right = parts[1].trim();
					String remainder = raw.substring(b2 + 1).trim();
					if(right.equalsIgnoreCase("me")
						|| right.equalsIgnoreCase(myName))
					{
						String sender =
							left.replace("<", "").replace(">", "").trim();
						return new Parsed(sender, remainder, true);
					}
					if(left.equalsIgnoreCase("me")
						|| left.equalsIgnoreCase(myName))
					{
						return null; // our own outgoing DM echo
					}
				}
			}
		}
		
		// From <Name>: message (DM)
		if(raw.startsWith("From ") && raw.contains(":"))
		{
			int open = raw.indexOf('<');
			int close = raw.indexOf('>');
			if(open >= 0 && close > open)
			{
				String sender = raw.substring(open + 1, close);
				String msg =
					raw.substring(close + 1).replaceFirst("^:\\s*", "").trim();
				return new Parsed(sender, msg, true);
			}
			
			// From Name: message
			String rest = raw.substring("From ".length());
			int colon = rest.indexOf(':');
			if(colon > 0)
			{
				String sender = rest.substring(0, colon).trim();
				String msg = rest.substring(colon + 1).trim();
				return new Parsed(sender, msg, true);
			}
		}
		
		// Vanilla style: <Name> message
		if(raw.startsWith("<") && raw.indexOf('>') > 1)
		{
			int end = raw.indexOf('>');
			String sender = raw.substring(1, end);
			String msg = raw.substring(end + 1).trim();
			return new Parsed(sender, msg, false);
		}
		
		// Custom server format:
		// e.g. "imperials [IMMORTAL] ibrahimtest » @Reefy192 what u doing"
		int arrow = raw.indexOf('»');
		if(arrow > 0)
		{
			String left = raw.substring(0, arrow).trim();
			String msg = raw.substring(arrow + 1).trim();
			if(!left.isEmpty() && !msg.isEmpty())
			{
				String[] parts = left.split("\\s+");
				String sender = parts[parts.length - 1]; // last token is name
				return new Parsed(sender, msg, false);
			}
		}
		
		// Anything else is treated as system/announcement and ignored.
		return null;
	}
	
	// AI decision parsed from JSON
	private static final class AiDecision
	{
		boolean respondToCurrentMessage;
		boolean listenToNextMessageOfThisPlayer;
		String messageToSend;
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		final Text comp = event.getComponent();
		final String raw = comp.getString();
		final String me = MC.getSession().getUsername();
		
		if(debugLog.isChecked())
			System.out.println("[ChatGptResponder] Saw: " + raw);
		
		Parsed parsed = parse(raw, me);
		if(parsed == null)
		{
			if(debugLog.isChecked())
				System.out.println(
					"[ChatGptResponder] Ignored (system/announcement or own DM echo).");
			return;
		}
		
		// ignore our own public messages
		if(ignoreOwnMessages.isChecked() && !parsed.isDm)
		{
			if(parsed.sender != null && parsed.sender.equalsIgnoreCase(me))
				return;
			if(raw.contains("<" + me + ">") || raw.startsWith(me + ":"))
				return;
		}
		
		boolean mentioned = raw.toLowerCase().contains(me.toLowerCase());
		String senderName = parsed.sender == null ? "unknown" : parsed.sender;
		String senderKey = senderName.toLowerCase();
		
		// Did AI previously tell us to listen to this player's next message?
		boolean listenedFollowup = listenNextFrom.remove(senderKey) != null;
		
		// DM gating
		if(parsed.isDm && !respondToDMs.isChecked())
			return;
		
		// Public gating
		if(!parsed.isDm)
		{
			// if onlyWhenMentioned = true, still react when it's a follow-up
			if(onlyWhenMentioned.isChecked() && !mentioned && !listenedFollowup)
				return;
		}
		
		// --- record incoming message for history if DM or mention or followup
		// ---
		if(parsed.isDm || mentioned || listenedFollowup)
		{
			String tag;
			if(parsed.isDm)
				tag = "DM";
			else if(listenedFollowup)
				tag = "PublicFollow";
			else
				tag = "Public";
			
			addHistoryLine("[" + tag + "] "
				+ (parsed.sender == null ? "Unknown" : parsed.sender) + ": "
				+ parsed.msg);
		}
		
		// per-sender cooldown (skip cooldown if this is a follow-up)
		long now = System.currentTimeMillis();
		if(!listenedFollowup)
		{
			long last = lastReplyAt.getOrDefault(senderKey, 0L);
			if(now - last < (long)cooldownMs.getValue())
				return;
			lastReplyAt.put(senderKey, now);
		}
		
		final boolean replyPrivately = parsed.isDm && parsed.sender != null;
		final String targetName = parsed.sender;
		final String textForModel = parsed.msg;
		final String publicPrefix =
			(parsed.sender != null ? "@" + parsed.sender + " " : "");
		final long delay = (long)sendDelayMs.getValue();
		
		// ---- TEST MODE (no JSON logic, just echo) ----
		if(testModeEcho.isChecked())
		{
			String reply = sanitizeForChat(textForModel);
			if(reply.isEmpty())
				return;
			
			Thread.ofVirtual().start(() -> {
				try
				{
					if(disableMods.isChecked())
						modDisabler.acquireAndApply();
					if(delay > 0)
						Thread.sleep(delay + jitter());
					if(MC.getNetworkHandler() != null)
					{
						if(replyPrivately)
						{
							MC.getNetworkHandler().sendChatCommand(
								"msg " + targetName + " " + reply);
							if(debugLog.isChecked())
								System.out.println(
									"[ChatGptResponder] Echo via /msg to "
										+ targetName);
						}else
						{
							MC.getNetworkHandler()
								.sendChatMessage(publicPrefix + reply);
							if(debugLog.isChecked())
								System.out.println(
									"[ChatGptResponder] Echo (public).");
						}
					}
					
					addHistoryLine("[Me] " + reply);
				}catch(InterruptedException ignored)
				{}finally
				{
					if(disableMods.isChecked())
						modDisabler
							.releaseLater((long)reenableDelayMs.getValue());
				}
			});
			return;
		}
		
		// ---- REAL MODE (OpenAI with JSON decision) ----
		final String key =
			apiKey.getValue() == null ? "" : apiKey.getValue().trim();
		if(key.isEmpty())
		{
			if(debugLog.isChecked())
				System.out.println("[ChatGptResponder] No API key set.");
			return;
		}
		
		final String tpl = (promptTemplate.getValue() == null || promptTemplate
			.getValue().isBlank()) ? "Conversation so far:\n{history}\n\n"
				+ "Now reply concisely (max 2 short sentences) to this Minecraft chat from {sender}: {message}"
				: promptTemplate.getValue();
		
		final String historyBlock = buildHistoryBlock();
		
		final String prompt = tpl.replace("{message}", textForModel)
			.replace("{sender}",
				parsed.sender == null ? "Unknown" : parsed.sender)
			.replace("{me}", me).replace("{player}", me)
			.replace("{is_dm}", Boolean.toString(parsed.isDm))
			.replace("{history}", historyBlock);
		
		Thread.ofVirtual().name("ChatGptResponder")
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace())
			.start(() -> {
				try
				{
					if(disableMods.isChecked())
						modDisabler.acquireAndApply();
					
					String rawReply = OpenAIClient.completeChat(key, prompt);
					if(rawReply == null || rawReply.isBlank())
					{
						if(debugLog.isChecked())
							System.out.println(
								"[ChatGptResponder] API returned empty string.");
						return;
					}
					
					AiDecision decision = parseAiDecision(rawReply);
					if(decision == null)
					{
						if(debugLog.isChecked())
						{
							System.out.println(
								"[ChatGptResponder] Failed to parse JSON from model:");
							System.out.println(rawReply);
						}
						return;
					}
					
					// remember follow-up listener
					if(decision.listenToNextMessageOfThisPlayer
						&& parsed.sender != null)
					{
						listenNextFrom.put(senderKey, Boolean.TRUE);
					}
					
					if(!decision.respondToCurrentMessage)
					{
						if(debugLog.isChecked())
							System.out.println(
								"[ChatGptResponder] Model chose not to respond.");
						return;
					}
					
					String reply = sanitizeForChat(decision.messageToSend);
					if(reply == null || reply.isBlank())
					{
						if(debugLog.isChecked())
							System.out.println(
								"[ChatGptResponder] messageToSend empty after sanitize.");
						return;
					}
					
					try
					{
						if(delay > 0)
							Thread.sleep(delay + jitter());
					}catch(InterruptedException ignored)
					{}
					
					if(MC.getNetworkHandler() != null)
					{
						if(replyPrivately)
						{
							MC.getNetworkHandler().sendChatCommand(
								"msg " + targetName + " " + reply);
							if(debugLog.isChecked())
								System.out.println(
									"[ChatGptResponder] Sent via /msg to "
										+ targetName);
						}else
						{
							MC.getNetworkHandler()
								.sendChatMessage(publicPrefix + reply);
							if(debugLog.isChecked())
								System.out.println(
									"[ChatGptResponder] Sent (public).");
						}
					}
					
					addHistoryLine("[Me] " + reply);
				}catch(Exception e)
				{
					e.printStackTrace();
				}finally
				{
					if(disableMods.isChecked())
						modDisabler
							.releaseLater((long)reenableDelayMs.getValue());
				}
			});
	}
	
	// ============ Helpers ============
	
	// (kept in case you want to use it later, but not used in the logic now)
	@SuppressWarnings("unused")
	private boolean isRealPlayer(String name)
	{
		if(name == null || MC.getNetworkHandler() == null)
			return false;
		
		ClientPlayNetworkHandler nh = MC.getNetworkHandler();
		for(PlayerListEntry e : nh.getPlayerList())
		{
			if(e == null || e.getProfile() == null)
				continue;
			String n = e.getProfile().getName();
			if(n != null && n.equalsIgnoreCase(name))
				return true;
		}
		return false;
	}
	
	private void addHistoryLine(String line)
	{
		if(line == null || line.isEmpty())
			return;
		history.addLast(line);
		while(history.size() > MAX_HISTORY_LINES)
			history.removeFirst();
	}
	
	private String buildHistoryBlock()
	{
		if(history.isEmpty())
			return "";
		
		StringBuilder sb = new StringBuilder();
		for(String line : history)
			sb.append(line).append('\n');
		
		// trim a bit so prompt doesn't explode
		String result = sb.toString().trim();
		if(result.length() > 2000)
			result = result.substring(result.length() - 2000);
		return result;
	}
	
	private static long jitter()
	{
		return (long)((Math.random() - 0.5) * 300.0); // ±150ms
	}
	
	private static String sanitizeForChat(String s)
	{
		if(s == null)
			return "";
		s = s.replaceAll("§.", ""); // strip color codes
		s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
		s = s.replaceAll("\\p{C}", ""); // control chars
		s = s.trim();
		if(s.length() > 256)
			s = s.substring(0, 256);
		return s;
	}
	
	private AiDecision parseAiDecision(String raw)
	{
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		if(start < 0 || end <= start)
			return null;
		
		String json = raw.substring(start, end + 1);
		
		AiDecision d = new AiDecision();
		d.respondToCurrentMessage =
			extractBoolean(json, "respondToCurrentMessage", false);
		d.listenToNextMessageOfThisPlayer =
			extractBoolean(json, "listenToNextMessageOfThisPlayer", false);
		d.messageToSend = extractString(json, "messageToSend");
		return d;
	}
	
	private boolean extractBoolean(String json, String key, boolean def)
	{
		Pattern p =
			Pattern.compile(String.format(BOOL_PATTERN_TEMPLATE.pattern(), key),
				Pattern.CASE_INSENSITIVE);
		Matcher m = p.matcher(json);
		if(m.find())
			return Boolean.parseBoolean(m.group(1).toLowerCase());
		return def;
	}
	
	private String extractString(String json, String key)
	{
		Pattern p = Pattern.compile(
			String.format(STRING_PATTERN_TEMPLATE.pattern(), key),
			Pattern.DOTALL);
		Matcher m = p.matcher(json);
		if(!m.find())
			return "";
		String value = m.group(1);
		// very light unescape for common sequences
		value = value.replace("\\\"", "\"").replace("\\\\", "\\");
		return value;
	}
	
	/**
	 * Temporarily disables selected hacks while messaging, with reference
	 * counting so overlapping replies don't re-enable too early.
	 */
	private final class ModDisabler
	{
		private final AtomicInteger holds = new AtomicInteger(0);
		private Boolean priorSneak, priorTriggerBot, priorKillauraLegit;
		
		void acquireAndApply()
		{
			if(holds.getAndIncrement() == 0)
				apply();
		}
		
		void releaseLater(long ms)
		{
			Thread.ofVirtual().start(() -> {
				try
				{
					if(ms > 0)
						Thread.sleep(ms);
				}catch(InterruptedException ignored)
				{}
				release();
			});
		}
		
		private synchronized void apply()
		{
			var hax = WurstClient.INSTANCE.getHax();
			
			if(disableSneak.isChecked() && priorSneak == null)
			{
				priorSneak = hax.sneakHack.isEnabled();
				if(priorSneak)
					hax.sneakHack.setEnabled(false);
			}
			if(disableTriggerBot.isChecked() && priorTriggerBot == null)
			{
				priorTriggerBot = hax.triggerBotHack.isEnabled();
				if(priorTriggerBot)
					hax.triggerBotHack.setEnabled(false);
			}
			if(disableKillauraLegit.isChecked() && priorKillauraLegit == null)
			{
				priorKillauraLegit = hax.killauraLegitHack.isEnabled();
				if(priorKillauraLegit)
					hax.killauraLegitHack.setEnabled(false);
			}
		}
		
		private synchronized void release()
		{
			if(holds.decrementAndGet() > 0)
				return; // still in use by another reply
				
			var hax = WurstClient.INSTANCE.getHax();
			
			if(priorSneak != null)
			{
				if(priorSneak)
					hax.sneakHack.setEnabled(true);
				priorSneak = null;
			}
			if(priorTriggerBot != null)
			{
				if(priorTriggerBot)
					hax.triggerBotHack.setEnabled(true);
				priorTriggerBot = null;
			}
			if(priorKillauraLegit != null)
			{
				if(priorKillauraLegit)
					hax.killauraLegitHack.setEnabled(true);
				priorKillauraLegit = null;
			}
		}
	}
	
	public TextFieldSetting getPromptTemplateSetting()
	{
		return promptTemplate;
	}
}
