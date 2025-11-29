package tetris.multiplayer.session;

import java.util.Objects;

import tetris.domain.GameMode;
import tetris.domain.GameModel;
import tetris.multiplayer.controller.MultiPlayerController;
import tetris.multiplayer.handler.LocalMultiplayerHandler;
import tetris.multiplayer.model.MultiPlayerGame;
import tetris.multiplayer.model.PlayerState;

/**
 * 로컬 2P 세션에 필요한 참조(플레이어 모델, 컨트롤러, 핸들러)를 한 군데 묶어 둔 값 객체.
 * UI나 컨트롤러가 동일한 세션을 공유해야 하므로 불변 필드만 노출한다.
 */
public final class LocalMultiplayerSession {

    private final PlayerState player1;
    private final PlayerState player2;
    private final MultiPlayerGame game;
    private final MultiPlayerController controller;
    private final LocalMultiplayerHandler handler;
    private static final long DEFAULT_TIME_LIMIT_MS = 180_000L; // 3 minutes
    private long timeLimitMillis = DEFAULT_TIME_LIMIT_MS;
    private long remainingTimeMillis;
    private long lastTickMillis;
    private boolean timeExpired;
    private GameMode currentMode = GameMode.STANDARD;

    public LocalMultiplayerSession(PlayerState player1,
                                   PlayerState player2,
                                   MultiPlayerGame game,
                                   MultiPlayerController controller,
                                   LocalMultiplayerHandler handler) {
        this.player1 = Objects.requireNonNull(player1, "player1");
        this.player2 = Objects.requireNonNull(player2, "player2");
        this.game = Objects.requireNonNull(game, "game");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /** P1이 조작할 GameModel */
    public GameModel playerOneModel() {
        return player1.getModel();
    }

    /** P2가 조작할 GameModel */
    public GameModel playerTwoModel() {
        return player2.getModel();
    }

    /** 멀티플레이 상태 머신에 장착할 핸들러 */
    public LocalMultiplayerHandler handler() {
        return handler;
    }

    /** UI/디버깅용으로 컨트롤러를 꺼내고 싶을 때 사용 */
    public MultiPlayerController controller() {
        return controller;
    }

    public MultiPlayerGame game() {
        return game;
    }

    /**
     * 모든 플레이어 모델을 동일한 모드로 재시작한다.
     * - GameModel#startGame 이 내부 상태를 초기화하므로 별도 클리어 작업이 필요 없다.
     */
    public void restartPlayers(GameMode mode) {
        GameMode resolved = mode == null ? GameMode.STANDARD : mode;
        this.currentMode = resolved;
        resetTimeLimit();
        playerOneModel().startGame(resolved);
        playerTwoModel().startGame(resolved);
    }

    /**
     * 메뉴로 돌아갈 때 플레이어 모델도 MENU 상태로 돌려 놓는다.
     */
    public void shutdown() {
        playerOneModel().quitToMenu();
        playerTwoModel().quitToMenu();
    }

    /**
     * TIME_LIMIT 모드에서만 남은 시간을 감소시킨다.
     */
    public void tickTimeLimit(long nowMillis) {
        if (!isTimeLimitMode()) {
            return;
        }
        if (lastTickMillis == 0L) {
            lastTickMillis = nowMillis;
            return;
        }
        long delta = Math.max(0L, nowMillis - lastTickMillis);
        remainingTimeMillis -= delta;
        lastTickMillis = nowMillis;
    }

    public boolean isTimeUp() {
        return remainingTimeMillis <= 0L;
    }

    public long getRemainingTimeMillis() {
        return Math.max(0L, remainingTimeMillis);
    }

    public boolean isTimeLimitMode() {
        return currentMode == GameMode.TIME_LIMIT;
    }

    public void markTimeExpired() {
        this.timeExpired = true;
    }

    public boolean hasTimeExpired() {
        return timeExpired;
    }

    public GameMode getCurrentMode() {
        return currentMode;
    }

    private void resetTimeLimit() {
        timeExpired = false;
        if (isTimeLimitMode()) {
            remainingTimeMillis = timeLimitMillis;
            lastTickMillis = System.currentTimeMillis();
        } else {
            remainingTimeMillis = 0L;
            lastTickMillis = 0L;
        }
    }
}