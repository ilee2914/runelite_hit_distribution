package com.github.ilee2.hitdistribution.ui;

import com.github.ilee2.hitdistribution.Aggregate;
import com.github.ilee2.hitdistribution.CombatContext;
import com.github.ilee2.hitdistribution.ContextStats;
import com.github.ilee2.hitdistribution.HitDistributionConfig;
import com.github.ilee2.hitdistribution.HitDistributionStore;
import com.github.ilee2.hitdistribution.FilterOptions;
import com.github.ilee2.hitdistribution.HistoryFilter;
import com.github.ilee2.hitdistribution.HistoryScope;
import com.github.ilee2.hitdistribution.HitRecord;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToolTip;
import javax.swing.JComponent;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar: the filters, a stats grid, the histogram, and one row per recorded setup showing the
 * damage it produced.
 */
public class HitDistributionPanel extends PluginPanel
{
	private static final Color PROTECTED_COLOR = new Color(216, 110, 110);

	private static final Color BOOSTED_COLOR = new Color(110, 210, 110);
	private static final Color MISS_COLOR = new Color(130, 130, 130);
	private static final Color SPLASH_COLOR = new Color(140, 122, 230);
	private static final Color MAX_COLOR = new Color(255, 200, 80);

	/** Skill icon sprites, in {@link CombatContext#SKILL_NAMES} order. */
	private static final int[] SKILL_SPRITES = {197, 198, 200, 202};

	/** Height of one skill row in the setup tooltip, tall enough not to clip the sprite. */
	private static final int SKILL_ROW_HEIGHT = 22;

	/** Width of the skill icon cell, wide enough to centre the widest sprite with a margin. */
	private static final int SKILL_ICON_WIDTH = 22;

	/** Vertical space between skill rows. */
	private static final int SKILL_ROW_GAP = 4;

	private final HitDistributionStore store;
	private final HitDistributionConfig config;
	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final Consumer<HistoryScope> clearAction;

	private final JComboBox<HistoryScope> scopeBox = new JComboBox<>(HistoryScope.values());
	private final JComboBox<Protection> protectionBox = new JComboBox<>(Protection.values());

	private final FilterSelect npcSelect;
	private final FilterSelect attackSelect;
	private final EquipmentFilterPanel gearFilterPanel;
	private final JPanel gearFilterWrapper;
	private final JLabel gearToggleLabel = new JLabel();

	/** Equipment slot index to the item required in it. The weapon lives here too. */
	private final Map<Integer, Integer> gearFilter = new HashMap<>();

	private final JLabel missCaption = new JLabel();
	private final JLabel scaleCaption = new JLabel();
	private final JCheckBox killingBlowBox = new JCheckBox("Count killing blows");
	private final JLabel killingBlowNote = new JLabel();
	private final JPanel statsPanel = new JPanel(new GridLayout(0, 2, 4, 2));
	private final HistogramPanel histogram = new HistogramPanel();
	private final JPanel contextsPanel = new JPanel();
	private final JLabel statusLabel = new JLabel();

	private boolean populating;

