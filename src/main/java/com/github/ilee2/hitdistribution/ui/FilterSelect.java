package com.github.ilee2.hitdistribution.ui;

import com.github.ilee2.hitdistribution.FilterOptions;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * A search box that doubles as a dropdown: type to narrow the list, or open it with the arrow to
 * see everything recorded. Picking an entry sets the filter; the clear button drops back to "all".
 *
 * <p>Same shape as the search in the GE Helper plugin -- an {@link IconTextField} with a
 * {@link JPopupMenu} of suggestions under it, driven by the keyboard as well as the mouse.
 */
class FilterSelect extends JPanel
{
	/** Longer lists are truncated rather than filling the screen; typing narrows them. */
	private static final int MAX_SUGGESTIONS = 15;

	private static final int HEIGHT = 24;

	private final IconTextField field = new IconTextField();
	private final JPopupMenu popup = new JPopupMenu();
	private final String allLabel;
	private final Runnable onChange;

	/** Only set for lists whose ids are item ids, so the suggestions can carry item icons. */
	@Nullable
	private final ItemManager itemManager;

	private List<FilterOptions.Option> options = Collections.emptyList();

	/** null means no filter on this dimension. */
	@Nullable
	private FilterOptions.Option selected;

	private int highlighted = -1;

	/** Set while the field's text is being written programmatically rather than typed. */
	private boolean suppressSuggestions;

	/** What the open popup was built from: the typed text, or "" when the arrow listed everything. */
	private String lastQuery = "";

