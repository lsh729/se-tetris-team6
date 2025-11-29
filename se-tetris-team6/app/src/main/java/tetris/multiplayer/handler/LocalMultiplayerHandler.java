package tetris.multiplayer.handler;

import java.util.Objects;
import java.util.function.Consumer;

import tetris.domain.GameModel;
import tetris.domain.GameModel.MultiplayerHook;
import tetris.domain.handler.GameHandler;
import tetris.domain.model.GameState;
import tetris.multiplayer.controller.MultiPlayerController;
import tetris.multiplayer.model.MultiPlayerGame;
import tetris.multiplayer.session.LocalMultiplayerSession;

/**
 * Local 2P 모드를 기존 {@link GameHandler} 상태 머신에 붙여주는 핸들러.
 * - GameModel에 멀티 훅을 등록해 락/스폰 이벤트를 자동 수신한다.
 * - update()에서 두 모델을 동시에 틱 처리하고 승패를 감지한다.
 */
public final class LocalMultiplayerHandler implements GameHandler {

    private final MultiPlayerGame game;
    private final MultiPlayerController controller;
    private final GameState state;
    private MultiplayerHook p1Hook;
    private MultiplayerHook p2Hook;

    public LocalMultiplayerHandler(MultiPlayerGame game,
                                   MultiPlayerController controller,
                                   GameState state) {
        this.game = Objects.requireNonNull(game, "game");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public GameState getState() {
        return state;
    }

    @Override
    public void enter(GameModel model) {
        game.player(1).setReady(false);
        game.player(2).setReady(false);
        registerHooks();
    }

    @Override
    public void update(GameModel model) {
        controller.tick();
        LocalMultiplayerSession session = model.getActiveLocalMultiplayerSession().orElse(null);
        // 시간제한 모드 처리
        if (session != null && session.isTimeLimitMode() && !game.isGameOver() && !session.hasTimeExpired()) {
            session.tickTimeLimit(System.currentTimeMillis());
            if (session.isTimeUp()) {
                handleTimeUp(model, session);
            }
        }
        // 개별 플레이어가 먼저 GAME_OVER가 되면 즉시 패배자로 표시한다.
        if (!game.isGameOver() && (session == null || !session.hasTimeExpired())) {
            if (game.modelOf(1).getCurrentState() == GameState.GAME_OVER) {
                System.out.println("[LOG][LocalMulti] Player1 reached GAME_OVER → mark loser");
                game.markLoser(1);
            } else if (game.modelOf(2).getCurrentState() == GameState.GAME_OVER) {
                System.out.println("[LOG][LocalMulti] Player2 reached GAME_OVER → mark loser");
                game.markLoser(2);
            }
        }

        if (game.isGameOver() && model.getCurrentState() != GameState.GAME_OVER) {
            int winnerId = game.getWinnerId() == null ? -1 : game.getWinnerId();
            System.out.printf("[LOG][LocalMulti] Game over detected, winner: %d%n", winnerId);
            // 두 플레이어 모델 모두 GAME_OVER 상태로 정지시킨다.
            controller.withPlayer(1, m -> m.changeState(GameState.GAME_OVER));
            controller.withPlayer(2, m -> m.changeState(GameState.GAME_OVER));
            model.changeState(GameState.GAME_OVER);
            model.showMultiplayerResult(winnerId);
        }
    }

    private void handleTimeUp(GameModel model, LocalMultiplayerSession session) {
        int p1Score = game.modelOf(1).getScore().getPoints();
        int p2Score = game.modelOf(2).getScore().getPoints();
        if (p1Score > p2Score) {
            game.markLoser(2);
        } else if (p2Score > p1Score) {
            game.markLoser(1);
        } else {
            controller.withPlayer(1, m -> m.changeState(GameState.GAME_OVER));
            controller.withPlayer(2, m -> m.changeState(GameState.GAME_OVER));
            model.changeState(GameState.GAME_OVER);
            model.showMultiplayerResult(-1);
            session.markTimeExpired();
            return;
        }
        session.markTimeExpired();
    }

    @Override
    public void exit(GameModel model) {
        unregisterHooks();
    }

    public void dispatchToPlayer(int playerId, Consumer<GameModel> action) {
        controller.withPlayer(playerId, action);
    }

    public int getPendingLines(int playerId) {
        return controller.getPendingLines(playerId);
    }

    private void registerHooks() {
        // GameModel 훅은 중복 등록을 피하기 위해 항상 기존 값을 제거하고 새로 등록한다.
        unregisterHooks();
        p1Hook = createHook(1);
        p2Hook = createHook(2);
        game.player(1).getModel().addMultiplayerHook(p1Hook);
        game.player(2).getModel().addMultiplayerHook(p2Hook);
    }

    private void unregisterHooks() {
        if (p1Hook != null) {
            game.player(1).getModel().removeMultiplayerHook(p1Hook);
            p1Hook = null;
        }
        if (p2Hook != null) {
            game.player(2).getModel().removeMultiplayerHook(p2Hook);
            p2Hook = null;
        }
    }

    private MultiplayerHook createHook(int playerId) {
        return new MultiplayerHook() {
            @Override
            public void onPieceLocked(tetris.multiplayer.model.LockedPieceSnapshot snapshot,
                    int[] clearedRows,
                    int boardWidth) {
                // 해당 플레이어가 줄을 삭제하면 VersusRules로 위임해 공격을 계산한다.
                controller.onPieceLocked(playerId, snapshot, clearedRows);
            }

            @Override
            public void beforeNextSpawn() {
                // 스폰 직전에 대기 중인 공격 줄을 주입한다.
                controller.injectAttackBeforeNextSpawn(playerId);
            }
        };
    }
}
