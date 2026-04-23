import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Window for selecting or creating a studio
 * Supports both traditional server list and peer-to-peer server discovery
 */
public class StudioListWindow {

    @FXML
    private ListView<String> studioListView;

    @FXML
    private TextField newStudioField;

    private Stage stage;
    private String selectedStudio;
    private boolean createNew;
    private int canvasSize = 100; // Default 100x100
    
    // For peer-to-peer: store selected server info
    private ServerDiscovery.ServerInfo selectedServerInfo;
    private List<ServerDiscovery.ServerInfo> serverInfoList;

    /**
     * Constructor for traditional server (receives studio list from server)
     */
    public StudioListWindow(List<String> studios) throws IOException {
        this.serverInfoList = null;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("studioListUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();

        stage = new Stage();
        stage.setTitle("Select Studio");
        stage.setScene(new Scene(root));

        if (studios != null) {
            studioListView.getItems().addAll(studios);
        }

        stage.showAndWait();
    }
    
    /**
     * Constructor for peer-to-peer mode (displays discovered servers with studios)
     */
    public StudioListWindow(List<ServerDiscovery.ServerInfo> serverInfoList, boolean isPeerToPeer) throws IOException {
        this.serverInfoList = serverInfoList;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("studioListUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();

        stage = new Stage();
        stage.setTitle("Select Studio");
        stage.setScene(new Scene(root));

        // Display all studios from all discovered servers
        if (serverInfoList != null) {
            for (ServerDiscovery.ServerInfo serverInfo : serverInfoList) {
                for (String studio : serverInfo.studios) {
                    // Format: "StudioName @ IP:Port"
                    String displayText = studio + " @ " + serverInfo.ip + ":" + serverInfo.port;
                    studioListView.getItems().add(displayText);
                }
            }
        }

        stage.showAndWait();
    }

    @FXML
    private void createStudio(ActionEvent event) {
        String name = newStudioField.getText().trim();
        if (name.isEmpty()) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog("100");
        dialog.setTitle("New Studio");
        dialog.setHeaderText("Set canvas size (width = height)");
        dialog.setContentText("Size(1-200):");

        Optional<String> result = dialog.showAndWait();
        if (!result.isPresent()) {
            return;
        }

        try {
            int size = Integer.parseInt(result.get());
            if (size <= 0 || size > 200) {
                return;
            }
            this.canvasSize = size;
        } catch (NumberFormatException ex) {
            return;
        }

        this.selectedStudio = name;
        this.createNew = true;
        stage.close();
    }

    @FXML
    private void joinSelected(ActionEvent event) {
        String selected = studioListView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.trim().isEmpty()) {
            return;
        }
        
        // For peer-to-peer mode: extract studio name and find server info
        if (serverInfoList != null) {
            // Format: "StudioName @ IP:Port"
            String[] parts = selected.split(" @ ");
            if (parts.length == 2) {
                this.selectedStudio = parts[0].trim();
                String serverAddr = parts[1].trim();
                
                // Find matching server info
                for (ServerDiscovery.ServerInfo info : serverInfoList) {
                    String addr = info.ip + ":" + info.port;
                    if (addr.equals(serverAddr)) {
                        this.selectedServerInfo = info;
                        break;
                    }
                }
            } else {
                this.selectedStudio = selected.trim();
            }
        } else {
            // Traditional mode: just use the studio name
            this.selectedStudio = selected.trim();
        }
        
        this.createNew = false;
        stage.close();
    }

    public String getSelectedStudio() {
        return selectedStudio;
    }
    
    /**
     * Get selected server info (for peer-to-peer mode)
     */
    public ServerDiscovery.ServerInfo getSelectedServerInfo() {
        return selectedServerInfo;
    }

    public boolean isCreateNew() {
        return createNew;
    }

    public int getCanvasSize() {
        return canvasSize;
    }
}