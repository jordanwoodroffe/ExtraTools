package com.pvmkits.bosses.verzik;

import com.pvmkits.PvmKitsConfig;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.*;

import javax.inject.Inject;
import java.awt.*;

public class VerzikOverlay extends Overlay {

    private final Client client;
    private final VerzikHandler verzikHandler;
    private final PvmKitsConfig config;

    @Inject
    public VerzikOverlay(Client client, VerzikHandler verzikHandler, PvmKitsConfig config) {
        this.client = client;
        this.verzikHandler = verzikHandler;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.enableVerzik()) {
            return null;
        }

        NPC verzikNpc = getVerzikNpc();
        if (verzikNpc == null) {
            return null;
        }

        // Debug: Log when we find Verzik and render overlay
        // Uncomment these lines for debugging:
        VerzikHandler.VerzikAttackStyle attackStyle = verzikHandler.getCurrentAttackStyle();
        boolean timerActive = verzikHandler.isTimerActive();
        int timer = verzikHandler.getAttackTimer();
        System.out.println("Verzik Overlay Debug - NPC: " + verzikNpc.getId() +
                ", AttackStyle: " + attackStyle +
                ", TimerActive: " + timerActive +
                ", Timer: " + timer);

        // Render attack style tile coloring (like Yama) - always show when Verzik is
        // enabled
        renderAttackStyleTileColoring(graphics, verzikNpc);

        // Render attack timer
        if (config.showVerzikTimer() && verzikHandler.isTimerActive()) {
            renderAttackTimer(graphics, verzikNpc);
        }

        return null;
    }

    private void renderAttackStyleTileColoring(Graphics2D graphics, NPC verzikNpc) {
        VerzikHandler.VerzikAttackStyle attackStyle = verzikHandler.getCurrentAttackStyle();
        Color attackColor = getAttackStyleColor(attackStyle);

        if (attackColor == null) {
            return;
        }

        // Get base tile location of the NPC
        LocalPoint basePoint = verzikNpc.getLocalLocation();
        if (basePoint == null) {
            return;
        }

        // Use 7x7 tile size for P3 Verzik (NPC ID 8374)
        int size = 7;

        // Calculate the southwest corner of the 7x7 area (same approach as YamaOverlay)
        int swX = basePoint.getX() - (Perspective.LOCAL_TILE_SIZE * (size - 1) / 2);
        int swY = basePoint.getY() - (Perspective.LOCAL_TILE_SIZE * (size - 1) / 2);

        // Calculate the northeast corner of the 7x7 area
        int neX = swX + ((size - 1) * Perspective.LOCAL_TILE_SIZE);
        int neY = swY + ((size - 1) * Perspective.LOCAL_TILE_SIZE);

        // Create LocalPoints for the four corners of the 7x7 area
        LocalPoint swPoint = new LocalPoint(swX, swY);
        LocalPoint sePoint = new LocalPoint(neX, swY);
        LocalPoint nePoint = new LocalPoint(neX, neY);
        LocalPoint nwPoint = new LocalPoint(swX, neY);

        // Get the polygons for each corner tile
        Polygon swPoly = Perspective.getCanvasTilePoly(client, swPoint);
        Polygon sePoly = Perspective.getCanvasTilePoly(client, sePoint);
        Polygon nePoly = Perspective.getCanvasTilePoly(client, nePoint);
        Polygon nwPoly = Perspective.getCanvasTilePoly(client, nwPoint);

        if (swPoly == null || sePoly == null || nePoly == null || nwPoly == null) {
            return;
        }

        // Create a consolidated area polygon
        Polygon borderPoly = new Polygon();

        // Add the outer points of the 7x7 area to create the border
        addPointsToPolygon(borderPoly, swPoly, 0, 1);
        addPointsToPolygon(borderPoly, sePoly, 1, 2);
        addPointsToPolygon(borderPoly, nePoly, 2, 3);
        addPointsToPolygon(borderPoly, nwPoly, 3, 0);

        // Fill with attack style color
        graphics.setColor(attackColor);
        graphics.fill(borderPoly);

        // Draw border with solid color
        graphics.setColor(new Color(attackColor.getRed(), attackColor.getGreen(), attackColor.getBlue(), 255));
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(borderPoly);
    }

    private void renderAttackTimer(Graphics2D graphics, NPC verzikNpc) {
        int timer = verzikHandler.getAttackTimer();
        if (timer <= 0) {
            return;
        }

        // Get center point of the NPC for text positioning (same as YamaOverlay)
        LocalPoint center = verzikNpc.getLocalLocation();
        if (center == null) {
            return;
        }

        net.runelite.api.Point textPoint = Perspective.localToCanvas(client, center, 0);
        if (textPoint == null) {
            return;
        }

        // Set text properties
        String timerText = String.valueOf(timer);
        Font font = new Font("Arial", Font.BOLD, config.verzikTimerSize());
        graphics.setFont(font);

        FontMetrics metrics = graphics.getFontMetrics();
        int textWidth = metrics.stringWidth(timerText);
        int textHeight = metrics.getHeight();
        int textX = textPoint.getX() - (textWidth / 2);
        int textY = textPoint.getY() + (textHeight / 4); // Center vertically

        // Draw outline for visibility (same as YamaOverlay)
        graphics.setColor(Color.BLACK);
        graphics.drawString(timerText, textX - 2, textY - 2);
        graphics.drawString(timerText, textX + 2, textY - 2);
        graphics.drawString(timerText, textX - 2, textY + 2);
        graphics.drawString(timerText, textX + 2, textY + 2);
        graphics.drawString(timerText, textX - 2, textY);
        graphics.drawString(timerText, textX + 2, textY);
        graphics.drawString(timerText, textX, textY - 2);
        graphics.drawString(timerText, textX, textY + 2);

        // Draw main text - use warning color for low timer
        Color textColor = timer <= 2 ? config.verzikWarningColor() : config.verzikTimerColor();
        graphics.setColor(textColor);
        graphics.drawString(timerText, textX, textY);
    }

    private NPC getVerzikNpc() {
        for (NPC npc : client.getTopLevelWorldView().npcs()) {
            if (npc != null && isVerzikNpc(npc.getId())) {
                return npc;
            }
        }
        return null;
    }

    private boolean isVerzikNpc(int npcId) {
        // Only show overlay for NPC ID 8374 as requested
        return npcId == 8374; // P3 Verzik only
    }

    private Color getAttackStyleColor(VerzikHandler.VerzikAttackStyle attackStyle) {
        if (attackStyle == null) {
            return new Color(128, 128, 128, 80); // Light gray for null
        }

        // Use the color from the enum directly
        return attackStyle.getColor();
    }

    // Helper method to add points from one polygon to another (same as YamaOverlay)
    private void addPointsToPolygon(Polygon targetPoly, Polygon sourcePoly, int startIdx, int endIdx) {
        int sourcePoints = sourcePoly.npoints;
        if (startIdx >= sourcePoints || endIdx >= sourcePoints) {
            return;
        }

        targetPoly.addPoint(sourcePoly.xpoints[startIdx], sourcePoly.ypoints[startIdx]);
        targetPoly.addPoint(sourcePoly.xpoints[endIdx], sourcePoly.ypoints[endIdx]);
    }
}
