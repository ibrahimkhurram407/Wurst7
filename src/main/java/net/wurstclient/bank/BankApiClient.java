/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.bank;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal client for your Factions Bank API. */
public final class BankApiClient
{
	
	private static final OkHttpClient HTTP = new OkHttpClient();
	private static final MediaType JSON =
		MediaType.get("application/json; charset=utf-8");
	
	private final String baseUrl;
	private final String apiKey;
	private final Gson gson = new Gson();
	
	public BankApiClient(String baseUrl, String apiKey)
	{
		this.baseUrl = trimSlash(baseUrl);
		this.apiKey = apiKey;
	}
	
	private static String trimSlash(String s)
	{
		if(s == null)
			return "";
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
	
	private Request.Builder req(String path)
	{
		return new Request.Builder().url(baseUrl + path)
			.header("Content-Type", "application/json")
			.header("X-API-Key", apiKey);
	}
	
	// --- helpers for JSON GET/POST (safe, null on non-2xx) ---
	private JsonObject doGetJson(String path) throws IOException
	{
		Request rq = req(path).get().build();
		try(Response r = HTTP.newCall(rq).execute())
		{
			if(!r.isSuccessful() || r.body() == null)
				return null;
			String s = r.body().string();
			JsonElement el = JsonParser.parseString(s);
			return el != null && el.isJsonObject() ? el.getAsJsonObject()
				: null;
		}
	}
	
	private JsonObject doPostJson(String path, JsonObject body)
		throws IOException
	{
		if(body == null)
			body = new JsonObject();
		Request rq =
			req(path).post(RequestBody.create(body.toString(), JSON)).build();
		try(Response r = HTTP.newCall(rq).execute())
		{
			if(!r.isSuccessful() || r.body() == null)
				return null;
			String s = r.body().string();
			JsonElement el = JsonParser.parseString(s);
			return el != null && el.isJsonObject() ? el.getAsJsonObject()
				: null;
		}
	}
	
	// ---------- health ----------
	public boolean healthOk() throws IOException
	{
		Request rq =
			new Request.Builder().url(baseUrl + "/api/healthz").get().build();
		try(Response r = HTTP.newCall(rq).execute())
		{
			if(!r.isSuccessful())
				return false;
			String s = r.body().string();
			JsonObject j = JsonParser.parseString(s).getAsJsonObject();
			return j.has("ok") && j.get("ok").getAsBoolean();
		}
	}
	
	// ---------- endpoints (boolean/simple) ----------
	public boolean ensureAccount(String ign) throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", ign);
		try(Response r = HTTP
			.newCall(req("/api/ensure")
				.post(RequestBody.create(body.toString(), JSON)).build())
			.execute())
		{
			return r.isSuccessful();
		}
	}
	
	public boolean deposit(String ign, long amount, String note)
		throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", ign);
		body.addProperty("amount", amount);
		if(note != null)
			body.addProperty("note", note);
		try(Response r = HTTP
			.newCall(req("/api/deposit")
				.post(RequestBody.create(body.toString(), JSON)).build())
			.execute())
		{
			return r.isSuccessful();
		}
	}
	
	public boolean payout(String ign, long amount, long feePct, String note)
		throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", ign);
		body.addProperty("amount", amount);
		body.addProperty("fee", feePct);
		if(note != null)
			body.addProperty("note", note);
		try(Response r = HTTP
			.newCall(req("/api/payout")
				.post(RequestBody.create(body.toString(), JSON)).build())
			.execute())
		{
			return r.isSuccessful();
		}
	}
	
	public boolean withdrawAll(String ign, long feePct, String note)
		throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", ign);
		body.addProperty("fee", feePct);
		if(note != null)
			body.addProperty("note", note);
		try(Response r = HTTP
			.newCall(req("/api/withdrawall")
				.post(RequestBody.create(body.toString(), JSON)).build())
			.execute())
		{
			return r.isSuccessful();
		}
	}
	
	public JsonObject getPlayerDetail(String ign) throws IOException
	{
		try(Response r =
			HTTP.newCall(req("/api/players/" + ign).get().build()).execute())
		{
			if(!r.isSuccessful())
				return null;
			String s = r.body().string();
			JsonElement el = gson.fromJson(s, JsonElement.class);
			return el != null && el.isJsonObject() ? el.getAsJsonObject()
				: null;
		}
	}
	
	// ---------- endpoints (rich JSON receipts) ----------
	public JsonObject depositR(String player, long amount, String note)
		throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", player);
		body.addProperty("amount", amount);
		if(note != null)
			body.addProperty("note", note);
		Request rq = req("/api/deposit")
			.post(RequestBody.create(body.toString(), JSON)).build();
		try(Response r = HTTP.newCall(rq).execute())
		{
			if(!r.isSuccessful())
				return null;
			return JsonParser.parseString(r.body().string()).getAsJsonObject();
		}
	}
	
	public JsonObject payoutR(String player, long amount, long feePct,
		String note) throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", player);
		body.addProperty("amount", amount);
		body.addProperty("fee", feePct);
		if(note != null)
			body.addProperty("note", note);
		Request rq = req("/api/payout")
			.post(RequestBody.create(body.toString(), JSON)).build();
		try(Response r = HTTP.newCall(rq).execute())
		{
			if(!r.isSuccessful())
				return null;
			return JsonParser.parseString(r.body().string()).getAsJsonObject();
		}
	}
	
	public JsonObject withdrawAllR(String player, long feePct, String note)
		throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", player);
		body.addProperty("fee", feePct);
		body.addProperty("note", note == null ? "" : note);
		
		Request req = new Request.Builder().url(baseUrl + "/api/withdrawall")
			.header("X-API-Key", apiKey)
			.post(RequestBody.create(body.toString(), JSON)).build();
		
		try(Response res = HTTP.newCall(req).execute())
		{
			if(!res.isSuccessful())
				return null;
			String s = res.body().string();
			return JsonParser.parseString(s).getAsJsonObject();
		}
	}
	
	// GET /api/races/info
	public JsonObject racesInfo() throws IOException
	{
		return doGetJson("/api/races/info");
	}
	
	// POST /api/races/new { "name": "...", "starts_at": "2025-11-09T12:00:00Z"
	// }
	public JsonObject racesNew(String name, String startsAtIsoUtc)
		throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("name", name);
		body.addProperty("starts_at", startsAtIsoUtc);
		return doPostJson("/api/races/new", body);
	}
	
	// POST /api/races/enroll { "player_name": "IGN" }
	public JsonObject raceEnroll(String playerName) throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("player_name", playerName);
		return doPostJson("/api/races/enroll", body);
	}
	
	// POST /api/races/winner{1|2|3} { "player_name": "IGN" }
	public JsonObject raceWinner(int position, String playerName)
		throws IOException
	{
		int pos = Math.max(1, Math.min(3, position));
		String path = "/api/races/winner" + pos;
		JsonObject body = new JsonObject();
		body.addProperty("player_name", playerName);
		return doPostJson(path, body);
	}
	
	// POST /api/races/end {}
	public JsonObject raceEnd() throws IOException
	{
		return doPostJson("/api/races/end", new JsonObject());
	}
	
	public JsonObject getPlayerDetailR(String player) throws IOException
	{
		Request rq = new Request.Builder()
			.url(baseUrl + "/api/players/" + player).get().build();
		try(Response r = HTTP.newCall(rq).execute())
		{
			if(!r.isSuccessful())
				return null;
			return JsonParser.parseString(r.body().string()).getAsJsonObject();
		}
	}
}
