import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * Embedded server that can run within KidPaint2 application
 * Handles multiple clients and studio management
 */
public class EmbeddedServer {
    
    // Protocol types
    final int NAME = 0;
    final int PIXELS = 1;
    final int MESSAGE = 2;
    final int INITIAL_SKETCH = 3;
    final int LOAD_SKETCH = 4;
    final int STUDIO_LIST = 5;
    final int SELECT_STUDIO = 6;
    final int CREATE_STUDIO = 7;
    
    // All client connections and their output streams
    private HashMap<Socket, DataOutputStream> clientMap = new HashMap<>();
    
    // Each socket belongs to which studio
    private HashMap<Socket, String> clientStudioMap = new HashMap<>();
    
    // studio -> current canvas data
    private HashMap<String, int[][]> studioDataMap = new HashMap<>();
    
    // studio -> list of sockets in that studio
    private HashMap<String, LinkedList<Socket>> studioClientsMap = new HashMap<>();
    
    private ServerSocket serverSocket;
    private int port;
    private boolean running = false;
    private Thread serverThread;
    
    /**
     * Point class for pixel coordinates
     */
    class Point {
        int x, y;
        
        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
    
    /**
     * Worker thread for handling each client connection
     */
    class WorkerThread extends Thread {
        private String username;
        private final EmbeddedServer server;
        private final Socket socket;
        
        public WorkerThread(Socket socket, EmbeddedServer server) {
            this.server = server;
            this.socket = socket;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getUsername() {
            return username;
        }
        
        @Override
        public void run() {
            try {
                System.out.println("User " + getUsername() + " has joined.");
                server.serve(socket, this);
            } catch (IOException e) {
                System.out.println("Client disconnected!");
                synchronized (server.clientMap) {
                    server.removeClient(socket);
                }
            }
        }
    }
    
    /**
     * Start the server on specified port
     */
    public void start(int port) throws IOException {
        this.port = port;
        
        serverSocket = new ServerSocket(port);
        running = true;
        System.out.println("EmbeddedServer started on port " + port);
        
        // Start UDP discovery listener
        ServerDiscovery.startDiscoveryListener(port, this::getStudioList);
        
        serverThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    synchronized (clientMap) {
                        clientMap.put(socket, new DataOutputStream(socket.getOutputStream()));
                    }
                    
                    Thread thread = new WorkerThread(socket, this);
                    thread.setDaemon(true);
                    thread.start();
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            }
        });
        
        serverThread.setDaemon(true);
        serverThread.start();
    }
    
    /**
     * Stop the server
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // Close all client connections
        synchronized (clientMap) {
            for (Socket socket : clientMap.keySet()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            clientMap.clear();
            clientStudioMap.clear();
            studioClientsMap.clear();
        }
    }
    
    /**
     * Get list of studio names (for UDP discovery)
     */
    public List<String> getStudioList() {
        synchronized (studioDataMap) {
            return new LinkedList<>(studioDataMap.keySet());
        }
    }
    
    /**
     * Get the port the server is running on
     */
    public int getPort() {
        return port;
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
                    // Don't delete studioDataMap, keep canvas for future users
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
        
        // Initially: wait for NAME, then send Studio List
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
                    // Client requests current studio canvas
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
    
    // ====== Studio Selection / Creation ======
    
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
    
    // ====== Canvas Synchronization ======
    
    void receiveFullSketch(DataInputStream in, Socket socket) throws IOException {
        String studioName = getClientStudio(socket);
        if (studioName == null) {
            // Not selected studio yet, ignore
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
            // Not joined studio yet, give default
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
    
    // ====== Username ======
    
    String receiveNAME(DataInputStream in) throws IOException {
        String name = readString(in);
        System.out.println("User connected: " + name);
        return name;
    }
    
    // ====== Pixel Updates ======
    
    void receivePIXELS(DataInputStream in, Socket socket) throws IOException {
        String studioName = getClientStudio(socket);
        if (studioName == null) {
            // Not selected studio yet, discard data
            in.readInt(); // color
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
    
    // ====== Messages ======
    
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
}

