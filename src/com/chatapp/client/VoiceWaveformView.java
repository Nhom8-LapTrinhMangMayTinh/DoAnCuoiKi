package com.chatapp.client;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Sóng âm nhỏ dùng chung cho bong bóng tin thoại và cửa sổ ghi âm. */
final class VoiceWaveformView extends JComponent {
    private static final int[] SHAPE = {
        8, 12, 16, 10, 19, 23, 15, 12, 20, 14, 17, 25, 18, 12, 9, 14,
        18, 11, 6, 5, 4, 7, 5, 9, 12, 8, 5, 4, 6, 11, 16, 21, 17, 12,
        19, 24, 21, 14, 9, 13, 17, 22, 16, 10, 7, 12, 18, 14, 9, 6, 4, 7
    };
    private static final Color QUIET = new Color(190, 200, 214);
    private static final Color PLAYED = new Color(21, 65, 154);
    private static final Color LIVE = new Color(37, 99, 235);

    private final int[] levels = new int[SHAPE.length];
    private boolean liveMode;
    private double progress;
    private int latestLevel;

    VoiceWaveformView() {
        setOpaque(false);
        setPreferredSize(new Dimension(220, 39));
        setMinimumSize(new Dimension(95, 34));
        setToolTipText("Dạng sóng âm thanh");
    }

    static VoiceWaveformView liveMeter() {
        VoiceWaveformView view = new VoiceWaveformView();
        view.liveMode = true;
        return view;
    }

    void setProgress(double value) {
        progress = Math.max(0.0, Math.min(1.0, value));
        repaint();
    }

    void pushLevel(int value) {
        latestLevel = Math.max(0, Math.min(100, value));
        System.arraycopy(levels, 1, levels, 0, levels.length - 1);
        levels[levels.length - 1] = latestLevel;
        repaint();
    }

    int getLatestLevel() { return latestLevel; }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int width = getWidth();
        int height = getHeight();
        if (width <= 4 || height <= 4) {
            g.dispose();
            return;
        }
        int count = Math.max(12, Math.min(SHAPE.length, width / 4));
        double gap = (double) width / (count + 1);
        int barWidth = Math.max(2, Math.min(4, (int) Math.floor(gap * 0.52)));
        double maxBarHeight = Math.max(14.0, Math.min(30.0, height - 5.0));

        // Marker at the played position, matching the compact voice-player style.
        int markerX = (int) Math.round(progress * Math.max(0, width - 3));
        if (!liveMode && progress <= 0.0001) markerX = 1;

        for (int i = 0; i < count; i++) {
            int sourceIndex = count <= 1 ? 0 : (int) Math.round(i * (SHAPE.length - 1.0) / (count - 1.0));
            int amplitude;
            if (liveMode) {
                amplitude = levels[sourceIndex];
                // When silence is detected, keep the signal visible but low.
                if (amplitude == 0) amplitude = 5 + (SHAPE[sourceIndex] % 5);
                amplitude = Math.min(100, amplitude);
            } else {
                amplitude = SHAPE[sourceIndex];
            }
            double normalized = liveMode ? (0.12 + amplitude / 115.0) : (0.13 + amplitude / 29.0);
            int barHeight = (int) Math.round(Math.min(maxBarHeight, maxBarHeight * normalized));
            barHeight = Math.max(3, Math.min((int) maxBarHeight, barHeight));
            int x = (int) Math.round((i + 1) * gap - barWidth / 2.0);
            int y = (height - barHeight) / 2;

            if (liveMode) {
                int threshold = (int) Math.round((i + 1) * 100.0 / (count + 1));
                g.setColor(threshold >= 100 - Math.min(100, latestLevel) ? LIVE : new Color(123, 164, 224));
            } else {
                g.setColor(x + barWidth / 2 <= markerX ? PLAYED : QUIET);
            }
            g.fillRoundRect(x, y, barWidth, barHeight, barWidth, barWidth);
        }
        if (!liveMode) {
            g.setColor(PLAYED);
            g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(markerX, Math.max(2, height / 2 - 14), markerX, Math.min(height - 2, height / 2 + 14));
        }
        g.dispose();
    }
}
