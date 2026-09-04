package com.github.ilee2.hitstats.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The damage distribution, one horizontal bar per hitsplat amount, with the amount labelled on the
 * left and its count at the end of the bar.
 *
 * <p>Horizontal because vertical bars in a sidebar leave a few pixels per column, which is not
 * enough for a readable label once a weapon can hit past twenty. Every damage bar is drawn to
 * scale against the longest damage bar, so their lengths can be compared directly.
 *
 * <p>Misses and splashes are the exception. They routinely outnumber any single damage value
 * several times over, and scaling to them would squash every real bar to nothing, so those two
 * run off the end of the chart with a torn edge and their true count beside them. They sit above
 * a divider, apart from the damage they are not part of.
 *
 * <p>When a community distribution is supplied, each row grows a second bar under the first and
 * both series switch to <em>share of attempts</em> rather than raw counts. Everyone else together
 * has thousands of times more hits than one player, so raw counts would draw one visible series
 * and one flat line; shares are the only way the two shapes can be compared.
 */
public class HistogramPanel extends JPanel
{
	private static final int SOLO_ROW_HEIGHT = 13;
	private static final int SOLO_BAR_HEIGHT = 9;
	private static final int PAIR_ROW_HEIGHT = 15;
	private static final int PAIR_BAR_HEIGHT = 5;
	private static final int PAIR_BAR_GAP = 1;

	private static final int GUTTER = 22;
	private static final int LEFT_PAD = 2;
	private static final int RIGHT_PAD = 4;
	private static final int TOP_PAD = 3;
	private static final int BOTTOM_PAD = 3;
	private static final int COUNT_GAP = 4;
	private static final int DIVIDER_HEIGHT = 5;
	private static final int MIN_BAR = 2;

	private static final Color HIT_COLOR = ColorScheme.BRAND_ORANGE;
	private static final Color MAX_COLOR = new Color(255, 200, 80);
	private static final Color MISS_COLOR = new Color(120, 120, 120);
	private static final Color SPLASH_COLOR = new Color(140, 122, 230);
	private static final Color AXIS_COLOR = new Color(150, 150, 150);
	private static final Color DIVIDER_COLOR = new Color(70, 70, 70);
	private static final Color HOVER_COLOR = new Color(255, 255, 255, 40);

	/** Killing blows, both in their bar segments here and wherever the panel refers to them. */
	public static final Color KILL_COLOR = new Color(196, 96, 70);

	/** Everyone else's distribution. Cool against the warm bars so the two never read as one. */
	public static final Color COMMUNITY_COLOR = new Color(96, 156, 196);

	/** One bar: a hitsplat amount, or the miss or splash tally. */
	private static class Row
	{
		private final String label;
		private final String tooltip;
		private final int count;
		private final Color color;

		/** Miss and splash rows are not part of the damage scale and may run off the end. */
		private final boolean offScale;

		/** How many of {@link #count} ended a fight; drawn as a segment at the end of the bar. */
		private final int killCount;

		/** The same amount across everyone else, or -1 when there is no community series. */
		private int otherCount = -1;

		Row(String label, String tooltip, int count, Color color, boolean offScale, int killCount)
		{
			this.label = label;
			this.tooltip = tooltip;
			this.count = count;
			this.color = color;
			this.offScale = offScale;
			this.killCount = killCount;
		}
	}

	private List<Row> rows = new ArrayList<>();
	private int peak;
	private int total;

	/** Attempts across everyone else, so their bars can be drawn as shares. 0 when absent. */
	private int otherTotal;

	private boolean paired;

	/** Index of the first damage row, which is where the divider goes. */
	private int firstDamageRow;

	private int hoverRow = -1;

