package tetris.concurrent;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Queue;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tetris.network.protocol.GameMessage;
import tetris.network.protocol.MessageType;
import tetris.network.INetworkThreadCallback;

// =================================================================
// NetworkThread 구현 시작
// =================================================================

/**
 * 네트워크 통신을 담당하는 전용 스레드
 * 실제 소켓 I/O를 처리하며, GameThread와 독립적으로 동작합니다.
 */
public class NetworkThread implements Runnable {
    // === 상수 설정 (NetworkProtocol.java 참조) ===
    private static final long GAME_SYNC_INTERVAL = 50; // 50ms마다 동기화 시도
    private static final long PING_INTERVAL = 1000;    // 1초마다 핑
    private static final long MAX_LAG_THRESHOLD = 200; // 200ms 이상이면 랙 경고
    private static final int MAX_RETRY_COUNT = 3;     // 최대 재연결 시도 횟수
    private static final long RECONNECT_DELAY = 1000;  // 1초 후 재시도
    private static final int DEFAULT_PORT = 55555;     // 예시 포트

    // === 네트워크 관리 ===
    private final INetworkThreadCallback callback;     // 콜백 인터페이스 참조
    private final AtomicBoolean isRunning = new AtomicBoolean(true); // 스레드 실행 상태
    private final AtomicBoolean isConnected = new AtomicBoolean(false); // 네트워크 연결 상태

    // === 소켓 I/O 객체 (추가) ===
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private Thread readerThread; // 전용 수신 스레드

    // === 메시지 큐 관리 ===
    private final BlockingQueue<GameMessage> outgoingQueue = new LinkedBlockingQueue<>();    // 송신 대기 메시지
    private final BlockingQueue<GameMessage> incomingQueue = new LinkedBlockingQueue<>();    // 수신된 메시지 (GameThread가 가져감)
    private final BlockingQueue<GameMessage> priorityQueue = new LinkedBlockingQueue<>();    // 우선순위 메시지 (핑, 에러 등)

    // === 동기화 관리 ===
    private long lastSyncTime;                          // 마지막 동기화 시간
    private final long syncInterval = GAME_SYNC_INTERVAL; // 동기화 간격

    // === 지연시간 관리 ===
    private volatile long currentLatency = 0;               // 현재 지연시간
    private final Queue<Long> latencyHistory = new LinkedList<>(); // 지연시간 히스토리
    private long lastPingTime;                 // 마지막 핑 시간

    // === 재연결 관리 ===
    private int reconnectAttempts = 0;             // 재연결 시도 횟수
    private long lastReconnectTime;            // 마지막 재연결 시도 시간


    // === 주요 메서드들 ===

    // 접속 대상 호스트/포트 설정을 허용하는 생성자
    private final String host;
    private final int port;

