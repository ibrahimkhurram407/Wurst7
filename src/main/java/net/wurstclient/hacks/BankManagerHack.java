/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.bank.BankApiClient;

@SearchTags({"bank", "economy", "withdraw", "deposit", "balance"})
public final class BankManagerHack extends Hack
	implements ChatInputListener, UpdateListener
{
	// ---- Required config ----
	private final TextFieldSetting apiBase =
		new TextFieldSetting("Bank API baseUrl", "http://localhost:8080");
	private final TextFieldSetting apiKey = new TextFieldSetting(
		"Bank API key (X-API-Key)", "d6892971-aada-4ab6-ac14-76cd4c77054b");
	
	// This client/bot IGN (used in /pay sender)
	private final TextFieldSetting myIgn =
		new TextFieldSetting("My IGN (bot account)", "imperial_bank");
	
	private final TextFieldSetting adminUsersCsv =
		new TextFieldSetting("Race admins (comma-separated IGNs)",
			"ibrahimtest,Ibrahim407,abdullah_678,hassanxsaeed_876");
	
	// ---- Feature toggles ----
	private final CheckboxSetting enableDeposits =
		new CheckboxSetting("Accept deposits (auto-ledger)", true);
	private final CheckboxSetting enableWithdrawals =
		new CheckboxSetting("Allow withdrawals (DM commands)", true);
	private final CheckboxSetting respondToDMs =
		new CheckboxSetting("Respond to DMs", true);
	private final CheckboxSetting checkOwnBalanceBeforePay =
		new CheckboxSetting("Run /balance before paying", true);
	private final SliderSetting defaultFeePct =
		new SliderSetting("Default fee % (payout/withdraw all)", "", 7, 0, 25,
			1, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting payCooldownSec =
		new SliderSetting("Server /pay cooldown (sec)", "", 5, 0, 30, 1,
			SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting perUserWithdrawCooldownSec =
		new SliderSetting("Per-user withdraw cooldown (sec)", "", 5, 0, 120, 1,
			SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting healthCheckEverySec =
		new SliderSetting("Health check interval (seconds)", "", 60, 10, 600, 5,
			SliderSetting.ValueDisplay.DECIMAL);
	
	private volatile boolean bankHealthy = false;
	private final AtomicLong nextHealthAt = new AtomicLong(0);
	
	// ---- Ads ----
	private final CheckboxSetting enableAds =
		new CheckboxSetting("Send advertising message", true);
	private final TextFieldSetting adMessage1 = new TextFieldSetting(
		"Ad text (public chat)",
		"§6[Bank]§r DM me “deposit”, “withdraw”, or “balance”. Earn interest, instant payouts!");
	private final TextFieldSetting adMessage2 = new TextFieldSetting("Ad #2",
		"Imperial is Bringing  horse races soon!, earn big prize pool!");
	private final SliderSetting adEverySec =
		new SliderSetting("Ad interval (seconds)", "", 180, 30, 1800, 10,
			SliderSetting.ValueDisplay.DECIMAL);
	
	// ---- Safety limits ----
	private final SliderSetting maxSingleWithdraw = new SliderSetting(
		"Max withdraw per txn", "Hard cap per user request.", 5_000_000, 10_000,
		1_000_000_000, 10_000, SliderSetting.ValueDisplay.DECIMAL);
	
	private final CheckboxSetting debug =
		new CheckboxSetting("Debug log", false);
	
	// state
	private final AtomicLong nextAdAt = new AtomicLong(0);
	private BankApiClient bank;
	private volatile Long ownCash = null; // in dollars
	private volatile long ownCashAtMs = 0L; // timestamp parsed
	private final Object balanceLock = new Object(); // notify/wait for parse
	
	// Queue/worker to serialize /pay calls
	private static final class PayJob
	{
		final String to;
		final long amount;
		
		PayJob(String t, long a)
		{
			to = t;
			amount = a;
		}
	}
	
	private final java.util.concurrent.LinkedBlockingQueue<PayJob> payQueue =
		new java.util.concurrent.LinkedBlockingQueue<>();
	private volatile boolean runPayWorker = false;
	private Thread payWorker;
	
	private final java.util.concurrent.ConcurrentHashMap<String, Long> lastWithdrawAt =
		new java.util.concurrent.ConcurrentHashMap<>();
	
	// how long a parsed balance is considered fresh
	private static final long BALANCE_MAX_AGE_MS = 10_000L; // 10s
	private int nextAdIndex = 0; // 0 -> ad1, 1 -> ad2
	
	public BankManagerHack()
	{
		super("BankManager");
		setCategory(Category.CHAT);
		
		addSetting(apiBase);
		addSetting(apiKey);
		addSetting(myIgn);
		addSetting(adminUsersCsv);
		
		addSetting(enableDeposits);
		addSetting(enableWithdrawals);
		addSetting(respondToDMs);
		addSetting(checkOwnBalanceBeforePay);
		addSetting(defaultFeePct);
		addSetting(healthCheckEverySec);
		
		addSetting(enableAds);
		addSetting(adMessage1);
		addSetting(adMessage2);
		addSetting(adEverySec);
		
		addSetting(maxSingleWithdraw);
		addSetting(debug);
		addSetting(payCooldownSec);
		addSetting(perUserWithdrawCooldownSec);
		
	}
	
	@Override
	protected void onEnable()
	{
		nextAdAt.set(
			System.currentTimeMillis() + (long)adEverySec.getValue() * 1000L);
		nextHealthAt.set(0);
		EVENTS.add(ChatInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		bank = new BankApiClient(apiBase.getValue().trim(),
			apiKey.getValue().trim());
		nextAdAt.set(
			System.currentTimeMillis() + (long)adEverySec.getValue() * 1000L);
		if(debug.isChecked())
			System.out.println("[BankManager] enabled");
		startPayWorker();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ChatInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		if(debug.isChecked())
			System.out.println("[BankManager] disabled");
		stopPayWorker();
	}
	
	// ----------------- Update: ads -----------------
	@Override
	public void onUpdate()
	{
		if(MC.getNetworkHandler() == null)
			return;
		
		long now = System.currentTimeMillis();
		
		// refresh health flag on schedule (non-blocking)
		if(now >= nextHealthAt.get())
		{
			nextHealthAt
				.set(now + (long)healthCheckEverySec.getValue() * 1000L);
			Thread.ofVirtual().start(() -> {
				try
				{
					bankHealthy = bank.healthOk();
				}catch(Exception ignored)
				{
					bankHealthy = false;
				}
				if(debug.isChecked())
					System.out.println("[BankManager] health=" + bankHealthy);
			});
		}
		
		if(!enableAds.isChecked())
			return;
		if(now < nextAdAt.get())
			return;
		
		String msg =
			(nextAdIndex == 0 ? adMessage1.getValue() : adMessage2.getValue());
		if(msg == null)
			msg = "";
		msg = msg.replaceAll("§.", "");
		
		if(bankHealthy && !msg.isBlank())
			MC.getNetworkHandler().sendChatMessage(msg);
		else if(!bankHealthy)
			MC.getNetworkHandler()
				.sendChatMessage("[Bank] temporarily offline. Try again soon.");
		
		nextAdIndex = 1 - nextAdIndex; // alternate
		nextAdAt.set(now + (long)adEverySec.getValue() * 1000L);
	}
	
	private void startPayWorker()
	{
		runPayWorker = true;
		payWorker = Thread.ofVirtual().start(() -> {
			long lastPayAt = 0L;
			final long padMs = 200L; // safety pad
			while(runPayWorker)
			{
				try
				{
					PayJob job = payQueue.take();
					long now = System.currentTimeMillis();
					long cool = (long)(payCooldownSec.getValue() * 1000L);
					long wait =
						(lastPayAt == 0 ? 0 : (cool - (now - lastPayAt)));
					if(wait > 0)
						Thread.sleep(wait + padMs);
					
					// >>> New: re-check wallet right before paying
					if(checkOwnBalanceBeforePay.isChecked())
					{
						if(!ensureWalletAtLeast(job.amount, 2500))
						{
							// Not enough funds right now. Requeue to try later
							// and give some breathing room.
							if(debug.isChecked())
								System.out.println(
									"[BankManager] Insufficient wallet for /pay "
										+ job.to + " " + job.amount
										+ ", requeuing");
							Thread.sleep(2000); // small backoff so we don't
												// spin
							payQueue.offer(job); // requeue at tail
							continue; // try next job / loop
						}
					}
					
					pay(job.to, job.amount);
					lastPayAt = System.currentTimeMillis();
					
				}catch(InterruptedException ie)
				{
					// exit gracefully when disabling
					Thread.currentThread().interrupt();
				}catch(Throwable t)
				{
					if(debug.isChecked())
						t.printStackTrace();
				}
			}
		});
	}
	
	private void stopPayWorker()
	{
		runPayWorker = false;
		if(payWorker != null)
			payWorker.interrupt();
		payWorker = null;
	}
	
	private void enqueuePay(String to, long amount)
	{
		payQueue.offer(new PayJob(to, amount));
	}
	
	// ----------------- Chat intake -----------------
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		final String raw = event.getComponent().getString();
		final String clean = stripColors(raw).replaceAll("\\p{Cf}", "") // strip
																		// zero-width/format
																		// chars
			.trim();
		
		if(debug.isChecked())
			System.out.println(
				"[BankManager] chat(raw): " + raw + " | clean: " + clean);
		
		// --- wallet balance parse (on any line) ---
		Long parsedBal = parseWalletBalance(clean);
		if(parsedBal != null)
		{
			ownCash = parsedBal;
			ownCashAtMs = System.currentTimeMillis();
			synchronized(balanceLock)
			{
				balanceLock.notifyAll();
			}
			if(debug.isChecked())
				System.out.println("[BankManager] wallet=" + ownCash);
		}
		
		// --- deposit detection (trusted server line) ---
		if(enableDeposits.isChecked() && isServerEconomyDeposit(clean))
		{
			var dep = parseServerDeposit(clean);
			if(dep != null)
			{
				Thread.ofVirtual()
					.start(() -> handleDeposit(dep.sender, dep.amount, raw));
			}
			return;
		}
		final String line = event.getComponent().getString();
		
		// 2) DMs from players (withdraw/balance/help). Don’t trust public chat.
		if(!respondToDMs.isChecked())
			return;
		var dm = parseInboundDM(clean, MC.getSession().getUsername());
		if(dm == null)
			return; // ignore non-DMs / own echoes
			
		String cmd = dm.msg.trim().toLowerCase(Locale.ROOT);
		// --- Race commands ---
		if(cmd.startsWith("race"))
		{
			Thread.ofVirtual().start(() -> handleRaceCommand(dm.sender, cmd));
			return;
		}
		if(cmd.startsWith("withdraw"))
		{
			if(!enableWithdrawals.isChecked())
			{
				replyDM(dm.sender,
					"Withdrawals are currently paused. Try later.");
				return;
			}
			long now = System.currentTimeMillis();
			long last = lastWithdrawAt.getOrDefault(dm.sender, 0L);
			long coolMs = (long)(perUserWithdrawCooldownSec.getValue() * 1000L);
			long left = coolMs - (now - last);
			if(left > 0)
			{
				replyDM(dm.sender, "Please wait " + Math.ceil(left / 1000.0)
					+ "s before withdrawing again.");
				return;
			}
			lastWithdrawAt.put(dm.sender, now);
			String arg = cmd.replaceFirst("withdraw", "").trim();
			Thread.ofVirtual().start(() -> handleWithdraw(dm.sender, arg));
			return;
		}
		if(cmd.equals("deposit"))
		{
			replyDM(dm.sender,
				"To deposit, use: /pay " + myIgn.getValue() + " <amount>");
			return;
		}
		if(cmd.equals("balance") || cmd.equals("bal"))
		{
			Thread.ofVirtual().start(() -> handleBalance(dm.sender));
			return;
		}
		if(cmd.equals("help") || cmd.equals("?"))
		{
			replyDM(dm.sender,
				"Commands: balance | withdraw <amount|all>. To deposit, /pay "
					+ myIgn.getValue() + " <amount>.");
			return;
		}
	}
	
	// ----------------- Horse Race commands -----------------
	private static final java.time.ZoneId Z_PK =
		java.time.ZoneId.of("Asia/Karachi");
	
	private void handleRaceCommand(String sender, String full)
	{
		try
		{
			String[] parts = full.split("\\s+", 3); // "race", sub, rest
			String sub = parts.length >= 2 ? parts[1] : "";
			String rest = parts.length >= 3 ? parts[2] : "";
			
			switch(sub)
			{
				case "enroll" -> raceEnroll(sender);
				case "info" -> raceInfo(sender);
				case "start" -> raceStart(sender, rest);
				case "winner1" -> raceWinner(sender, rest, 1);
				case "winner2" -> raceWinner(sender, rest, 2);
				case "winner3" -> raceWinner(sender, rest, 3);
				case "end" -> raceEnd(sender);
				default -> replyDM(sender,
					"Race commands: race enroll | race info | (admins) race start \"Name\" HH:mm dd-M-uuuu | race winner1/2/3 <player> | race end");
			}
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			replyDM(sender, "Race command error.");
		}
	}
	
	/** Player: enroll into the latest active race. Charges entry fee. */
	private void raceEnroll(String sender)
	{
		try
		{
			var res = bank.raceEnroll(sender); // POST /api/races/enroll
			if(res == null || !res.has("success")
				|| !res.get("success").getAsBoolean())
			{
				replyDM(sender, "Enroll failed: " + safeErr(res));
				return;
			}
			// Fetch balance to echo new balance
			var detail = bank.getPlayerDetail(sender);
			Long bal = null;
			if(detail != null)
			{
				if(detail.has("account")
					&& detail.get("account").isJsonObject())
				{
					var acc = detail.getAsJsonObject("account");
					if(acc.has("balance"))
						bal = Math.round(acc.get("balance").getAsDouble());
				}else if(detail.has("balance"))
				{
					bal = detail.get("balance").getAsLong();
				}
			}
			double prize = res.has("prize_pool")
				? res.get("prize_pool").getAsDouble() : -1;
			double fee =
				res.has("entry_fee") ? res.get("entry_fee").getAsDouble() : -1;
			int jockeys = res.has("jockey_count")
				? res.get("jockey_count").getAsInt() : -1; // if API returns it
			
			String msg = "Enrolled! Entry " + money(Math.round(fee))
				+ ", pot now $"
				+ NumberFormat.getNumberInstance(Locale.US)
					.format(Math.round(prize))
				+ (jockeys >= 0 ? (", participants " + jockeys) : "")
				+ (bal != null ? (". New bank bal " + money(bal) + ".") : ".");
			replyDM(sender, msg);
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			replyDM(sender, "Enroll error. Try again.");
		}
	}
	
	/** Player: quick info about latest race. */
	private void raceInfo(String sender)
	{
		try
		{
			var info = bank.racesInfo(); // GET /api/races/info
			if(info == null || info.has("error") || !info.has("race_id"))
			{
				replyDM(sender, "No race info available.");
				return;
			}
			
			double prize = info.has("prize_pool")
				? info.get("prize_pool").getAsDouble() : 0d;
			int count = info.has("jockey_count")
				? info.get("jockey_count").getAsInt() : 0;
			
			String startsAt =
				(info.has("starts_at") && !info.get("starts_at").isJsonNull())
					? info.get("starts_at").getAsString() : null;
			
			String when = "(unscheduled)";
			if(startsAt != null && !startsAt.isBlank())
			{
				java.time.ZonedDateTime zdt;
				
				// If it already has 'Z' or an offset like +05:00, parse as
				// OffsetDateTime.
				if(startsAt.endsWith("Z")
					|| startsAt.matches(".*[+-]\\d\\d:\\d\\d$"))
				{
					zdt = java.time.OffsetDateTime.parse(startsAt)
						.atZoneSameInstant(Z_PK);
				}else
				{
					// No offset in string → assume UTC (change to your server
					// TZ if needed)
					java.time.LocalDateTime ldt =
						java.time.LocalDateTime.parse(startsAt);
					zdt = ldt.atZone(java.time.ZoneOffset.UTC)
						.withZoneSameInstant(Z_PK);
				}
				
				when = zdt.format(java.time.format.DateTimeFormatter
					.ofPattern("EEE dd-MMM yyyy HH:mm 'PKT'"));
			}
			
			replyDM(sender, "Race: pot $"
				+ java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
					.format(Math.round(prize))
				+ ", starts " + when + ", participants " + count + ".");
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			replyDM(sender, "Race info error.");
		}
	}
	
	/** Admin: start "Name Here" HH:mm dd-M-uuuu in Asia/Karachi */
	private void raceStart(String sender, String rest)
	{
		if(!isAdmin(sender))
		{
			replyDM(sender, "Only admins can start races.");
			return;
		}
		// Expect: "name in quotes" + space + time + space + date (e.g., 17:00
		// 11-9-2025)
		try
		{
			String name = parseQuotedName(rest);
			if(name == null)
			{
				replyDM(sender,
					"Usage: race start \"Name Here\" HH:mm dd-M-uuuu");
				return;
			}
			
			String tail = rest
				.substring(rest.indexOf('"', rest.indexOf('"') + 1) + 1).trim();
			String[] tparts = tail.split("\\s+");
			if(tparts.length < 2)
			{
				replyDM(sender,
					"Usage: race start \"Name Here\" HH:mm dd-M-uuuu");
				return;
			}
			
			String hhmm = tparts[0]; // 17:00
			String dmy = tparts[1]; // 11-9-2025
			var time = java.time.LocalTime.parse(hhmm,
				java.time.format.DateTimeFormatter.ofPattern("H:mm"));
			var date = java.time.LocalDate.parse(dmy,
				java.time.format.DateTimeFormatter.ofPattern("d-M-uuuu"));
			
			var zoned = java.time.ZonedDateTime.of(date, time, Z_PK);
			String isoUtc = zoned.toInstant().toString(); // API wants UTC ISO
			
			var res = bank.racesNew(name, isoUtc); // POST /api/races/new
			if(res == null || !res.has("success")
				|| !res.get("success").getAsBoolean())
			{
				replyDM(sender, "Race start failed: " + safeErr(res));
				return;
			}
			replyDM(sender,
				"Race created: \"" + name + "\" at "
					+ zoned.format(java.time.format.DateTimeFormatter
						.ofPattern("EEE dd-MMM yyyy HH:mm 'PKT'"))
					+ ".");
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			replyDM(sender,
				"Start error. Format: race start \"Name\" HH:mm dd-M-uuuu");
		}
	}
	
	/** Admin: set winners */
	private void raceWinner(String sender, String rest, int n)
	{
		if(!isAdmin(sender))
		{
			replyDM(sender, "Only admins can set winners.");
			return;
		}
		String player = (rest == null ? "" : rest.trim());
		if(player.isEmpty())
		{
			replyDM(sender, "Usage: race winner" + n + " <playerName>");
			return;
		}
		try
		{
			var res = switch(n)
			{
				case 1 -> bank.raceWinner(1, player);
				case 2 -> bank.raceWinner(2, player);
				default -> bank.raceWinner(3, player);
			};
			if(res == null || !res.has("success")
				|| !res.get("success").getAsBoolean())
			{
				replyDM(sender, "Set winner" + n + " failed: " + safeErr(res));
				return;
			}
			double prize =
				res.has("prize") ? res.get("prize").getAsDouble() : -1;
			replyDM(sender, "Winner" + n + " set: " + player + " (+"
				+ money(Math.round(prize)) + ").");
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			replyDM(sender, "Winner" + n + " error.");
		}
	}
	
	/** Admin: end current race */
	private void raceEnd(String sender)
	{
		if(!isAdmin(sender))
		{
			replyDM(sender, "Only admins can end races.");
			return;
		}
		try
		{
			var res = bank.raceEnd();
			if(res == null || !res.has("success")
				|| !res.get("success").getAsBoolean())
			{
				replyDM(sender, "End race failed: " + safeErr(res));
				return;
			}
			replyDM(sender, "Race ended.");
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			replyDM(sender, "End race error.");
		}
	}
	
	private String parseQuotedName(String s)
	{
		if(s == null)
			return null;
		int q1 = s.indexOf('"');
		if(q1 < 0)
			return null;
		int q2 = s.indexOf('"', q1 + 1);
		if(q2 < 0)
			return null;
		return s.substring(q1 + 1, q2);
	}
	
	private String safeErr(com.google.gson.JsonObject res)
	{
		try
		{
			if(res == null)
				return "unknown";
			if(res.has("error"))
				return res.get("error").getAsString();
			if(res.has("message"))
				return res.get("message").getAsString();
			return "unknown";
		}catch(Exception e)
		{
			return "unknown";
		}
	}
	
	// ----------------- Handlers -----------------
	
	private void handleDeposit(String fromPlayer, long amount, String rawLine)
	{
		try
		{
			bank.ensureAccount(fromPlayer);
			var res = bank.depositR(fromPlayer, amount, "essx:" + rawLine);
			if(res == null || !res.has("ok") || !res.get("ok").getAsBoolean())
			{
				if(debug.isChecked())
					System.out
						.println("[BankManager] deposit failed JSON: " + res);
				return;
			}
			
			long txnId =
				res.has("txn_id") ? res.get("txn_id").getAsLong() : -1L;
			var rc = res.getAsJsonObject("receipt");
			double before = rc.get("before_balance").getAsDouble();
			double after = rc.get("balance_after").getAsDouble();
			double delta = rc.get("delta").getAsDouble();
			
			replyDM(fromPlayer,
				"Deposit ok #" + txnId + ": +" + money(Math.round(delta))
					+ " (bal " + money(Math.round(before)) + " → "
					+ money(Math.round(after)) + ").");
			
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
		}
	}
	
	private void handleBalance(String sender)
	{
		try
		{
			var detail = bank.getPlayerDetail(sender);
			if(detail == null)
			{
				replyDM(sender, "Could not fetch your balance. Try again.");
				return;
			}
			
			Long bal = null;
			
			// Preferred: { account: { balance: number } }
			if(detail.has("account") && detail.get("account").isJsonObject())
			{
				var acc = detail.getAsJsonObject("account");
				if(acc.has("balance"))
					bal = Math.round(acc.get("balance").getAsDouble());
			}
			
			// Fallbacks (older responses)
			if(bal == null && detail.has("balance"))
				bal = detail.get("balance").getAsLong();
			if(bal == null && detail.has("data")
				&& detail.getAsJsonObject("data").has("balance"))
				bal = detail.getAsJsonObject("data").get("balance").getAsLong();
			
			if(bal == null)
			{
				replyDM(sender, "Could not fetch your balance. Try again.");
				return;
			}
			
			replyDM(sender, "Your bank balance is " + money(bal) + ".");
		}catch(Exception e)
		{
			replyDM(sender, "Error fetching balance.");
			if(debug.isChecked())
				e.printStackTrace();
		}
	}
	
	private void handleWithdraw(String sender, String amountArg)
	{
		try
		{
			long feePct = (long)defaultFeePct.getValue();
			
			// --- ALL branch first ---
			if(amountArg != null && amountArg.equalsIgnoreCase("all"))
			{
				// Basic wallet check
				if(checkOwnBalanceBeforePay.isChecked())
				{
					Long wallet = ensureFreshOwnBalance(2000);
					if(wallet == null || wallet <= 0)
					{
						replyDM(sender,
							"Could not verify my wallet. Try again in a moment.");
						return;
					}
				}
				
				var res = bank.withdrawAllR(sender,
					(long)defaultFeePct.getValue(), "user request: all");
				
				if(res == null || !res.has("ok")
					|| !res.get("ok").getAsBoolean())
				{
					replyDM(sender, "Withdraw-all failed.");
					return;
				}
				
				long txnId =
					res.has("txn_id") ? res.get("txn_id").getAsLong() : -1L;
				var rc = res.getAsJsonObject("receipt");
				long paid = Math.round(rc.get("paid_to_player").getAsDouble());
				long fee = Math.round(rc.get("fee_charged").getAsDouble());
				long before =
					Math.round(rc.get("before_balance").getAsDouble());
				long after = Math.round(rc.get("balance_after").getAsDouble());
				
				enqueuePay(sender, paid);
				
				replyDM(sender,
					"Withdraw-all #" + txnId + ": paid " + money(paid)
						+ ", fee " + money(fee) + ". (bal " + money(before)
						+ " → " + money(after) + ").");
				
				return;
			}
			
			// --- Numeric amount path ---
			Long amt = parseAmount(amountArg);
			if(amt == null || amt <= 0)
			{
				replyDM(sender,
					"Usage: withdraw <amount|all>. Example: withdraw 250k");
				return;
			}
			if(amt > (long)maxSingleWithdraw.getValue())
			{
				replyDM(sender, "Max per withdrawal is "
					+ money((long)maxSingleWithdraw.getValue()) + ".");
				return;
			}
			
			if(checkOwnBalanceBeforePay.isChecked())
			{
				Long wallet = ensureFreshOwnBalance(2000);
				if(wallet == null)
				{
					replyDM(sender,
						"Could not verify funds. Please try again in a moment.");
					return;
				}
				if(wallet < amt)
				{
					replyDM(sender, "Insufficient funds in my wallet: have "
						+ money(wallet) + ", need " + money(amt) + ".");
					return;
				}
			}
			
			if(checkOwnBalanceBeforePay.isChecked())
			{
				if(!ensureWalletAtLeast(
					amt /* or paid from previous calc if known */, 2500))
				{
					replyDM(sender,
						"Hold on—insufficient funds in my wallet right now. Try again in ~10s.");
					return;
				}
			}
			
			var res = bank.payoutR(sender, amt, feePct, "user request");
			if(res == null || !res.has("ok") || !res.get("ok").getAsBoolean())
			{
				replyDM(sender,
					"Withdrawal failed. Maybe insufficient bank balance.");
				return;
			}
			
			long txnId =
				res.has("txn_id") ? res.get("txn_id").getAsLong() : -1L;
			var rc = res.getAsJsonObject("receipt");
			long paid = Math.round(rc.get("paid_to_player").getAsDouble());
			long fee = Math.round(rc.get("fee_charged").getAsDouble());
			long before = Math.round(rc.get("before_balance").getAsDouble());
			long after = Math.round(rc.get("balance_after").getAsDouble());
			
			enqueuePay(sender, paid);
			replyDM(sender,
				"Payout #" + txnId + ": paid " + money(paid) + ", fee "
					+ money(fee) + ". (bal " + money(before) + " → "
					+ money(after) + ").");
			
		}catch(Exception e)
		{
			replyDM(sender, "Error processing withdrawal.");
			if(debug.isChecked())
				e.printStackTrace();
		}
	}
	
	// ----------------- Chat helpers -----------------
	private boolean ensureWalletAtLeast(long needed, long timeoutMs)
	{
		Long w = ensureFreshOwnBalance(timeoutMs);
		if(w == null)
		{
			// try one more forced refresh
			requestOwnBalance();
			w = ensureFreshOwnBalance(timeoutMs);
		}
		return w != null && w >= needed;
	}
	
	private Long parseWalletBalance(String s)
	{
		java.util.regex.Pattern[] ps = new java.util.regex.Pattern[]{
			// ECONOMY » Balance $630,004,500 (anywhere in line)
			java.util.regex.Pattern.compile(
				"(?i)\\bBalance\\b\\s*[:>\\-]?\\s*\\$?\\s*([\\d,]+)\\b"),
			// Your balance is $5,000
			java.util.regex.Pattern.compile(
				"(?i)\\bYour\\s+balance\\s+(?:is\\s+)?\\$?\\s*([\\d,]+)\\b"),
			// $5,000 balance
			java.util.regex.Pattern
				.compile("(?i)\\$\\s*([\\d,]+)\\s+balance\\b")};
		for(var p : ps)
		{
			var m = p.matcher(s);
			if(m.find())
			{
				try
				{
					return Long.parseLong(m.group(1).replace(",", ""));
				}catch(Exception ignored)
				{}
			}
		}
		return null;
	}
	
	private static String stripColors(String s)
	{
		if(s == null)
			return "";
		// color codes
		s = s.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
		s = s.replaceAll("&[0-9A-FK-ORa-fk-or]", "");
		// normalize weird spaces & arrows
		s = s.replace('\u00A0', ' ') // NBSP -> space
			.replace('\u2007', ' ').replace('\u202F', ' ').replace('➟', ' ') // arrow
																				// used
																				// by
																				// many
																				// servers
			.replace('»', ' ');
		// collapse
		return s.replaceAll("\\s+", " ").trim();
	}
	
	/** Ensure we have a fresh wallet balance; returns null on failure. */
	private Long ensureFreshOwnBalance(long timeoutMs)
	{
		long now = System.currentTimeMillis();
		if(ownCash != null && now - ownCashAtMs <= BALANCE_MAX_AGE_MS)
			return ownCash; // still fresh
			
		requestOwnBalance(); // ask server
		long end = now + Math.max(250, timeoutMs);
		
		synchronized(balanceLock)
		{
			while(System.currentTimeMillis() < end)
			{
				try
				{
					balanceLock.wait(100L);
				}catch(InterruptedException ignored)
				{}
				if(ownCash != null && System.currentTimeMillis()
					- ownCashAtMs <= BALANCE_MAX_AGE_MS)
					return ownCash;
			}
		}
		return null; // didn’t get it in time
	}
	
	private void pay(String to, long amount)
	{
		if(MC.getNetworkHandler() == null)
			return;
		MC.getNetworkHandler().sendChatCommand("pay " + to + " " + amount);
	}
	
	private void requestOwnBalance()
	{
		if(MC.getNetworkHandler() == null)
			return;
		MC.getNetworkHandler().sendChatCommand("balance");
	}
	
	private void replyDM(String to, String msg)
	{
		if(MC.getNetworkHandler() == null)
			return;
		MC.getNetworkHandler().sendChatCommand("msg " + to + " " + msg);
	}
	
	private static String money(long v)
	{
		var nf = NumberFormat.getNumberInstance(Locale.US);
		return "$" + nf.format(v);
	}
	
	// ----------------- Parsing -----------------
	
	private static class DM
	{
		final String sender;
		final String msg;
		
		DM(String s, String m)
		{
			sender = s;
			msg = m;
		}
	}
	
	private DM parseInboundDM(String raw, String myName)
	{
		// [Sender -> me] message
		int b1 = raw.indexOf('[');
		int b2 = raw.indexOf(']');
		if(b1 >= 0 && b2 > b1)
		{
			String bracket = raw.substring(b1 + 1, b2).trim(); // e.g. "Vijay ->
																// me"
			if(bracket.contains("->"))
			{
				String[] p = bracket.split("->", 2);
				String left = p[0].trim();
				String right = p[1].trim();
				if(right.equalsIgnoreCase("me")
					|| right.equalsIgnoreCase(myName))
				{
					String msg = raw.substring(b2 + 1).trim();
					// Ignore our own outgoing DMs: "[me -> Target]"
					if(left.equalsIgnoreCase("me")
						|| left.equalsIgnoreCase(myName))
						return null;
					return new DM(left, msg);
				}
			}
		}
		// From <Name>: message
		if(raw.startsWith("From ") && raw.contains(":"))
		{
			int open = raw.indexOf('<'), close = raw.indexOf('>');
			if(open >= 0 && close > open)
			{
				String sender = raw.substring(open + 1, close);
				String msg =
					raw.substring(close + 1).replaceFirst("^:\\s*", "").trim();
				return new DM(sender, msg);
			}
			String rest = raw.substring("From ".length());
			int colon = rest.indexOf(':');
			if(colon > 0)
			{
				return new DM(rest.substring(0, colon).trim(),
					rest.substring(colon + 1).trim());
			}
		}
		return null;
	}
	
	private static class DepositLine
	{
		final String sender;
		final long amount;
		
		DepositLine(String s, long a)
		{
			sender = s;
			amount = a;
		}
	}
	
	private boolean isAdmin(String ign)
	{
		if(ign == null)
			return false;
		String csv = adminUsersCsv.getValue();
		if(csv == null || csv.isBlank())
			return false;
		String me = ign.trim().toLowerCase(Locale.ROOT);
		for(String s : csv.split(","))
		{
			if(me.equals(s.trim().toLowerCase(Locale.ROOT)))
				return true;
		}
		return false;
	}
	
	private boolean isServerEconomyDeposit(String clean)
	{
		// e.g. "ECONOMY » +$500 has been received from Rayyanxd99."
		
		return clean.matches(
			"(?i).*\\$?\\+?\\s*[\\d,]+\\s+has\\s+been\\s+received\\s+from\\s+[.]?[A-Za-z0-9_]{1,31}\\.?\\s*");
	}
	
	private DepositLine parseServerDeposit(String clean)
	{
		try
		{
			var p = java.util.regex.Pattern.compile(
				"(?i)\\$?\\+?\\s*([\\d,]+)\\s+has\\s+been\\s+received\\s+from\\s+([.]?[A-Za-z0-9_]{1,31})\\.?");
			var m = p.matcher(clean);
			if(!m.find())
				return null;
			long amount = Long.parseLong(m.group(1).replace(",", ""));
			String sender = m.group(2);
			return new DepositLine(sender, amount);
		}catch(Exception e)
		{
			if(debug.isChecked())
				e.printStackTrace();
			return null;
		}
	}
	
	private Long parseAmount(String s)
	{
		if(s == null || s.isBlank())
			return null;
		s = s.replace(",", "").trim().toLowerCase(Locale.ROOT);
		try
		{
			if(s.endsWith("k"))
				return Math
					.round(Double.parseDouble(s.substring(0, s.length() - 1))
						* 1_000D);
			if(s.endsWith("m"))
				return Math
					.round(Double.parseDouble(s.substring(0, s.length() - 1))
						* 1_000_000D);
			return Long.parseLong(s);
		}catch(Exception e)
		{
			return null;
		}
	}
}
