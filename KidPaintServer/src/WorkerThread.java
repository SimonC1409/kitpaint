import java.io.IOException;
import java.net.Socket;

public class WorkerThread extends Thread {

    private String username;
    private final KidPaintServer server;
    private final Socket socket;

    public WorkerThread(Socket socket, KidPaintServer server) {
        this.server = server;
        this.socket = socket;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    String CheckUser(int users) {
        if (server.getOnlineUsers() > 1)
            return "s";
        return "";
    }

    public void run() {
        try {
            System.out.println("User " + getUsername() + " has joined.");
            server.serve(socket, this);
        } catch (IOException e) {
            System.out.println("Disconnected!");
            synchronized (server.clientMap) {
                server.removeClient(socket);
            }
        }
    }
}