	public HitDistributionPanel(HitDistributionStore store, HitDistributionConfig config,
		ConfigManager configManager, ItemManager itemManager, SpriteManager spriteManager,
		Consumer<HistoryScope> clearAction)
	{
		// Wrapped: PluginPanel supplies the scroll pane, which a long breakdown list needs.
		super(true);
		this.store = store;
		this.config = config;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.clearAction = clearAction;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(new EmptyBorder(2, 2, 2, 2));

		content.add(header());
		content.add(Box.createVerticalStrut(6));

		npcSelect = new FilterSelect("All monsters", null, this::refresh);
		attackSelect = new FilterSelect("All styles", null, this::refresh);
		gearFilterPanel = new EquipmentFilterPanel(itemManager, this::onGearSlotSelected);
		gearFilterWrapper = wrap(gearFilterPanel);

		// The weapon has no box of its own: it is the weapon slot of the gear filter below.
		content.add(filterRow("View", scopeBox));
		content.add(Box.createVerticalStrut(3));
		content.add(filterRow("Monster", npcSelect));
		content.add(Box.createVerticalStrut(3));
		content.add(filterRow("Style", attackSelect));
		content.add(Box.createVerticalStrut(3));
		content.add(filterRow("Target", protectionBox));
		content.add(Box.createVerticalStrut(4));
		content.add(gearToggleRow());
		content.add(gearFilterWrapper);
		content.add(Box.createVerticalStrut(8));

		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
		statsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(statsPanel);
		content.add(Box.createVerticalStrut(8));

		missCaption.setFont(FontManager.getRunescapeSmallFont());
		missCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		missCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(missCaption);

		scaleCaption.setFont(FontManager.getRunescapeSmallFont());
		scaleCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scaleCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(scaleCaption);

		histogram.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(histogram);
		content.add(Box.createVerticalStrut(4));
		content.add(killingBlowRow());
		content.add(Box.createVerticalStrut(8));

		contextsPanel.setLayout(new BoxLayout(contextsPanel, BoxLayout.Y_AXIS));
		contextsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contextsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(contextsPanel);

		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(statusLabel);

		add(content, BorderLayout.NORTH);

		final java.awt.event.ActionListener onFilter = e ->
		{
			if (!populating)
			{
				refresh();
			}
		};
		protectionBox.addActionListener(onFilter);
		scopeBox.addActionListener(onFilter);

		populating = true;
		// Attacks into a protection prayer are a different distribution, and almost never the one
		// being asked about, so they start hidden.
		protectionBox.setSelectedItem(Protection.UNPROTECTED);
		scopeBox.setSelectedItem(config.defaultScope());
		populating = false;

		refresh();
	}

	// ------------------------------------------------------------------ layout

	private JPanel header()
	{
		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);

		final JLabel title = new JLabel("Hit Distribution");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		header.add(title, BorderLayout.WEST);

