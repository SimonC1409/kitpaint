import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class KidPaintServer {

    // 協定型別
    final int NAME = 0;
    final int PIXELS = 1;
    final int MESSAGE = 2;
    final int INITIAL_SKETCH = 3;
    final int LOAD_SKETCH = 4;

    final int STUDIO_LIST = 5;
    final int SELECT_STUDIO = 6;
    final int CREATE_STUDIO = 7;

    // 全部連線對應的輸出串流（for 清理用）
    HashMap<Socket, DataOutputStream> clientMap = new HashMap<>();

    // 每個 socket 所屬的 studio
    HashMap<Socket, String> clientStudioMap = new HashMap<>();

    // studio -> 目前畫布資料
    HashMap<String, int[][]> studioDataMap = new HashMap<>();

    // studio -> 該 studio 的 socket 清單
    HashMap<String, LinkedList<Socket>> studioClientsMap = new HashMap<>();

    int onlineUsers = 0;

    public KidPaintServer(int port) throws IOException {
        // 建立一個預設 Studio，供舊 client 或第一個使用者使用
        studioDataMap.put("DefaultStudio", new int[100][100]);

        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("KidPaintServer started on port " + port);

        while (true) {
            Socket socket = serverSocket.accept();
            synchronized (clientMap) {
                clientMap.put(socket, new DataOutputStream(socket.getOutputStream()));
            }

            Thread thread = new WorkerThread(socket, this);
            thread.start();
        }
    }

    int getOnlineUsers() {
        return onlineUsers;
    }

    void setOnlineUsers(int users) {
        onlineUsers = users;
    }

    class Point {
        int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    synchronized void addClientToStudio(String studioName, Socket socket) {
        clientStudioMap.put(socket, studioName);
        studioClientsMap.computeIfAbsent(studioName, k -> new LinkedList<>()).add(socket);
    }

    synchronized void removeClient(Socket socket) {
        clientMap.remove(socket);
        String studio = clientStudioMap.remove(socket);
        if (studio != null) {
            LinkedList<Socket> list = studioClientsMap.get(studio);
            if (list != null) {
                list.remove(socket);
                if (list.isEmpty()) {
                    studioClientsMap.remove(studio);
                    // 不刪除 studioDataMap，保留畫布，之後的人仍可再加入
                }
            }
        }
    }

    synchronized String getClientStudio(Socket socket) {
        return clientStudioMap.get(socket);
    }

    synchronized int[][] getStudioData(String studioName) {
        return studioDataMap.get(studioName);
    }

    synchronized void setStudioData(String studioName, int[][] data) {
        studioDataMap.put(studioName, data);
    }

    void serve(Socket socket, WorkerThread thread) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        // 一開始：等 NAME，再送 Studio List
        while (true) {
            int type = in.read();
            switch (type) {
                case NAME:
                    thread.setUsername(receiveNAME(in));
                    sendStudioList(out);
                    break;
                case PIXELS:
                    receivePIXELS(in, socket);
                    break;
                case MESSAGE:
                    receiveMESSAGE(in, thread.getUsername(), socket);
                    break;
                case INITIAL_SKETCH:
                    // client 要求重新拿目前 studio 的畫布
                    sendInitialSketchToClient(out, socket);
                    break;
                case LOAD_SKETCH:
                    receiveFullSketch(in, socket);
                    break;
                case SELECT_STUDIO:
                    handleSelectStudio(in, socket, out);
                    break;
                case CREATE_STUDIO:
                    handleCreateStudio(in, socket, out);
                    break;
            }
        }
    }

    // ====== Studio 選擇 / 建立 ======

    void sendStudioList(DataOutputStream out) throws IOException {
        synchronized (studioDataMap) {
            out.write(STUDIO_LIST);
            out.writeInt(studioDataMap.size());
            for (String name : studioDataMap.keySet()) {
                byte[] bytes = name.getBytes();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            out.flush();
        }
    }

    String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] buffer = new byte[len];
        in.readFully(buffer, 0, len);
        return new String(buffer, 0, len);
    }

    void handleSelectStudio(DataInputStream in, Socket socket, DataOutputStream out) throws IOException {
        String studioName = readString(in);
        synchronized (this) {
            if (!studioDataMap.containsKey(studioName)) {
                studioDataMap.put(studioName, new int[100][100]);
            }
            addClientToStudio(studioName, socket);
        }
        sendInitialSketchToClient(out, socket);
        System.out.println("User joined studio: " + studioName);
    }

    void handleCreateStudio(DataInputStream in, Socket socket, DataOutputStream out) throws IOException {
        String studioName = readString(in);
        int size = in.readInt();
        if (size <= 0) size = 100;

        synchronized (this) {
            if (!studioDataMap.containsKey(studioName)) {
                studioDataMap.put(studioName, new int[size][size]);
            }
            addClientToStudio(studioName, socket);
        }
        sendInitialSketchToClient(out, socket);
        System.out.println("Created studio: " + studioName + " (" + size + "x" + size + ")");
    }

    // ====== 畫布同步 ======

    void receiveFullSketch(DataInputStream in, Socket socket) throws IOException {
        String studioName = getClientStudio(socket);
        if (studioName == null) {
            // 尚未選 studio，忽略
            int rows = in.readInt();
            int cols = in.readInt();
            for (int i = 0; i < rows * cols; i++) {
                in.readInt();
            }
            return;
        }

        int rows = in.readInt();
        int cols = in.readInt();
        int[][] newData = new int[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                newData[row][col] = in.readInt();
            }
        }

        synchronized (this) {
            setStudioData(studioName, newData);
        }
        forwardFullSketch(studioName);
    }

    void forwardFullSketch(String studioName) {
        int[][] data = getStudioData(studioName);
        if (data == null) return;

        synchronized (clientMap) {
            LinkedList<Socket> sockets = studioClientsMap.get(studioName);
            if (sockets == null) return;

            for (Socket s : sockets) {
                DataOutputStream out = clientMap.get(s);
                if (out == null) continue;
                try {
                    sendInitialSketch(out, data);
                } catch (IOException e) {
                    System.out.println("Someone disconnected during full sketch forward");
                }
            }
        }
    }

    void sendInitialSketchToClient(DataOutputStream out, Socket socket) throws IOException {
        String studioName = getClientStudio(socket);
        if (studioName == null) {
            // 尚未加入 studio，給預設
            studioName = "DefaultStudio";
            synchronized (this) {
                if (!studioDataMap.containsKey(studioName)) {
                    studioDataMap.put(studioName, new int[100][100]);
                }
                addClientToStudio(studioName, socket);
            }
        }
        int[][] data = getStudioData(studioName);
        if (data == null) {
            data = new int[100][100];
            setStudioData(studioName, data);
        }
        sendInitialSketch(out, data);
    }

    void sendInitialSketch(DataOutputStream out, int[][] data) throws IOException {
        synchronized (data) {
            out.write(INITIAL_SKETCH);
            out.writeInt(data.length);
            out.writeInt(data[0].length);
            for (int row = 0; row < data.length; row++) {
                for (int col = 0; col < data[0].length; col++) {
                    out.writeInt(data[row][col]);
                }
            }
            out.flush();
        }
    }

    // ====== 使用者名稱 ======

    String receiveNAME(DataInputStream in) throws IOException {
        String name = readString(in);
        System.out.println("User connected: " + name);
        return name;
    }

    // ====== 差量像素 ======

    void receivePIXELS(DataInputStream in, Socket socket) throws IOException {
        String studioName = getClientStudio(socket);
        if (studioName == null) {
            // 尚未選擇 studio，丟棄資料
            int color = in.readInt();
            int len = in.readInt();
            for (int i = 0; i < len; i++) {
                in.readInt();
                in.readInt();
            }
            return;
        }

        int color = in.readInt();
        int len = in.readInt();
        LinkedList<Point> pixels = new LinkedList<>();
        int[][] data = getStudioData(studioName);
        if (data == null) {
            data = new int[100][100];
            setStudioData(studioName, data);
        }

        for (int i = 0; i < len; i++) {
            int x = in.readInt();
            int y = in.readInt();
            pixels.add(new Point(x, y));
            if (y >= 0 && y < data.length && x >= 0 && x < data[0].length) {
                data[y][x] = color;
            }
        }
        forwardPixels(color, pixels, studioName);
    }

    void forwardPixels(int color, LinkedList<Point> pixels, String studioName) {
        synchronized (clientMap) {
            LinkedList<Socket> sockets = studioClientsMap.get(studioName);
            if (sockets == null) return;

            for (Socket s : sockets) {
                DataOutputStream out = clientMap.get(s);
                if (out == null) continue;
                try {
                    out.write(PIXELS);
                    out.writeInt(color);
                    out.writeInt(pixels.size());
                    for (Point p : pixels) {
                        out.writeInt(p.x);
                        out.writeInt(p.y);
                    }
                    out.flush();
                } catch (IOException ex) {
                    System.out.println("Someone disconnected during pixel forward");
                }
            }
        }
    }

    // ====== 訊息 ======

    void receiveMESSAGE(DataInputStream in, String username, Socket socket) throws IOException {
        String studioName = getClientStudio(socket);
        if (studioName == null) {
            int size = in.readInt();
            for (int i = 0; i < size; i++) {
                in.read();
            }
            return;
        }

        int size = in.readInt();
        byte[] buffer = new byte[size];
        in.readFully(buffer, 0, size);
        String text = username + ": " + new String(buffer, 0, size);
        forwardMsg(text.getBytes(), studioName);
    }

    void forwardMsg(byte[] buffer, String studioName) {
        synchronized (clientMap) {
            LinkedList<Socket> sockets = studioClientsMap.get(studioName);
            if (sockets == null) return;

            for (Socket s : sockets) {
                DataOutputStream out = clientMap.get(s);
                if (out == null) continue;
                try {
                    out.write(MESSAGE);
                    out.writeInt(buffer.length);
                    out.write(buffer, 0, buffer.length);
                    out.flush();
                } catch (IOException ex) {
                    System.out.println("User left already!");
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        new KidPaintServer(12345);
    }
}