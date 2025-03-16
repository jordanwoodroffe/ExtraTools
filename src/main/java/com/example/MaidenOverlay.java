package com.example;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Perspective;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public class MaidenOverlay extends Overlay
{
    private final Client client;
    private final ExamplePlugin plugin;

    @Inject
    public MaidenOverlay(Client client, ExamplePlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(100.0f); // Use floating-point priority
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        NPC maiden = plugin.getMaiden();
        int countdown = plugin.getMaidenCountdown();

        // Exclude 0 from the countdown
        if (maiden != null && countdown > 0)
        {
            LocalPoint localPoint = maiden.getLocalLocation();
            if (localPoint != null)
            {
                // Use the deprecated client.getPlane() method
                int plane = client.getPlane();

                // Convert LocalPoint to screen coordinates using Perspective
                net.runelite.api.Point apiPoint = Perspective.localToCanvas(client, localPoint, plane, maiden.getLogicalHeight() / 2);

                if (apiPoint != null)
                {
                    // Convert net.runelite.api.Point to java.awt.Point
                    Point screenPoint = new Point(apiPoint.getX() - 12, apiPoint.getY() + 85);

                    String text = String.valueOf(countdown);

                    // Set font to bold and larger size
                    graphics.setFont(new Font("Arial", Font.BOLD, 30));

                    // Draw shadow
                    graphics.setColor(new Color(0x000000)); // Black shadow
                    graphics.drawString(text, screenPoint.x + 2, screenPoint.y + 2); // Offset by 2 pixels

                    // Last tick before attack
                    if (countdown == 1)
                    {
                        graphics.setColor(new Color(0xFF00FA)); // #FF00FA
                    }
                    else
                    {
                        graphics.setColor(new Color(0x00FFFB)); // #00FFFB
                    }

                    // Draw main text
                    graphics.drawString(text, screenPoint.x, screenPoint.y);
                }
            }
        }
        return null;
    }
}