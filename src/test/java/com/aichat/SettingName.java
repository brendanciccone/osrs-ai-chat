package com.aichat;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;

/**
 * A setting's name as RuneLite's settings panel shows it, read from its {@link ConfigItem} in {@link AiChatConfig}: what
 * AI Chat's own words must call it, checked against what the player actually sees.
 */
final class SettingName
{
	private SettingName()
	{
	}

	/** The name shown for the setting saved as {@code keyName}: "Share items and gear" for "shareItems". */
	static String of(String keyName)
	{
		for (Method m : AiChatConfig.class.getMethods())
		{
			ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (item != null && item.keyName().equals(keyName))
			{
				return item.name();
			}
		}
		throw new AssertionError("AI Chat has no setting " + keyName);
	}
}
