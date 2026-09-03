package com.github.ilee2.hitdistribution.ui;

import javax.annotation.Nullable;
import net.runelite.api.EquipmentInventorySlot;

/** The game's worn-equipment arrangement, shared by the read-only view and the filter. */
final class EquipmentLayout
{
	static final int COLUMNS = 3;

	/**
	 * Arms, hair and jaw are real slots but hold nothing a player equips, so they are left out
	 * rather than drawn as permanently empty boxes.
	 */
	static final EquipmentInventorySlot[][] SLOTS = {
		{null, EquipmentInventorySlot.HEAD, null},
		{EquipmentInventorySlot.CAPE, EquipmentInventorySlot.AMULET, EquipmentInventorySlot.AMMO},
		{EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.BODY, EquipmentInventorySlot.SHIELD},
		{null, EquipmentInventorySlot.LEGS, null},
		{EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS, EquipmentInventorySlot.RING},
	};

	private EquipmentLayout()
	{
	}

	static String name(@Nullable EquipmentInventorySlot slot)
	{
		if (slot == null)
		{
			return "";
		}

		switch (slot)
		{
			case HEAD:
				return "Head";
			case CAPE:
				return "Cape";
			case AMULET:
				return "Amulet";
			case WEAPON:
				return "Weapon";
			case BODY:
				return "Body";
			case SHIELD:
				return "Shield";
			case LEGS:
				return "Legs";
			case GLOVES:
				return "Gloves";
			case BOOTS:
				return "Boots";
			case RING:
				return "Ring";
			case AMMO:
				return "Ammo";
			default:
				return slot.name();
		}
	}
}
