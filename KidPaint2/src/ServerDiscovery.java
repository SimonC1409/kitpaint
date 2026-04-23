import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

/**
 * UDP broadcast discovery for finding servers on the network
 */
public class ServerDiscovery {
    
    // UDP broadcast port for discovery
    private static final int DISCOVERY_PORT = 12346;
    // Broadcast request message
    private static final String DISCOVERY_REQUEST = "KIDPAINT_DISCOVERY_REQUEST";
    // Broadcast response prefix
    private static final String DISCOVERY_RESPONSE_PREFIX = "KIDPAINT_DISCOVERY_RESPONSE:";
    // Timeout for discovery (milliseconds)
    private static final int DISCOVERY_TIMEOUT = 2000;
    
    /**
     * Represents a discovered server with its studio list
     */
    public static class ServerInfo {
        public String ip;
        public int port;
        public List<String> studios;
        
        public ServerInfo(String ip, int port, List<String> studios) {
            this.ip = ip;
            this.port = port;
            this.studios = studios;
        }
        
        @Override
        public String toString() {
            return ip + ":" + port + " (" + studios.size() + " studios)";
        }
    }
    
    /**
     * Broadcast discovery request and collect server responses
     * @return List of discovered servers
     */
    public static List<ServerInfo> discoverServers() throws IOException {
        List<ServerInfo> servers = new ArrayList<>();
        
        // Create UDP socket for broadcasting
        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);
        socket.setSoTimeout(DISCOVERY_TIMEOUT);
        
        try {
            // Send broadcast request
            byte[] requestData = DISCOVERY_REQUEST.getBytes();
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket requestPacket = new DatagramPacket(
                requestData, 
                requestData.length, 
                broadcastAddress, 
                DISCOVERY_PORT
            );
            socket.send(requestPacket);
            
            // Collect responses
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT) {
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);
                    
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    if (response.startsWith(DISCOVERY_RESPONSE_PREFIX)) {
                        // Parse response: "KIDPAINT_DISCOVERY_RESPONSE:port:studio1,studio2,..."
                        String data = response.substring(DISCOVERY_RESPONSE_PREFIX.length());
                        String[] parts = data.split(":", 2);
                        if (parts.length == 2) {
                            int port = Integer.parseInt(parts[0]);
                            String ip = responsePacket.getAddress().getHostAddress();
                            
                            // Parse studio list
                            List<String> studios = new ArrayList<>();
                            if (!parts[1].isEmpty()) {
                                String[] studioArray = parts[1].split(",");
                                for (String studio : studioArray) {
                                    if (!studio.trim().isEmpty()) {
                                        studios.add(studio.trim());
                                    }
                                }
                            }
                            
                            servers.add(new ServerInfo(ip, port, studios));
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout is expected, break the loop
                    break;
                } catch (Exception e) {
                    // Ignore malformed responses
                    e.printStackTrace();
                }
            }
        } finally {
            socket.close();
        }
        
        return servers;
    }
    
    /**
     * Start UDP discovery listener (for servers)
     * This should run in a separate thread
     */
    public static void startDiscoveryListener(int tcpPort, ServerInfoProvider provider) {
        Thread listenerThread = new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(DISCOVERY_PORT);
                socket.setBroadcast(true);
                
                byte[] buffer = new byte[1024];
                
                while (true) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        
                        String request = new String(packet.getData(), 0, packet.getLength());
                        if (DISCOVERY_REQUEST.equals(request)) {
                            // Get studio list from provider
                            List<String> studios = provider.getStudioList();
                            
                            // Build response: "KIDPAINT_DISCOVERY_RESPONSE:port:studio1,studio2,..."
                            StringBuilder response = new StringBuilder(DISCOVERY_RESPONSE_PREFIX);
                            response.append(tcpPort).append(":");
                            for (int i = 0; i < studios.size(); i++) {
                                if (i > 0) response.append(",");
                                response.append(studios.get(i));
                            }
                            
                            // Send response back to requester
                            byte[] responseData = response.toString().getBytes();
                            DatagramPacket responsePacket = new DatagramPacket(
                                responseData,
                                responseData.length,
                                packet.getAddress(),
                                packet.getPort()
                            );
                            socket.send(responsePacket);
                        }
                    } catch (IOException e) {
                        if (socket != null && !socket.isClosed()) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (SocketException e) {
                // Socket closed, exit thread
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
        
        listenerThread.setDaemon(true);
        listenerThread.start();
    }
    
    /**
     * Interface for providing studio list to discovery listener
     */
    public interface ServerInfoProvider {
        List<String> getStudioList();
    }
}

