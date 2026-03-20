package com.getradetracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.*;

import javax.inject.Inject;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@PluginDescriptor(
	name = "GE Trade Tracker",
	description = "Sends Grand Exchange buy/sell offer data to the GE Trade Tracker API",
	tags = {"grand exchange", "trading", "ge", "tracker"}
)
public class GETradeTrackerPlugin extends Plugin
{
	private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
	private static final long LOGIN_COOLDOWN_MS = 3_000;
	private static final long DEBOUNCE_DELAY_MS = 200;
	private static final String LIVE_API_URL = "https://api.example.com/ge";
	private static final String SERVER_URL_OVERRIDE_PROPERTY = "geTradeTracker.serverUrl";

	@Inject
	private Client client;

	@Inject
	private GETradeTrackerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	private boolean loggedIn = false;
	private long loginTime = 0;

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> pendingSend = null;
	private String lastSentPayload = null;

	@Override
	protected void startUp()
	{
		log.info("GE Trade Tracker plugin started");
		loggedIn = false;
	}

	@Override
	protected void shutDown()
	{
		log.info("GE Trade Tracker plugin stopped");
		loggedIn = false;

		if (pendingSend != null && !pendingSend.isDone())
		{
			pendingSend.cancel(false);
		}
		scheduler.shutdown();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
			loginTime = System.currentTimeMillis();
			cancelPendingSend();
			pendingSend = scheduler.schedule(this::sendOffers, LOGIN_COOLDOWN_MS, TimeUnit.MILLISECONDS);
			log.debug("Logged in - GE tracking enabled (cooldown started)");
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			loggedIn = false;
			cancelPendingSend();
			log.debug("Logged out - GE tracking disabled");
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!loggedIn)
		{
			return;
		}

		if (config.apiToken().isEmpty())
		{
			return;
		}

		// Skip events during login cooldown (initial slot population)
		long now = System.currentTimeMillis();
		if (now - loginTime < LOGIN_COOLDOWN_MS)
		{
			log.debug("Ignoring GE event during login cooldown");
			return;
		}

		// Debounce rapid events
		cancelPendingSend();
		pendingSend = scheduler.schedule(this::sendOffers, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	private void sendOffers()
	{
		if (!loggedIn)
		{
			log.debug("Skipping snapshot send while logged out");
			return;
		}

		if (config.apiToken().isEmpty())
		{
			log.debug("Skipping snapshot send because API token is not configured");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (client == null || client.getGrandExchangeOffers() == null || localPlayer == null)
		{
			log.debug("Client not ready");
			return;
		}

		String playerName = localPlayer.getName();
		long accountHash = client.getAccountHash();

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		List<Map<String, Object>> offerList = new ArrayList<>();

		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer offer = offers[i];
			Map<String, Object> slotData = new LinkedHashMap<>();
			slotData.put("slot", i);

			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				slotData.put("state", "EMPTY");
			}
			else
			{
				slotData.put("state", offer.getState().toString());
				slotData.put("itemId", offer.getItemId());
				slotData.put("price", offer.getPrice());
				slotData.put("totalQuantity", offer.getTotalQuantity());
				slotData.put("quantitySold", offer.getQuantitySold());
			}

			offerList.add(slotData);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("playerName", playerName);
		payload.put("accountHash", accountHash);
		payload.put("timestamp", System.currentTimeMillis());
		payload.put("offers", offerList);

		String jsonPayload = gson.toJson(payload);
		String serverUrl = getServerUrl();

		// Skip if payload is identical to last sent
		if (jsonPayload.equals(lastSentPayload))
		{
			log.debug("Skipping duplicate payload");
			return;
		}
		lastSentPayload = jsonPayload;

		RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, jsonPayload);
		Request request = new Request.Builder()
			.url(serverUrl)
			.post(body)
			.addHeader("X-API-Key", config.apiToken())
			.addHeader("Content-Type", "application/json")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Failed to send GE data: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					int code = response.code();
					if (response.isSuccessful())
					{
						log.debug("GE data sent successfully for {} (status {})", playerName, code);
					}
					else
					{
						log.warn("Server returned error {}", code);
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private String getServerUrl()
	{
		String overrideUrl = System.getProperty(SERVER_URL_OVERRIDE_PROPERTY);
		if (overrideUrl != null && !overrideUrl.trim().isEmpty())
		{
			return overrideUrl.trim();
		}

		return LIVE_API_URL;
	}

	private void cancelPendingSend()
	{
		if (pendingSend != null && !pendingSend.isDone())
		{
			pendingSend.cancel(false);
		}
	}

	@Provides
	GETradeTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GETradeTrackerConfig.class);
	}
}