	public HistogramPanel()
	{
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setToolTipText("");
		setPreferredSize(new Dimension(0, height()));
		setMinimumSize(new Dimension(50, height()));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, height()));

		addMouseMotionListener(new java.awt.event.MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				final int row = rowAt(e.getY());
				if (row != hoverRow)
				{
					hoverRow = row;
					repaint();
				}
			}
		});
		addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseExited(MouseEvent e)
			{
				hoverRow = -1;
				repaint();
			}
		});
	}

	/**
	 * @param killCounts killing blows by damage that are folded into {@code counts}, so they can
	 * be picked out within their bars. Empty when they are not charted.
	 */
	public void setData(int[] counts, int[] killCounts, int splashes, int highestHit)
	{
		setData(counts, killCounts, splashes, highestHit, null, 0);
	}

	/**
	 * @param otherCounts everyone else's hitsplats by damage, or null to draw this player alone.
	 * @param otherSplashes their splashes, which count towards their attempts the same way.
	 */
	public void setData(int[] counts, int[] killCounts, int splashes, int highestHit,
		@Nullable int[] otherCounts, int otherSplashes)
	{
		final List<Row> built = new ArrayList<>();
		int attempts = splashes;

		if (splashes > 0 || (otherCounts != null && otherSplashes > 0))
		{
			built.add(new Row("S", "Splash", splashes, SPLASH_COLOR, true, 0));
		}

		if (counts.length > 0)
		{
			built.add(new Row("0", "Miss", counts[0], MISS_COLOR, true, 0));
			attempts += counts[0];
		}

		firstDamageRow = built.size();

		// Long enough for both series: a damage value only they have reached still gets a row.
		final int width = Math.max(counts.length, otherCounts == null ? 0 : otherCounts.length);

		int highest = 0;
		for (int d = 1; d < width; d++)
		{
			final int mine = d < counts.length ? counts[d] : 0;
			final int kills = d < killCounts.length ? Math.min(killCounts[d], mine) : 0;
			built.add(new Row(Integer.toString(d), "Hit " + d, mine,
				d == highestHit ? MAX_COLOR : HIT_COLOR, false, kills));
			attempts += mine;
			highest = Math.max(highest, mine);
		}

		paired = otherCounts != null;
		otherTotal = 0;
		if (paired)
		{
			otherTotal = otherSplashes;
			for (int d = 0; d < otherCounts.length; d++)
			{
				otherTotal += otherCounts[d];
			}
			for (int i = 0; i < built.size(); i++)
			{
				final Row row = built.get(i);
				if (i < firstDamageRow)
				{
					row.otherCount = "S".equals(row.label)
						? otherSplashes
						: (otherCounts.length > 0 ? otherCounts[0] : 0);
				}
				else
				{
					final int d = i - firstDamageRow + 1;
					row.otherCount = d < otherCounts.length ? otherCounts[d] : 0;
				}
			}
		}

		rows = built;
		peak = highest;
		total = attempts;
		hoverRow = -1;

		final Dimension size = new Dimension(0, height());
		setPreferredSize(size);
		setMinimumSize(new Dimension(50, size.height));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));

		revalidate();
		repaint();
	}

	private int rowHeight()
	{
		return paired ? PAIR_ROW_HEIGHT : SOLO_ROW_HEIGHT;
	}

	private int height()
	{
		if (rows == null || rows.isEmpty())
		{
			return 28;
		}
		return TOP_PAD + rows.size() * rowHeight() + BOTTOM_PAD
			+ (firstDamageRow > 0 ? DIVIDER_HEIGHT : 0);
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		final int index = rowAt(event.getY());
		if (index < 0)
		{
			return null;
		}

		final Row row = rows.get(index);
		final String kills = row.killCount == 0
			? ""
			: ", " + row.killCount + (row.killCount == 1 ? " killing blow" : " killing blows");

		if (!paired)
		{
			return String.format("%s: %d (%s)", row.tooltip, row.count, percent(row.count, total)) + kills;
		}

		return "<html>" + row.tooltip
			+ "<br>You: " + row.count + " (" + percent(row.count, total) + ")" + kills
			+ "<br>Everyone: " + Math.max(0, row.otherCount)
			+ " (" + percent(Math.max(0, row.otherCount), otherTotal) + ")</html>";
	}

	private static String percent(int part, int whole)
	{
		return whole <= 0 ? "0.0%" : String.format("%.1f%%", 100.0 * part / whole);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);

		final Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setFont(FontManager.getRunescapeSmallFont());
			paint(g2);
		}
		finally
		{
			g2.dispose();
		}
	}

	private void paint(Graphics2D g)
	{
		final FontMetrics fm = g.getFontMetrics();

		if (rows.isEmpty())
		{
			g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			final String msg = "No hits recorded yet";
			g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2 + fm.getAscent() / 2);
			return;
		}

		final int barLeft = LEFT_PAD + GUTTER;
		final int countWidth = fm.stringWidth(Integer.toString(widestCount())) + COUNT_GAP;
		final int barMax = Math.max(MIN_BAR, getWidth() - barLeft - RIGHT_PAD - countWidth);

		// Alone, bars are counts against the tallest count. Beside the community they are shares
		// against the tallest share of either series, which is the only comparable scale.
		final double scale = paired ? peakShare() : Math.max(1, peak);
		final int rowHeight = rowHeight();
		final int barHeight = paired ? PAIR_BAR_HEIGHT : SOLO_BAR_HEIGHT;

		for (int i = 0; i < rows.size(); i++)
		{
			final Row row = rows.get(i);
			final int y = rowTop(i);

			if (i == hoverRow)
			{
				g.setColor(HOVER_COLOR);
				g.fillRect(0, y, getWidth(), rowHeight);
			}

			// Amount, right-aligned into its own column so the bars all start together.
			g.setColor(AXIS_COLOR);
			final int labelWidth = fm.stringWidth(row.label);
			g.drawString(row.label, barLeft - COUNT_GAP - labelWidth,
				y + fm.getAscent() + (paired ? -1 : 0));

			final int barY = paired
				? y + 1
				: y + (rowHeight - barHeight) / 2;

			if (row.count > 0)
			{
				final double value = paired ? share(row.count, total) : row.count;
				final boolean runsOff = row.offScale && value > scale;
				final int length = runsOff
					? barMax
					: Math.max(MIN_BAR, (int) Math.round(barMax * value / scale));

				g.setColor(row.color);
				g.fillRect(barLeft, barY, length, barHeight);

				if (row.killCount > 0 && !runsOff)
				{
					// The killing blows sit at the end of their bar in their own colour, so a bar
					// that owes part of its length to capped hits says so.
					final double killValue = paired ? share(row.killCount, total) : row.killCount;
					final int segment = Math.min(length,
						Math.max(1, (int) Math.round(barMax * killValue / scale)));
					g.setColor(KILL_COLOR);
					g.fillRect(barLeft + length - segment, barY, segment, barHeight);
				}

				if (runsOff)
				{
					tear(g, barLeft + length, barY, barHeight);
				}

				g.setColor(row.color);
				g.drawString(Integer.toString(row.count), barLeft + length + COUNT_GAP,
					y + fm.getAscent() + (paired ? -1 : 0));
			}

			if (paired && row.otherCount > 0)
			{
				final double value = share(row.otherCount, otherTotal);
				final boolean runsOff = row.offScale && value > scale;
				final int length = runsOff
					? barMax
					: Math.max(MIN_BAR, (int) Math.round(barMax * value / scale));
				final int otherY = barY + barHeight + PAIR_BAR_GAP;

				g.setColor(COMMUNITY_COLOR);
				g.fillRect(barLeft, otherY, length, barHeight);
				if (runsOff)
				{
					tear(g, barLeft + length, otherY, barHeight);
				}
			}
		}

		if (firstDamageRow > 0)
		{
			// Misses and splashes are not damage; keep them visibly apart from it.
			final int y = rowTop(firstDamageRow) - DIVIDER_HEIGHT / 2 - 1;
			g.setColor(DIVIDER_COLOR);
			g.drawLine(LEFT_PAD, y, getWidth() - RIGHT_PAD, y);
		}
	}

	/**
	 * Tears the end off a bar so it reads as "longer than the chart" rather than as a bar that
	 * happens to reach the edge.
	 */
	private void tear(Graphics2D g, int end, int barY, int barHeight)
	{
		g.setColor(getBackground());
		for (int notch = 0; notch < 3; notch++)
		{
			final int nx = end - 1 - notch * 3;
			g.drawLine(nx, barY, nx, barY + barHeight - 1);
		}
	}

	private static double share(int part, int whole)
	{
		return whole <= 0 ? 0 : (double) part / whole;
	}

	/** The tallest damage share in either series; what both are drawn against. */
	private double peakShare()
	{
		double highest = 0;
		for (int i = firstDamageRow; i < rows.size(); i++)
		{
			final Row row = rows.get(i);
			highest = Math.max(highest, share(row.count, total));
			highest = Math.max(highest, share(Math.max(0, row.otherCount), otherTotal));
		}
		return highest <= 0 ? 1 : highest;
	}

	private int rowTop(int index)
	{
		return TOP_PAD + index * rowHeight()
			+ (index >= firstDamageRow && firstDamageRow > 0 ? DIVIDER_HEIGHT : 0);
	}

	private int rowAt(int y)
	{
		final int rowHeight = rowHeight();
		for (int i = 0; i < rows.size(); i++)
		{
			final int top = rowTop(i);
			if (y >= top && y < top + rowHeight)
			{
				return i;
			}
		}
		return -1;
	}

	private int widestCount()
	{
		int widest = 0;
		for (Row row : rows)
		{
			widest = Math.max(widest, row.count);
		}
		return widest;
	}
}
