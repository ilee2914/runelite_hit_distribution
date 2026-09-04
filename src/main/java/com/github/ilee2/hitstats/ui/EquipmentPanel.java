package com.github.ilee2.hitstats.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;

/**
 * The worn equipment of one recorded context, drawn as item icons in the same arrangement the
 * game's worn-equipment tab uses.
 */
class EquipmentPanel extends JPanel
{
	private static final int CELL = 32;
	private static final int GAP = 2;
	private static final Color EMPTY_SLOT = new Color(40, 40, 40);

	EquipmentPanel(ItemManager itemManager, int[] gear)
	{
		setLayout(new GridLayout(EquipmentLayout.SLOTS.length, EquipmentLayout.COLUMNS, GAP, GAP));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

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
				add(cell(itemManager, gear, slot));
			}
		}
	}

	private JLabel cell(ItemManager itemManager, int[] gear, @Nullable EquipmentInventorySlot slot)
	{
		final JLabel label = new JLabel();
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setVerticalAlignment(SwingConstants.CENTER);
		label.setPreferredSize(new Dimension(CELL, CELL));

		if (slot == null)
		{
			// A gap in the layout, not an empty slot: draw nothing at all.
			label.setOpaque(false);
			return label;
		}

		label.setOpaque(true);
		label.setBackground(EMPTY_SLOT);

		final int index = slot.getSlotIdx();
		final int itemId = index >= 0 && index < gear.length ? gear[index] : -1;
		if (itemId > 0)
		{
			// Loads from the item cache and repaints the label when it arrives.
			itemManager.getImage(itemId).addTo(label);
		}

		return label;
	}
}
