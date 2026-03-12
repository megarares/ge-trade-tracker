package com.getradetracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("getradetracker")
public interface GETradeTrackerConfig extends Config
{
	@ConfigItem(
		keyName = "apiToken",
		name = "API Token",
		description = "Bearer token for authentication",
		secret = true,
		position = 1
	)
	default String apiToken()
	{
		return "";
	}
}
