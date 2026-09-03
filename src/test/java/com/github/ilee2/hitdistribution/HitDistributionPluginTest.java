package com.github.ilee2.hitdistribution;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class HitDistributionPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(HitDistributionPlugin.class);
		RuneLite.main(args);
	}
}