	FilterSelect(String allLabel, @Nullable ItemManager itemManager, Runnable onChange)
	{
		this.allLabel = allLabel;
		this.itemManager = itemManager;
		this.onChange = onChange;

		setLayout(new BorderLayout(2, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
		setPreferredSize(new Dimension(100, HEIGHT));

		field.setIcon(IconTextField.Icon.SEARCH);
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		field.setPreferredSize(new Dimension(100, HEIGHT));
		field.setMinimumSize(new Dimension(0, HEIGHT));
		field.setToolTipText("Type to search, or use the arrow to list everything");
		add(field, BorderLayout.CENTER);

		add(arrow(), BorderLayout.EAST);

		popup.setFocusable(false);

		field.addClearListener(() ->
		{
			setFieldText("");
			popup.setVisible(false);
			if (selected != null)
			{
				selected = null;
				onChange.run();
			}
		});

		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				showSuggestions();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				showSuggestions();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				showSuggestions();
			}
		});

		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				onKey(e);
			}
		});
	}

	// ------------------------------------------------------------------ state

	/**
	 * Replaces the choices offered. The current selection is deliberately left alone even when it
	 * is no longer among them: it can drop out because of the other filters, and silently
	 * resetting it would change the numbers on screen without the user asking.
	 */
	void setOptions(List<FilterOptions.Option> options)
	{
		this.options = options;

		// The panel refreshes every couple of seconds while fighting. A list left open across
		// that would keep showing the counts from before the refresh.
		if (popup.isVisible())
		{
			buildPopup(lastQuery);
		}
	}

	@Nullable
	FilterOptions.Option getSelected()
	{
		return selected;
	}

	/** Shows a selection made elsewhere, without calling back. */
	void setSelected(@Nullable FilterOptions.Option option)
	{
		selected = option;
		setFieldText(option == null ? "" : option.getLabel());
		popup.setVisible(false);
	}

	/** Drops back to "all" without notifying; the caller refreshes once for all of its boxes. */
	void clear()
	{
		selected = null;
		setFieldText("");
		popup.setVisible(false);
	}

	private void select(@Nullable FilterOptions.Option option)
	{
		selected = option;
		setFieldText(option == null ? "" : option.getLabel());
		popup.setVisible(false);
		highlighted = -1;
		onChange.run();
	}

	// -------------------------------------------------------------------- ui

	private JLabel arrow()
	{
		// Down-pointing triangle, as an escape so the source stays pure ASCII.
		final JLabel label = new JLabel("\u25be");
		label.setFont(FontManager.getDefaultFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.setToolTipText("Show everything recorded");
		label.setBorder(new EmptyBorder(0, 2, 0, 4));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (popup.isVisible())
				{
					popup.setVisible(false);
					return;
				}
				// The arrow lists everything, whatever is typed in the box.
				buildPopup("");
			}
		});
		return label;
	}

	private void onKey(KeyEvent e)
	{
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
		{
			popup.setVisible(false);
			return;
		}

		// Arrow keys drive the suggestions; without consuming them the sidebar's scroll pane
		// takes them as well and the whole panel jumps.
		if (e.getKeyCode() == KeyEvent.VK_DOWN && !popup.isVisible())
		{
			buildPopup(field.getText());
			e.consume();
			return;
		}

		if (!popup.isVisible())
		{
			return;
		}

		final int count = popup.getComponentCount();
		if (count == 0)
		{
			return;
		}

		if (e.getKeyCode() == KeyEvent.VK_DOWN)
		{
			highlighted = highlighted + 1 >= count ? 0 : highlighted + 1;
			highlight();
			e.consume();
		}
		else if (e.getKeyCode() == KeyEvent.VK_UP)
		{
			highlighted = highlighted - 1 < 0 ? count - 1 : highlighted - 1;
			highlight();
			e.consume();
		}
		else if (e.getKeyCode() == KeyEvent.VK_ENTER)
		{
			final int index = highlighted >= 0 ? highlighted : 0;
			final java.awt.Component item = popup.getComponent(index);
			if (item instanceof JMenuItem && item.isEnabled())
			{
				((JMenuItem) item).doClick();
				e.consume();
			}
		}
	}

	private void highlight()
	{
		if (highlighted < 0 || highlighted >= popup.getComponentCount())
		{
			return;
		}
		final java.awt.Component item = popup.getComponent(highlighted);
		if (!(item instanceof MenuElement))
		{
			return;
		}
		MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{popup, (MenuElement) item});
	}

	private void setFieldText(String text)
	{
		suppressSuggestions = true;
		try
		{
			field.setText(text);
		}
		finally
		{
			suppressSuggestions = false;
		}
	}

	private void showSuggestions()
	{
		if (suppressSuggestions)
		{
			return;
		}
		buildPopup(field.getText());
	}

	private void buildPopup(@Nullable String query)
	{
		final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		lastQuery = needle;

		popup.removeAll();
		highlighted = -1;

		final JMenuItem all = new JMenuItem(allLabel);
		all.setFont(FontManager.getRunescapeSmallFont());
		all.addActionListener(e -> select(null));
		popup.add(all);

		int shown = 0;
		int matched = 0;
		for (FilterOptions.Option option : options)
		{
			if (!needle.isEmpty() && !option.getLabel().toLowerCase(Locale.ROOT).contains(needle))
			{
				continue;
			}

			matched++;
			if (shown >= MAX_SUGGESTIONS)
			{
				continue;
			}
			shown++;

			final JMenuItem item = new JMenuItem(option.getLabel() + "  (" + option.getAttacks() + ")");
			item.setFont(FontManager.getRunescapeSmallFont());
			icon(option, item);
			item.addActionListener(e -> select(option));
			popup.add(item);
		}

		if (matched > shown)
		{
			final JMenuItem more = new JMenuItem((matched - shown) + " more, keep typing");
			more.setFont(FontManager.getRunescapeSmallFont());
			more.setEnabled(false);
			popup.add(more);
		}

		if (!field.isShowing())
		{
			return;
		}

		popup.pack();
		if (popup.isVisible())
		{
			popup.revalidate();
			popup.repaint();
		}
		else
		{
			popup.show(field, 0, field.getHeight());
		}
	}

	private void icon(FilterOptions.Option option, JMenuItem item)
	{
		final Integer id = option.getId();
		if (itemManager == null || id == null || id <= 0)
		{
			return;
		}

		final AsyncBufferedImage image = itemManager.getImage(id, 1, false);
		item.setIcon(new ImageIcon(image));
		image.onLoaded(item::repaint);
	}
}
