package com.github.ilee2.hitstats.ui;

import com.github.ilee2.hitstats.CombatContext;
import com.github.ilee2.hitstats.FilterOptions;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Filter by worn equipment by clicking the slot you care about, laid out the way the game lays
 * out the equipment tab. Eleven dropdowns would have swamped the panel; eleven boxes in the shape
 * players already know do not.
 *
 * <p>A slot with a filter on it shows the chosen item and an orange border. Clicking a slot lists
 * only what was actually worn there in the hits that match the rest of the filter, including
 * having worn nothing there: "no shield" and "any shield" are different questions, and both are
 * askable.
 */
class EquipmentFilterPanel extends JPanel
{
	interface SlotListener
	{
		/** @param itemId the item required in this slot, or null to stop filtering on it. */
		void slotChanged(int slot, @Nullable Integer itemId);
	}

	private static final int CELL = 32;
	private static final int GAP = 2;
	private static final int MAX_SUGGESTIONS = 20;

	/**
	 * Text drawn in a slot pinned to "nothing was worn here". Plain ASCII: the RuneScape fonts
	 * have no glyph for the symbols that would say this more compactly.
	 */
	private static final String NOTHING_MARK = "none";

	private static final Color EMPTY_SLOT = new Color(40, 40, 40);
	private static final Color SLOT_BORDER = new Color(70, 70, 70);
	private static final Color UNUSED_SLOT = new Color(30, 30, 30);

	private final ItemManager itemManager;
	private final SlotListener listener;
	private final Map<Integer, JLabel> cells = new HashMap<>();

	private Map<Integer, List<FilterOptions.Option>> options = Collections.emptyMap();
	private Map<Integer, Integer> selection = Collections.emptyMap();

	EquipmentFilterPanel(ItemManager itemManager, SlotListener listener)
	{
		this.itemManager = itemManager;
		this.listener = listener;

		setLayout(new GridLayout(EquipmentLayout.SLOTS.length, EquipmentLayout.COLUMNS, GAP, GAP));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final int width = EquipmentLayout.COLUMNS * CELL + (EquipmentLayout.COLUMNS - 1) * GAP;
		final int height = EquipmentLayout.SLOTS.length * CELL + (EquipmentLayout.SLOTS.length - 1) * GAP;
		final Dimension size = new Dimension(width, height);
		setPreferredSize(size);
		setMinimumSize(size);
		setMaximumSize(size);

		for (EquipmentInventorySlot[] row : EquipmentLayout.SLOTS)
		{
			for (EquipmentInventorySlot slot : row)
			{
				add(cell(slot));
			}
		}
	}

	void setOptions(Map<Integer, List<FilterOptions.Option>> options)
	{
		this.options = options;
		render();
	}

	void setSelection(Map<Integer, Integer> selection)
	{
		this.selection = selection;
		render();
	}

