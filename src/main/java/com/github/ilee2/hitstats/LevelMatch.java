package com.github.ilee2.hitstats;

/** How closely a community comparison should match the player's own levels. */
public enum LevelMatch
{
	/** Every level. The broadest sample, and the least like for like. */
	ANY("Any level"),

	/**
	 * The same five-level bracket of the skill that drives the style's damage: Strength for melee,
	 * Ranged for ranged, Magic for magic. A bracket, not "within five of you": 95 to 99 is one
	 * bracket whether the player is 95 or 99.
	 */
	BRACKET("Same level bracket"),

	/** The same four levels exactly. The closest match, and the smallest sample. */
	EXACT("Exactly my levels");

	private final String label;

	LevelMatch(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
