package com.pvmkits;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.List;

public class PvmKitsPanel extends PluginPanel {

    private static final Color ACTIVE_TAB_COLOR = new Color(200, 83, 0);

    private static final List<String[]> BOSS_SECTIONS = Arrays.<String[]>asList(
        new String[]{"Yama", "Phase highlighting, attack timers, glyph highlights, fireball safe tiles"},
        new String[]{"Phosani's & The Nightmare", "Phase highlighting, timers, parasite outline, surge path, totem and spore highlights"},
        new String[]{"Theatre of Blood", "Verzik attack style overlay, P2/P3/Xarpus timers, Sotetseg death ball tick eat"},
        new String[]{"Maggot King", "Attack style overlay, larvae highlight, screech prayer warning"},
        new String[]{"Chambers of Xeric", "Olm attack style, Tekton flinch timer, crystal bomb, shaman spit, Vasa boulder"},
        new String[]{"Doom of Mokhaiotl", "Prayer highlight, larvae, boulder tiles, shield/punish tiles, car phase, slam danger area, shockwave timer"}
    );

    private static final List<String[]> UTILITY_SECTIONS = Arrays.<String[]>asList(
        new String[]{"Prayer Flicking", "Tick-synced heartbeat cue above the prayer orb for prayer flicking, plus a locator orb Redemption click counter"}
    );

    private final JPanel contentPanel;

    public PvmKitsPanel() {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel tabBar = new JPanel(new GridLayout(1, 2, 2, 0));
        tabBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        tabBar.setBorder(new EmptyBorder(6, 6, 6, 6));

        JToggleButton bossesBtn = makeTabButton("Bosses");
        JToggleButton utilitiesBtn = makeTabButton("Utilities");

        ButtonGroup group = new ButtonGroup();
        group.add(bossesBtn);
        group.add(utilitiesBtn);

        tabBar.add(bossesBtn);
        tabBar.add(utilitiesBtn);
        add(tabBar, BorderLayout.NORTH);

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(new EmptyBorder(4, 0, 0, 0));

        JScrollPane scroll = new JScrollPane(contentPanel);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        add(scroll, BorderLayout.CENTER);

        bossesBtn.setSelected(true);
        showSections(BOSS_SECTIONS);

        bossesBtn.addActionListener(e -> showSections(BOSS_SECTIONS));
        utilitiesBtn.addActionListener(e -> showSections(UTILITY_SECTIONS));
    }

    private JToggleButton makeTabButton(String text) {
        JToggleButton btn = new JToggleButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(isSelected() ? ACTIVE_TAB_COLOR : ColorScheme.DARKER_GRAY_COLOR);
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(true);
        btn.setForeground(Color.WHITE);
        btn.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 13f));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void showSections(List<String[]> sections) {
        contentPanel.removeAll();
        for (String[] section : sections) {
            contentPanel.add(makeSectionCard(section[0], section[1]));
            contentPanel.add(Box.createRigidArea(new Dimension(0, 3)));
        }
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private JPanel makeSectionCard(String title, String description) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);

        JLabel descLabel = new JLabel("<html><body style='width: 200px'>" + description + "</body></html>");
        descLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        descLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(11f));
        descLabel.setAlignmentX(LEFT_ALIGNMENT);
        descLabel.setBorder(new EmptyBorder(3, 0, 0, 0));

        card.add(titleLabel);
        card.add(descLabel);
        return card;
    }
}
