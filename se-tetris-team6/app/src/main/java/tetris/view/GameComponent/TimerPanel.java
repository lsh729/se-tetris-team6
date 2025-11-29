package tetris.view.GameComponent;

import java.awt.Font;
import java.awt.Color;
import java.lang.reflect.Method;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.SwingConstants;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.function.LongSupplier;

import tetris.domain.GameModel;

public class TimerPanel extends JPanel {
    private final JLabel label;
    private Timer refreshTimer;
    private GameModel gameModel;
    private Method timeGetter;
    private LongSupplier timeSupplier;

    public TimerPanel() {
        label = new JLabel("00:00");
        label.setFont(new Font("SansSerif", Font.BOLD, 16));
        // set text color to black
        label.setForeground(Color.BLACK);

        // Make the panel transparent and give the label its own light-gray
        // background with padding so only the area around the text is filled.
        this.setOpaque(false);

        this.setLayout(new BorderLayout());
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalAlignment(SwingConstants.CENTER);
        label.setBackground(Color.LIGHT_GRAY);
        label.setOpaque(true);
        // padding around the label so the gray forms a rounded rectangle-like area
        label.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        this.add(label, BorderLayout.CENTER);
    }

    /**
     * Bind a GameModel instance. The panel will attempt to read a time value
     * from the model using one of the following methods (in order):
     * - getElapsedMillis(), getElapsedMs(), getElapsedTimeMs(), getElapsedTime(),
     * - getCurrentTimeMs(), getCurrentTick(), getCurrentTickMillis()
     * If a method returning a long is found it will be used. The panel polls
     * the model periodically and updates the displayed time in MM:SS.sss format.
     */
    public void bindGameModel(GameModel gameModel) {
        stopRefresh();
        this.gameModel = gameModel;
        this.timeGetter = findTimeGetter(gameModel);
        this.timeSupplier = null;
        // start polling UI updates every 50ms
        refreshTimer = new Timer(50, e -> updateFromModel());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
        updateFromModel();
    }

    /**
     * Bind a time supplier (e.g., LocalMultiplayerSession#getRemainingTimeMillis) directly.
     */
    public void bindTimeSupplier(LongSupplier supplier) {
        stopRefresh();
        this.timeSupplier = supplier;
        this.gameModel = null;
        this.timeGetter = null;
        refreshTimer = new Timer(50, e -> updateFromModel());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
        updateFromModel();
    }

    private Method findTimeGetter(GameModel model) {
        if (model == null)
            return null;
        String[] candidates = { "getElapsedMillis", "getElapsedMs", "getElapsedTimeMs", "getElapsedTime",
                "getCurrentTimeMs", "getCurrentTick", "getCurrentTickMillis" };
        for (String name : candidates) {
            try {
                Method m = model.getClass().getMethod(name);
                if (m.getReturnType() == long.class || m.getReturnType() == Long.class) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (NoSuchMethodException ex) {
                // try next
            }
        }
        return null;
    }

    private void updateFromModel() {
        if (timeSupplier == null && gameModel == null)
            return;
        long millis = 0L;
        if (timeSupplier != null) {
            try {
                millis = timeSupplier.getAsLong();
            } catch (Exception ignored) {
            }
        } else {
            try {
                if (timeGetter != null) {
                    Object v = timeGetter.invoke(gameModel);
                    if (v instanceof Number) {
                        millis = ((Number) v).longValue();
                    }

                } else {
                    // fallback to getCurrentTick if available
                    try {
                        Method m = gameModel.getClass().getMethod("getCurrentTick");
                        Object v = m.invoke(gameModel);
                        if (v instanceof Number) {
                            millis = ((Number) v).longValue();
                        }
                    } catch (Exception ex) {
                        // ignore
                    }
                }
            } catch (Exception ex) {
                // reflection failed; ignore and keep millis 0
            }
        }

        final String txt = formatMillis(millis);
        SwingUtilities.invokeLater(() -> label.setText(txt));
    }

    private String formatMillis(long millis) {
        // Ensure non-negative
        if (millis < 0)
            millis = 0;
        long totalMillis = millis;
        long minutes = totalMillis / 60000L;
        long seconds = (totalMillis % 60000L) / 1000L;
        return String.format("%02d:%02d", minutes, seconds);
    }

    public void stopRefresh() {
        if (refreshTimer != null && refreshTimer.isRunning()) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }

}