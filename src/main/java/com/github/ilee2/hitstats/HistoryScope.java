package com.github.ilee2.hitstats;

/**
 * Which body of records a panel view is built from, narrowest first.
 *
 * <p>{@link #ALL_TIME} is everything in the character's history file. {@link #SESSION} is only
 * what has been recorded since that character logged in. {@link #CURRENT_FIGHT} is everything
 * since the first attack after the last kill: at a boss that is the fight in progress, and once
 * the boss dies it stays on screen until the next fight starts, so a kill can be read after the
 * fact. In multi-way combat it is "since the last thing died", which is the honest reading.
 *
 * <p>Only the all-time records are written to disk. The other two are held in memory and end
 * with the client.
 */
public enum HistoryScope
{
	CURRENT_FIGHT("Current fight"),
	SESSION("This session"),
	ALL_TIME("All time");

	private final String label;

	HistoryScope(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