		final JPanel buttons = new JPanel();
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));

		final JLabel reset = new JLabel("Reset filter");
		reset.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reset.setFont(FontManager.getRunescapeSmallFont());
		reset.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		reset.setToolTipText("Show everything");
		reset.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				populating = true;
				npcSelect.clear();
				attackSelect.clear();
				gearFilter.clear();
				protectionBox.setSelectedItem(Protection.UNPROTECTED);
				populating = false;
				refresh();
			}
		});
		buttons.add(reset);
		buttons.add(Box.createHorizontalStrut(8));

		final JButton clear = new JButton("Clear \u25be");
		clear.setFont(FontManager.getRunescapeSmallFont());
		clear.setMargin(new java.awt.Insets(1, 6, 1, 6));
		clear.setToolTipText("Start the session over, or delete this character's whole history");
		clear.addActionListener(e -> clearMenu().show(clear, 0, clear.getHeight()));
		buttons.add(clear);

		header.add(buttons, BorderLayout.EAST);
		return header;
	}

	/** Clearing the session and clearing the file are different enough to be asked separately. */
	private JPopupMenu clearMenu()
	{
		final JPopupMenu menu = new JPopupMenu();

		final JMenuItem session = new JMenuItem("This session");
		session.setFont(FontManager.getRunescapeSmallFont());
		session.setToolTipText("Start the session counters over. The saved history is kept.");
		session.addActionListener(e -> confirmClear(HistoryScope.SESSION));
		menu.add(session);

		final JMenuItem everything = new JMenuItem("Everything");
		everything.setFont(FontManager.getRunescapeSmallFont());
		everything.setToolTipText("Delete this character's recorded history");
		everything.addActionListener(e -> confirmClear(HistoryScope.ALL_TIME));
		menu.add(everything);

		return menu;
	}

	private void confirmClear(HistoryScope scope)
	{
		final boolean sessionOnly = scope == HistoryScope.SESSION;
		final String message = sessionOnly
			? "Start this session's counters over?\nEverything already saved is kept."
			: "Delete every hit recorded for this character?\nThis cannot be undone.";
		final int choice = JOptionPane.showConfirmDialog(this, message,
			sessionOnly ? "Clear this session" : "Clear all recorded hits",
			JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice == JOptionPane.YES_OPTION)
		{
			clearAction.accept(scope);
		}
	}

	private JPanel filterRow(String label, JComponent box)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

		final JLabel name = new JLabel(label);
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setPreferredSize(new Dimension(48, 22));
		row.add(name, BorderLayout.WEST);

		box.setFont(FontManager.getRunescapeSmallFont());
		row.add(box, BorderLayout.CENTER);
		return row;
	}

	/**
	 * The killing-blow switch sits under the chart it changes. The hit that kills a monster is
	 * a real hit, but it is capped by the monster's remaining hitpoints, so it is not a fair
	 * sample of the roll; the switch writes the same setting the config panel shows.
	 */
	private JPanel killingBlowRow()
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		killingBlowBox.setFont(FontManager.getRunescapeSmallFont());
		killingBlowBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		killingBlowBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		killingBlowBox.setFocusPainted(false);
		killingBlowBox.setToolTipText("<html>The hit that kills a monster is capped by its remaining hitpoints,<br>"
			+ "so it is not a fair sample of what the weapon rolls.<br>"
			+ "Off: the truest distribution. On: every hit you saw.<br>"
			+ "Total damage, accuracy and DPS count them either way.</html>");
		killingBlowBox.addActionListener(e ->
		{
			if (!populating)
			{
				configManager.setConfiguration(HitDistributionConfig.GROUP, "includeKillingBlows",
					killingBlowBox.isSelected());
				refresh();
			}
		});
		row.add(killingBlowBox, BorderLayout.WEST);

		killingBlowNote.setFont(FontManager.getRunescapeSmallFont());
		killingBlowNote.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(killingBlowNote, BorderLayout.CENTER);
		return row;
	}

	/** The gear grid is tall, so it stays folded away until it is asked for. */
	private JPanel gearToggleRow()
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		gearToggleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		gearToggleLabel.setFont(FontManager.getRunescapeSmallFont());
		gearToggleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		gearToggleLabel.setToolTipText("Filter by the rest of your worn equipment");
		gearToggleLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				gearFilterWrapper.setVisible(!gearFilterWrapper.isVisible());
				updateGearToggle();
				revalidate();
				repaint();
			}
		});

		row.add(gearToggleLabel, BorderLayout.WEST);
		updateGearToggle();
		return row;
	}

	private void updateGearToggle()
	{
		final String arrow = gearFilterWrapper.isVisible() ? "\u25be" : "\u25b8";
		final int active = gearFilter.size();
		gearToggleLabel.setText(arrow + " Gear filter" + (active > 0 ? "  (" + active + ")" : ""));
	}

	private JPanel wrap(JComponent inner)
	{
		final JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, inner.getPreferredSize().height + 8));
		wrapper.add(inner);
		return wrapper;
	}

	private void onGearSlotSelected(int slot, @Nullable Integer itemId)
	{
		if (itemId == null)
		{
			gearFilter.remove(slot);
		}
		else
		{
			gearFilter.put(slot, itemId);
		}

		refresh();
	}

	// ----------------------------------------------------------------- refresh

	/** Re-reads the store and redraws. Must be called on the Swing thread. */
	public void refresh()
	{
		// Each list is built against the other selections, so choosing a weapon narrows the attack
		// list to the attacks actually made with it. A selection is never dropped on the user's
		// behalf, so the filter is the same before and after the lists are replaced.
		final HistoryScope scope = scope();
		final HistoryFilter filter = currentFilter();
		final FilterOptions options = store.options(config.splitNpcById(), filter, scope);

		npcSelect.setOptions(options.getNpcs());
		attackSelect.setOptions(options.getAttacks());
		gearFilterPanel.setOptions(options.getGearBySlot());
		gearFilterPanel.setSelection(gearFilter);
		updateGearToggle();

		final Aggregate aggregate = store.aggregate(filter, scope, config.includeKillingBlows());

		fillStats(aggregate);
		fillCaptions(aggregate);
		fillKillingBlows(aggregate);
		histogram.setData(aggregate.getCounts(),
			aggregate.isKillingBlowsIncluded() ? aggregate.getKillCounts() : new int[0],
			aggregate.getSplashes(), aggregate.getHighestHit());
		fillHistory(filter, scope);

		final String player = store.getPlayerName();
		final StringBuilder status = new StringBuilder();
		status.append(player == null ? "Not logged in" : player);
		if (scope == HistoryScope.SESSION)
		{
			status.append(" \u00b7 ").append(duration(store.getSessionMillis()));
		}
		else if (scope == HistoryScope.CURRENT_FIGHT)
		{
			status.append(" \u00b7 ").append(fightStatus(aggregate.isEmpty()));
		}
		status.append(" \u00b7 ").append(store.getContextCount(scope)).append(" contexts");
		if (store.getUnattributedHits(scope) > 0)
		{
			status.append(" \u00b7 ").append(store.getUnattributedHits(scope)).append(" unattributed");
		}
		statusLabel.setText(status.toString());

		revalidate();
		repaint();
	}

	/** What the fight window holds: a fight in progress, the last kill, or nothing yet. */
	private String fightStatus(boolean empty)
	{
		if (store.isFightOver())
		{
			return store.npcName(store.getLastKillNpcId()) + " died "
				+ duration(System.currentTimeMillis() - store.getLastKillMillis()) + " ago";
		}
		return empty ? "No fight yet" : "Fighting for " + duration(store.getFightMillis());
	}

	/** Whether the panel is showing this session or the whole history. */
	private HistoryScope scope()
	{
		final HistoryScope scope = (HistoryScope) scopeBox.getSelectedItem();
		return scope == null ? HistoryScope.ALL_TIME : scope;
	}

	private HistoryFilter currentFilter()
	{
		final FilterOptions.Option npc = npcSelect.getSelected();
		final FilterOptions.Option attack = attackSelect.getSelected();

		final Protection protection = (Protection) protectionBox.getSelectedItem();

		// The weapon is not read from its own box: it is slot 3 of the gear filter, which the box
		// and the equipment grid both write to.
		return new HistoryFilter(
			npc == null ? null : npc.getName(),
			npc == null ? null : npc.getId(),
			gearFilter,
			attack == null ? null : attack.getName(),
			protection == null ? null : protection.value);
	}

	private void fillStats(Aggregate a)
	{
		statsPanel.removeAll();

		if (a.isEmpty())
		{
			addStat("Attacks", "0");
			addStat("Hits", "none yet");
			return;
		}

		addStat("Attacks", integer(a.getAttacks()));
		addStat("Hitsplats", integer(a.getHitsplats()));
		addStat("Total damage", integer(a.getTotalDamage()));
		addStat("Avg / attack", decimal(a.getAveragePerAttack()));
		addStat("Avg / hitsplat", decimal(a.getAveragePerHitsplat()));
		addStat("Avg / landed hit", decimal(a.getAveragePerLandedHit()));
		addStat("Accuracy", percent(a.getAccuracy()));
		if (a.getMagicAttacks() > 0 || a.getSplashes() > 0)
		{
			addStat("Splash rate", percent(a.getSplashRate()) + "  (" + integer(a.getSplashes()) + ")");
		}
		if (a.getProtectedAttacks() > 0)
		{
			addStat("Into protection",
				percent(a.getProtectedShare()) + "  (" + integer(a.getProtectedAttacks()) + ")");
		}
		addStat("Highest hit", integer(a.getHighestHit()));
		addStat("Max-hit rate", percent(a.getMaxHitRate()) + "  (" + integer(a.getMaxHits()) + ")");
		addStat("DPS", decimal(a.getDps()));
		addStat("Wasted ticks", integer(a.getWastedTicks()) + "  (" + percent(a.getWastedShare()) + ")");
		addStat("Wasted / attack", decimal(a.getWastedPerAttack()));
		if (a.getFights() > 0)
		{
			addStat("Kills", integer(a.getKills()) + " / " + integer(a.getFights()) + " fights");
			if (a.getKills() > 0)
			{
				addStat("Avg kill time", decimal(a.getAverageKillSeconds()) + " s");
			}
		}
	}

	/** Spells out what the chart's cut-off bars and its height actually mean. */
	private void fillCaptions(Aggregate a)
	{
		if (a.isEmpty())
		{
			missCaption.setText("");
			scaleCaption.setText("");
			return;
		}

		final int attempts = Math.max(1, a.getAttempts());
		final StringBuilder misses = new StringBuilder();
		misses.append("Misses ").append(integer(a.getZeroHits()))
			.append(" (").append(percent((double) a.getZeroHits() / attempts)).append(")");
		if (a.getSplashes() > 0)
		{
			misses.append("   Splashes ").append(integer(a.getSplashes()))
				.append(" (").append(percent((double) a.getSplashes() / attempts)).append(")");
		}
		missCaption.setText(misses.toString());

		final int peak = a.getDamagePeak();
		if (peak == 0)
		{
			scaleCaption.setText("No damage landed yet");
			return;
		}

		// The damage bars are always drawn to scale; only the miss and splash bars can run off
		// the end, and they say so with their own count.
		final boolean clipped = a.getZeroHits() > peak || a.getSplashes() > peak;
		scaleCaption.setText(clipped
			? "Miss and splash bars run past the edge; damage bars are to scale"
			: "Longest bar " + integer(peak) + " hits");
	}

	/** Keeps the switch under the chart in step with the setting, and says what it is doing. */
	private void fillKillingBlows(Aggregate a)
	{
		populating = true;
		killingBlowBox.setSelected(a.isKillingBlowsIncluded());
		populating = false;

		final int kills = a.getKillingBlows();
		if (kills == 0)
		{
			killingBlowNote.setText("");
		}
		else if (a.isKillingBlowsIncluded())
		{
			killingBlowNote.setForeground(HistogramPanel.KILL_COLOR);
			killingBlowNote.setText("\u25a0 " + integer(kills) + " in chart");
		}
		else
		{
			killingBlowNote.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			killingBlowNote.setText(integer(kills) + " left out");
		}
	}

	private void addStat(String name, String value)
	{
		final JLabel n = new JLabel(name);
		n.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		n.setFont(FontManager.getRunescapeSmallFont());
		statsPanel.add(n);

		final JLabel v = new JLabel(value, SwingConstants.RIGHT);
		v.setForeground(Color.WHITE);
		v.setFont(FontManager.getRunescapeSmallFont());
		statsPanel.add(v);
	}

	/** What each of the matching hits actually did, newest first. */
	private void fillHistory(HistoryFilter filter, HistoryScope scope)
	{
		contextsPanel.removeAll();
		if (!config.showContexts())
		{
			return;
		}

		final List<HitRecord> hits = store.recentHits(filter, Math.max(1, config.maxContexts()), scope);
		if (hits.isEmpty())
		{
			return;
		}

		final JLabel heading = new JLabel("Damage history");
		heading.setForeground(Color.WHITE);
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setBorder(new EmptyBorder(0, 0, 4, 0));
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		contextsPanel.add(heading);

		for (HitRecord hit : hits)
		{
			contextsPanel.add(hitRow(hit, scope));
			contextsPanel.add(Box.createVerticalStrut(2));
		}
	}

	/** One hit: what it did, what it was against, and what it was dealt with. */
	private JPanel hitRow(HitRecord hit, HistoryScope scope)
	{
		final CombatContext c = store.contextFor(hit.getContextKey(), scope);

		final JPanel row = new JPanel(new BorderLayout(6, 0))
		{
			@Override
			public JToolTip createToolTip()
			{
				return c == null ? super.createToolTip() : setupTip(c);
			}
		};
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 6, 3, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		if (c != null)
		{
			// Any non-null text registers with the tooltip manager; createToolTip supplies the
			// panel that is actually shown.
			row.setToolTipText(" ");
		}

		final TipLabel damage = new TipLabel(hit.isSplash() ? "spl" : Integer.toString(hit.getDamage()),
			damageColor(hit), c);
		damage.setFont(FontManager.getRunescapeBoldFont());
		damage.setHorizontalAlignment(SwingConstants.RIGHT);
		damage.setPreferredSize(new Dimension(26, 0));
		row.add(damage, BorderLayout.WEST);

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.add(new TipLabel(store.npcName(hit.getNpcId()), Color.WHITE, c));
		text.add(new TipLabel(store.itemName(hit.getWeaponId()), ColorScheme.LIGHT_GRAY_COLOR, c));
		row.add(text, BorderLayout.CENTER);

		if (hit.isKillingBlow())
		{
			// The hit that ended the fight, in the colour the chart uses for the same thing.
			row.add(new TipLabel("kill", HistogramPanel.KILL_COLOR, c), BorderLayout.EAST);
		}

		if (c != null)
		{
			// Warm the item images so the first hover is not a grid of empty boxes.
			for (int itemId : c.getGear())
			{
				if (itemId > 0)
				{
					itemManager.getImage(itemId);
				}
			}
		}

		return row;
	}

	private static Color damageColor(HitRecord hit)
	{
		if (hit.isSplash())
		{
			return SPLASH_COLOR;
		}
		if (hit.getDamage() == 0)
		{
			return MISS_COLOR;
		}
		return hit.isMax() ? MAX_COLOR : ColorScheme.BRAND_ORANGE;
	}

	private JToolTip setupTip(CombatContext c)
	{
		final JPanel content = setupTooltip(c);
		final JToolTip tip = new JToolTip();
		tip.setLayout(new BorderLayout());
		tip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tip.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		tip.add(content, BorderLayout.CENTER);
		tip.setPreferredSize(content.getPreferredSize());
		return tip;
	}

	private JLabel line(String text, Color color)
	{
		final JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/**
	 * A label carrying the row's setup tooltip. Without this the tooltip only appears over the
	 * few pixels of the row not covered by its own text.
	 */
	private class TipLabel extends JLabel
	{
		@Nullable
		private final transient CombatContext context;

		TipLabel(String text, Color color, @Nullable CombatContext context)
		{
			super(text);
			this.context = context;
			setForeground(color);
			setFont(FontManager.getRunescapeSmallFont());
			setAlignmentX(Component.LEFT_ALIGNMENT);
			if (context != null)
			{
				setToolTipText(" ");
			}
		}

		@Override
		public JToolTip createToolTip()
		{
			return context == null ? super.createToolTip() : setupTip(context);
		}
	}

	/** The setup behind a row: worn gear as the game lays it out, levels, and prayers. */
	private JPanel setupTooltip(CombatContext c)
	{
		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(6, 8, 6, 8));

		panel.add(line(store.itemName(c.getWeaponId()) + " \u00b7 " + c.getAttackLabel(), Color.WHITE));
		panel.add(line(store.npcLabel(c.getNpcId()) + " \u00b7 " + c.getAttackSpeed() + "t",
			ColorScheme.LIGHT_GRAY_COLOR));
		panel.add(Box.createVerticalStrut(6));

		// Levels sit beside the gear rather than under it: the grid is three columns wide and
		// leaves plenty of room to its right.
		final JPanel middle = new JPanel(new BorderLayout(10, 0));
		middle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		middle.setAlignmentX(Component.LEFT_ALIGNMENT);
		middle.add(new EquipmentPanel(itemManager, c.getGear()), BorderLayout.WEST);
		middle.add(levelColumn(c), BorderLayout.CENTER);
		panel.add(middle);

		if (!c.getPrayers().isEmpty())
		{
			panel.add(Box.createVerticalStrut(4));
			panel.add(line(prayerSummary(c.getPrayers()), ColorScheme.BRAND_ORANGE));
		}

		panel.add(Box.createVerticalStrut(4));
		panel.add(line("Target: " + c.getTargetPrayerLabel(),
			c.isStyleProtected() ? PROTECTED_COLOR : ColorScheme.LIGHT_GRAY_COLOR));
		final HistoryScope scope = scope();
		panel.add(line(integer(store.attacksInContext(c.getKey(), scope)) + " attacks with this setup"
			+ (scope == HistoryScope.SESSION ? " this session" : ""), ColorScheme.LIGHT_GRAY_COLOR));

		return panel;
	}

	/** Boosted levels beside their skill icons, with the real level alongside when it differs. */
	private JPanel levelColumn(CombatContext c)
	{
		final int[] boosted = c.getBoosted();
		final int[] real = c.getReal();

		final JPanel grid = new JPanel();
		grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);

		// The gear grid beside this column is far taller than the four skill rows, so the rows
		// can be given room to breathe and the column centred against it rather than crammed
		// into the top corner.
		grid.add(Box.createVerticalGlue());

		for (int i = 0; i < CombatContext.SKILL_NAMES.length && i < SKILL_SPRITES.length
			&& i < boosted.length && i < real.length; i++)
		{
			final String text = boosted[i] == real[i]
				? Integer.toString(boosted[i])
				: boosted[i] + " (" + real[i] + ")";

			// Icon on the left, number right-aligned, so the numbers line up down the column
			// however wide each one is.
			final JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(SKILL_ICON_WIDTH, SKILL_ROW_HEIGHT));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			icon.setVerticalAlignment(SwingConstants.CENTER);
			spriteManager.addSpriteTo(icon, SKILL_SPRITES[i], 0);

			final JLabel value = new JLabel(text, SwingConstants.RIGHT);
			value.setFont(FontManager.getRunescapeSmallFont());
			value.setForeground(boosted[i] > real[i] ? BOOSTED_COLOR : Color.WHITE);

			final JPanel entry = new JPanel(new BorderLayout(6, 0));
			entry.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			entry.setAlignmentX(Component.LEFT_ALIGNMENT);
			entry.setToolTipText(CombatContext.SKILL_NAMES[i]);
			entry.setMaximumSize(new Dimension(Integer.MAX_VALUE, SKILL_ROW_HEIGHT));
			entry.add(icon, BorderLayout.WEST);
			entry.add(value, BorderLayout.CENTER);
			grid.add(entry);

			if (i + 1 < CombatContext.SKILL_NAMES.length)
			{
				grid.add(Box.createVerticalStrut(SKILL_ROW_GAP));
			}
		}

		grid.add(Box.createVerticalGlue());

		return grid;
	}

	private static String prayerSummary(List<String> prayers)
	{
		final List<String> pretty = new ArrayList<>(prayers.size());
		for (String p : prayers)
		{
			final String[] words = p.toLowerCase(Locale.ROOT).replace("rp_", "").split("_");
			final StringBuilder sb = new StringBuilder();
			for (String w : words)
			{
				if (w.isEmpty())
				{
					continue;
				}
				if (sb.length() > 0)
				{
					sb.append(' ');
				}
				sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
			pretty.add(sb.toString());
		}
		return String.join(", ", pretty);
	}

	private static String integer(long value)
	{
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String decimal(double value)
	{
		return String.format(Locale.ROOT, "%.2f", value);
	}

	/** Rough elapsed time, in the largest two units that say anything. */
	private static String duration(long millis)
	{
		final long seconds = Math.max(0, millis / 1000);
		final long hours = seconds / 3600;
		final long minutes = (seconds % 3600) / 60;
		if (hours > 0)
		{
			return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
		}
		if (minutes > 0)
		{
			return minutes + "m";
		}
		return seconds + "s";
	}

	private static String percent(double share)
	{
		return String.format(Locale.ROOT, "%.1f%%", share * 100);
	}

	/**
	 * The Target filter. An NPC praying against the style being used takes far less damage from
	 * it, so folding both cases into one average understates what the gear actually does.
	 */
	private enum Protection
	{
		ANY("All targets", null),
		UNPROTECTED("Not protecting", Boolean.FALSE),
		PROTECTED("Praying against me", Boolean.TRUE);

		private final String label;

		@Nullable
		private final Boolean value;

		Protection(String label, @Nullable Boolean value)
		{
			this.label = label;
			this.value = value;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