    // 생성자
    public NetworkThread(INetworkThreadCallback callback, String host, int port) {
        this.callback = callback;
        this.host = host == null ? "localhost" : host;
        this.port = port <= 0 ? DEFAULT_PORT : port;
        this.lastPingTime = System.currentTimeMillis();
        this.lastSyncTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        System.out.println("NetworkThread 시작됨.");
        attemptConnection();

        while (isRunning.get()) {
            if (isConnected.get()) {
                // 1. 우선순위 메시지 처리 (핑, 퐁)
                processPriorityMessages();
                // 2. 송신 큐 처리 (네트워크로 전송)
                processSendQueue();
                // 3. 수신 큐 처리 (콜백으로 전달)
                processReceiveQueue();
                // 4. 연결 모니터링 (지연시간 측정)
                monitorConnection();
                // 5. 게임 상태 동기화 (주기적)
                synchronizeGameState();

            } else {
                attemptReconnection();
            }

            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                isRunning.set(false);
            }
        }
        cleanup();
        System.out.println("NetworkThread 종료됨.");
    }

    // 초기 연결 시도 (클라이언트 역할 가정)
    private void attemptConnection() {
        try {
            System.out.println("서버 연결 시도 중... host=" + host + " port=" + port);

            // 실제 소켓 연결 (호스트/포트 사용)
            socket = new Socket(host, port);

            // 출력 스트림은 입력 스트림보다 먼저 초기화되어야 함 (Java Object Stream 특성)
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            inputStream = new ObjectInputStream(socket.getInputStream());

            isConnected.set(true);

            // 메시지 수신 전담 스레드 시작
            NetworkReader reader = new NetworkReader();
            readerThread = new Thread(reader, "Network-Reader");
            readerThread.start();

            onConnectionEstablished();
        } catch (IOException e) {
            System.err.println("연결 실패: " + e.getMessage());
            isConnected.set(false);
        }
    }

    // 송신 메시지 처리 - 큐에서 메시지를 가져와 전송
    private void processSendQueue() {
        GameMessage message;
        while ((message = outgoingQueue.poll()) != null) {
            try {
                if (outputStream != null) {
                    // 실제 소켓 outputStream.writeObject(message) 호출
                    outputStream.writeObject(message);
                    outputStream.flush();
                    System.out.println("NetThread SENT: " + message.getType());
                }
            } catch (IOException e) {
                // 전송 실패 시 연결 끊김 처리
                onNetworkError(e);
                break;
            }
        }
    }

    // 수신 메시지 처리 - 네트워크에서 받은 메시지 처리
    private void processReceiveQueue() {
        GameMessage message;
        // NetworkReader가 채워 넣은 incomingQueue의 메시지를 처리
        while ((message = incomingQueue.poll()) != null) {
            // 수신된 메시지를 콜백을 통해 NetworkManager로 전달
            callback.handleReceivedMessage(message); 
        }
    }

    // 우선순위 메시지 처리 - 핑, 에러, 연결 관련 메시지
    private void processPriorityMessages() {
        GameMessage message;
        while ((message = priorityQueue.poll()) != null) {
            switch (message.getType()) {
                case PING:
                    // PING을 받으면 PONG으로 응답
                    sendPriorityMessage(new GameMessage(MessageType.PONG, "CLIENT", message.getPayload()));
                    break;
                case PONG:
                    // PONG을 받으면 지연시간 계산
                    long pingTime = (Long) message.getPayload();
                    long latency = System.currentTimeMillis() - pingTime;
                    currentLatency = latency;
                    latencyHistory.offer(latency);
                    if (latency > MAX_LAG_THRESHOLD) {
                        onLatencyWarning(latency);
                    }
                    break;
                case ERROR:
                    System.err.println("Received ERROR from server: " + message.getPayload());
                    break;
                case DISCONNECT:
                    onConnectionLost();
                    break;
                default:
                    // 다른 우선순위 메시지 처리 (예: ROOM_JOINED 등)
                    callback.handleReceivedMessage(message);
                    break;
            }
        }
    }
    
    /**
     * ObjectInputStream.readObject()는 블로킹(Blocking)이므로, 
     * 메인 스레드를 막지 않기 위해 전용 스레드를 사용합니다.
     */
    private class NetworkReader implements Runnable {
        @Override
        public void run() {
            try {
                while (isRunning.get() && isConnected.get()) {
                    // 블로킹 호출: 메시지가 올 때까지 대기
                    GameMessage message = (GameMessage) inputStream.readObject(); 
                    
                    // 메시지 타입에 따라 큐에 분배
                    if (message.getType() == MessageType.PING || 
                        message.getType() == MessageType.PONG ||
                        message.getType() == MessageType.ERROR ||
                        message.getType() == MessageType.DISCONNECT) {
                        priorityQueue.offer(message);
                    } else {
                        incomingQueue.offer(message);
                    }
                }
            } catch (ClassNotFoundException e) {
                System.err.println("수신된 객체 타입 오류: " + e.getMessage());
            } catch (IOException e) {
                // 연결이 끊기거나 스트림 오류 발생 시
                if (isConnected.get()) {
                    onConnectionLost();
                }
            }
        }
    }

    // 게임 상태 동기화 - 주기적으로 호출
    private void synchronizeGameState() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSyncTime >= syncInterval) {
            lastSyncTime = currentTime;
            // TODO: 필요한 경우 주기적인 동기화 메시지 전송 로직 추가
        }
    }

    // 지연시간 측정 - 핑-퐁 메커니즘
    private void measureLatency() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPingTime >= PING_INTERVAL) {
            // PING 메시지 전송 (페이로드에 현재 시간 포함)
            sendPriorityMessage(new GameMessage(MessageType.PING, "CLIENT", currentTime));
            lastPingTime = currentTime;
        }
    }

    // 연결 상태 모니터링
    private void monitorConnection() {
        measureLatency();
        // TODO: 소켓의 상태를 확인하여 강제 종료 여부 판단 (예: socket.isClosed())
    }

    // 재연결 시도
    private void attemptReconnection() {
        long currentTime = System.currentTimeMillis();
        if (reconnectAttempts < MAX_RETRY_COUNT && currentTime - lastReconnectTime >= RECONNECT_DELAY) {
            lastReconnectTime = currentTime;
            reconnectAttempts++;
            System.out.println("재연결 시도 중... (" + reconnectAttempts + "/" + MAX_RETRY_COUNT + ")");

            try {
                // 재연결 시도
                attemptConnection(); // attemptConnection 재사용
                
                if (isConnected.get()) {
                    reconnectAttempts = 0;
                }
            } catch (Exception e) {
                // 연결 실패
            }
        } else if (reconnectAttempts >= MAX_RETRY_COUNT) {
            System.err.println("최대 재연결 횟수 초과. 연결 복구 실패.");
            shutdown();
        }
    }

    // === 외부 인터페이스 ===

    public void sendMessage(GameMessage message) {
        outgoingQueue.offer(message);
    }

    public void sendPriorityMessage(GameMessage message) {
        priorityQueue.offer(message);
    }

    // GameThread가 직접 메시지를 가져가는 대신, processReceiveQueue()에서 콜백을 사용하도록 유도
    public GameMessage getReceivedMessage() {
        // 이 메서드는 사용하지 않거나, incomingQueue.poll()을 호출하도록 할 수 있으나,
        // 현재 설계는 콜백 기반이므로, 외부에서 직접 큐에 접근하는 것은 권장되지 않음.
        return incomingQueue.poll(); 
    }

    public long getCurrentLatency() {
        return currentLatency;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public NetworkStats getNetworkStats() {
        return new NetworkStats(0L, 0L, currentLatency);
    }

    public void shutdown() {
        isRunning.set(false);
        // 먼저 reader 스레드를 종료하도록 시도
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
            try {
                readerThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        cleanup();
    }

    // === 이벤트 처리 ===

    private void onConnectionEstablished() {
        System.out.println("네트워크 연결 성공!");
        callback.handleConnectionEstablished();
    }

    private void onConnectionLost() {
        // Reader 스레드가 IOException으로 종료될 때 호출됨
        isConnected.set(false);
        System.err.println("네트워크 연결 끊김! 재연결 시도...");
        callback.handleConnectionLost(); 
        lastReconnectTime = System.currentTimeMillis();
        
        // I/O 스트림 및 소켓 닫기
        closeStreamsAndSocket();
    }

    private void onLatencyWarning(long latency) {
        System.out.println("!!! 랙 경고: 지연시간 " + latency + "ms");
        callback.handleLatencyWarning(latency); 
    }

    private void onNetworkError(Exception error) {
        System.err.println("치명적인 네트워크 에러 발생: " + error.getMessage());
        // onConnectionLost()를 호출하여 연결 끊김 처리 및 재연결 시도
        onConnectionLost();
        callback.handleNetworkError(error); 
    }
    
    private void closeStreamsAndSocket() {
        if (readerThread != null) {
            readerThread.interrupt();
        }
        // 먼저 소켓의 입력/출력 스트림을 shutdown 시도하여 readObject 블로킹을 해제
        if (socket != null && !socket.isClosed()) {
            try { socket.shutdownInput(); } catch (IOException ignore) {}
            try { socket.shutdownOutput(); } catch (IOException ignore) {}
        }
        if (outputStream != null) try { outputStream.close(); } catch (IOException ignore) {}
        if (inputStream != null) try { inputStream.close(); } catch (IOException ignore) {}
        if (socket != null && !socket.isClosed()) try { socket.close(); } catch (IOException ignore) {}

        outputStream = null;
        inputStream = null;
        socket = null;
    }
    
    private void cleanup() {
        closeStreamsAndSocket();
        outgoingQueue.clear();
        incomingQueue.clear();
        priorityQueue.clear();
    }
}