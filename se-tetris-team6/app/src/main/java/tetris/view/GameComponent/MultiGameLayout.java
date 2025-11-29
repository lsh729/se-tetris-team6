package tetris.view.GameComponent;

import java.awt.Color;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JPanel;

import tetris.domain.GameModel;
import tetris.multiplayer.session.LocalMultiplayerSession;

/**
 * 멀티 게임 UI 전용 레이아웃.
 * - 좌측(P1)/우측(P2) 보드와 다음 블록, 스코어, 공격 대기 줄을 각각 배치한다.
 * - TetrisFrame이 전달한 GameModel/LocalMultiplayerSession을 통해 실시간 상태를 그린다.
 */
public class MultiGameLayout extends JPanel {
        // 플레이어 1
        private GamePanel gamePanel_1;
        private NextBlockPanel nextBlockPanel_1;
        private ScorePanel scoreboard_1;
        private AttackQueuePanel attackQueuePanel_1;

        // 플레이어 2
        private GamePanel gamePanel_2;
        private NextBlockPanel nextBlockPanel_2;
        private ScorePanel scoreboard_2;
        private AttackQueuePanel attackQueuePanel_2;

        // 중앙 Timer
        private TimerPanel timerPanel;

        public MultiGameLayout() {
                super(new GridBagLayout());
                setOpaque(true);
                setVisible(false);

                // 각 요소 객체 생성 및 배치
                gamePanel_1 = new GamePanel();
                addToRegion(gamePanel_1, 0, 0, 6, 11, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
                nextBlockPanel_1 = new NextBlockPanel();
                addToRegion(nextBlockPanel_1, 6, 0, 2, 3, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
                scoreboard_1 = new ScorePanel();
                addToRegion(scoreboard_1, 6, 3, 2, 1, GridBagConstraints.BOTH, GridBagConstraints.CENTER);      
                attackQueuePanel_1 = new AttackQueuePanel();
                addToRegion(attackQueuePanel_1, 6, 5, 2, 6, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
                
                gamePanel_2 = new GamePanel();
                addToRegion(gamePanel_2, 11, 0, 6, 11, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
                nextBlockPanel_2 = new NextBlockPanel();        
                addToRegion(nextBlockPanel_2, 8, 0, 2, 3, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
                scoreboard_2 = new ScorePanel();
                addToRegion(scoreboard_2, 8, 3, 2, 1, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
                attackQueuePanel_2 = new AttackQueuePanel();
                addToRegion(attackQueuePanel_2, 8, 5, 2, 6, GridBagConstraints.BOTH, GridBagConstraints.CENTER);

                timerPanel = new TimerPanel();
                addToRegion(timerPanel, 6, 4, 4, 1, GridBagConstraints.BOTH, GridBagConstraints.CENTER);

                revalidate();
                repaint();
        }

        /**
         * Show the central timer panel.
         */
        public void showTimer() {
                setTimerVisible(true);
        }

        /**
         * Hide the central timer panel.
         */
        public void hideTimer() {
                setTimerVisible(false);
        }

        /**
         * Set visibility of the central timer panel.
         */
        public void setTimerVisible(boolean visible) {
                if (timerPanel == null)
                        return;
                timerPanel.setVisible(visible);
                revalidate();
                repaint();
        }

        /**
         * 싱글 플레이어 모델 한 개로 좌/우 모두를 채운다.
         * - 로컬 멀티가 비활성화된 상태에서 미리보기 용도로 사용된다.
         */
        public void bindGameModel(GameModel model) {
                bindPlayerModels(model, model);
                attackQueuePanel_1.bindGameModel(model);
                attackQueuePanel_2.bindGameModel(model);
                repaint();
        }

        /**
         * 로컬 멀티 세션에서 각 패널이 P1/P2 모델을 따로 그리도록 연결한다.
         * - 공격 대기 줄은 LocalMultiplayerHandler#getPendingLines 공급자를 통해 실시간으로 갱신한다.
         */
        public void bindLocalMultiplayerSession(LocalMultiplayerSession session) {
                if (session == null) {
                        return;
                }
                bindPlayerModels(session.playerOneModel(), session.playerTwoModel());
                // 각 패널이 해당 플레이어의 쓰레기 줄 대기량을 바로 읽어오도록 공급자를 연결한다.
                attackQueuePanel_1.bindPendingLinesSupplier(() -> session.handler().getPendingLines(1));
                attackQueuePanel_2.bindPendingLinesSupplier(() -> session.handler().getPendingLines(2));
                if (session.isTimeLimitMode()) {
                        timerPanel.bindTimeSupplier(session::getRemainingTimeMillis);
                        showTimer();
                } else {
                        timerPanel.bindGameModel(session.playerOneModel());
                        hideTimer();
                }
                repaint();
        }

        private void bindPlayerModels(GameModel playerOne, GameModel playerTwo) {
                // 좌측 UI는 P1 모델, 우측 UI는 P2 모델을 그대로 바라보도록 분리한다.
                gamePanel_1.bindGameModel(playerOne);
                gamePanel_2.bindGameModel(playerTwo);
                nextBlockPanel_1.bindGameModel(playerOne);
                nextBlockPanel_2.bindGameModel(playerTwo);
                scoreboard_1.bindGameModel(playerOne);
                scoreboard_2.bindGameModel(playerTwo);
                // 중앙 타이머 패널은 P1 기준으로 공유(추후 필요 시 P2 전용 UI를 추가할 수 있다).
                timerPanel.bindGameModel(playerOne);
        }

        public void addToRegion(Component comp, int x, int y, int w, int h, int fill, int anchor) {
                GridBagConstraints gbc = new GridBagConstraints();
                gbc.gridx = x;
                gbc.gridy = y;
                gbc.gridwidth = w;
                gbc.gridheight = h;

                gbc.weightx = gbc.gridwidth;
                gbc.weighty = gbc.gridheight;


                gbc.fill = fill;
                gbc.anchor = anchor;
                gbc.insets = new Insets(12, 12, 12, 12);
                gbc.ipadx = 5;
                gbc.ipady = 5;

                comp.setVisible(true);
                this.add(comp, gbc);
        }
}