	private JLabel cell(@Nullable EquipmentInventorySlot slot)
	{
		final JLabel label = new JLabel();
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setPreferredSize(new Dimension(CELL, CELL));
		label.setFont(FontManager.getRunescapeSmallFont());

		if (slot == null)
		{
			label.setOpaque(false);
			return label;
		}

		label.setOpaque(true);
		final int index = slot.getSlotIdx();
		cells.put(index, label);

		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showChoices(slot, label);
			}
		});

		return label;
	}

	private void render()
	{
		for (EquipmentInventorySlot[] row : EquipmentLayout.SLOTS)
		{
			for (EquipmentInventorySlot slot : row)
			{
				if (slot == null)
				{
					continue;
				}

				final int index = slot.getSlotIdx();
				final JLabel label = cells.get(index);
				if (label == null)
				{
					continue;
				}

				final boolean usable = options.containsKey(index);
				final Integer chosen = selection.get(index);

				label.setIcon(null);
				label.setText("");
				label.setBackground(usable ? EMPTY_SLOT : UNUSED_SLOT);
				label.setBorder(BorderFactory.createLineBorder(
					chosen != null ? ColorScheme.BRAND_ORANGE : SLOT_BORDER));
				label.setCursor(Cursor.getPredefinedCursor(usable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));

				if (chosen == null)
				{
					label.setToolTipText(usable
						? EquipmentLayout.name(slot) + ": any"
						: EquipmentLayout.name(slot) + ": nothing recorded");
					continue;
				}

				if (isNothing(chosen))
				{
					// An unfiltered slot is also drawn empty, so the pinned-to-nothing case needs
					// a mark of its own or the two read the same at a glance.
					label.setText(NOTHING_MARK);
					label.setForeground(ColorScheme.BRAND_ORANGE);
				}
				else
				{
					final AsyncBufferedImage image = itemManager.getImage(chosen);
					image.addTo(label);
				}
				label.setToolTipText(EquipmentLayout.name(slot) + ": " + labelFor(index, chosen));
			}
		}

		revalidate();
		repaint();
	}

	private String labelFor(int slot, int itemId)
	{
		for (FilterOptions.Option option : options.getOrDefault(slot, Collections.emptyList()))
		{
			if (option.getId() != null && option.getId() == itemId)
			{
				return option.getLabel();
			}
		}
		if (isNothing(itemId))
		{
			return slot == CombatContext.WEAPON_SLOT ? "Unarmed" : "Nothing";
		}
		return "item " + itemId;
	}

	/** The store writes {@link CombatContext#NO_ITEM} for an empty slot; be lenient about 0. */
	private static boolean isNothing(@Nullable Integer itemId)
	{
		return itemId != null && itemId <= 0;
	}

	private JMenuItem choice(int slot, FilterOptions.Option option)
	{
		final Integer itemId = option.getId();
		final JMenuItem item = new JMenuItem(option.getLabel() + "  (" + option.getAttacks() + ")");
		item.setFont(FontManager.getRunescapeSmallFont());
		if (!isNothing(itemId))
		{
			final AsyncBufferedImage image = itemManager.getImage(itemId, 1, false);
			item.setIcon(new ImageIcon(image));
			image.onLoaded(item::repaint);
		}
		item.addActionListener(e -> listener.slotChanged(slot, itemId));
		return item;
	}

	private void showChoices(EquipmentInventorySlot slot, JLabel anchor)
	{
		final int index = slot.getSlotIdx();
		final List<FilterOptions.Option> choices = options.get(index);
		if (choices == null || choices.isEmpty())
		{
			return;
		}

		final JPopupMenu popup = new JPopupMenu();
		popup.setFocusable(false);

		final JMenuItem any = new JMenuItem("Any " + EquipmentLayout.name(slot).toLowerCase());
		any.setFont(FontManager.getRunescapeSmallFont());
		any.setToolTipText("Do not filter on this slot");
		any.addActionListener(e -> listener.slotChanged(index, null));
		popup.add(any);

		// "Worn nothing here" is a choice like any other, but it is one attack among thousands in
		// most histories, so it is lifted out of the sorted list rather than left to be cut off by
		// the suggestion cap. It also sits next to "Any", which is the option it is confused with.
		final List<FilterOptions.Option> items = new ArrayList<>(choices.size());
		FilterOptions.Option nothing = null;
		for (FilterOptions.Option option : choices)
		{
			if (isNothing(option.getId()))
			{
				nothing = option;
			}
			else
			{
				items.add(option);
			}
		}

		if (nothing != null)
		{
			popup.add(choice(index, nothing));
		}

		popup.addSeparator();

		int shown = 0;
		for (FilterOptions.Option option : items)
		{
			if (shown++ >= MAX_SUGGESTIONS)
			{
				final JMenuItem more = new JMenuItem((items.size() - MAX_SUGGESTIONS) + " more not shown");
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setEnabled(false);
				popup.add(more);
				break;
			}
			popup.add(choice(index, option));
		}

		popup.show(anchor, 0, anchor.getHeight());
	}
}
