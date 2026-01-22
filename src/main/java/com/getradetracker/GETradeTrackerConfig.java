package com.getradetracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("getradetracker")
public interface GETradeTrackerConfig extends Config
{
	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "The URL to send GE trade data to (e.g., https://api.yourserver.com/ge)",
		position = 1
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiToken",
		name = "API Token",
		description = "Bearer token for authentication",
		secret = true,
		position = 2
	)
	default String apiToken()
	{
		return "";
	}
}
