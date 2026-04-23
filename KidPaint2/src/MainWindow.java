/* Replace your existing MainWindow.java with this file.
   Only client-side changes: adds brush size UI & brush expansion logic.
*/

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.Parent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class MainWindow {

    // 協定型別
    final int NAME = 0;
    final int PIXELS = 1;
    final int MESSAGE = 2;
    final int INITIAL_SKETCH = 3;
    final int LOAD_SKETCH = 4;

    final int STUDIO_LIST = 5;
    final int SELECT_STUDIO = 6;
    final int CREATE_STUDIO = 7;

    Socket socket;
    DataInputStream in;
    DataOutputStream out;


    @FXML
    Button btnEmoji;

    @FXML
    Button btnReset;

    @FXML
    ChoiceBox<String> chbSize;

    @FXML
    ChoiceBox<String> chbMode;

    @FXML
    Canvas canvas;

    @FXML
    Pane container;

    @FXML
    Pane panePicker;

    @FXML
    Pane paneColor;

    @FXML
    Button btnSend;

    @FXML
    Button btnSave;

    @FXML
    Button btnLoad;

    @FXML
    TextField txtMsg;

    @FXML
    TextArea areaMsg;

    String username;
    int numPixels = 100;

    Stage stage;
    AnimationTimer animationTimer;

    int[][] data;
    double pixelSize, padSize, startX, startY;
    int selectedColorARGB;
    boolean isPenMode = true;
    LinkedList<Point> filledPixels = new LinkedList<Point>();
    int lastRow = -1;
    int lastCol = -1;
    int brushSize = 1;
    final int CLEAR_COLOR = 0x00000000; // or the same as your initial blank pixel value
    // For peer-to-peer: embedded server instance (if running as server+client)
    EmbeddedServer embeddedServer;

    class Point {
        int x, y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Constructor for peer-to-peer mode
     * Uses UDP discovery to find servers, then connects or starts own server
     */
    public MainWindow(Stage stage, String username) throws IOException {
        this.username = username;
        this.stage = stage;

        // Step 1: Discover servers using UDP broadcast
        List<ServerDiscovery.ServerInfo> discoveredServers = ServerDiscovery.discoverServers();

        // Step 2: Show studio selection window with discovered servers
        StudioListWindow studioWindow;
        if (discoveredServers != null && !discoveredServers.isEmpty()) {
            // Peer-to-peer mode: show studios from discovered servers
            studioWindow = new StudioListWindow(discoveredServers, true);
        } else {
            // No servers found, show empty list (user can create new)
            studioWindow = new StudioListWindow(new ArrayList<>());
        }

        String studioName = studioWindow.getSelectedStudio();
        boolean createNew = studioWindow.isCreateNew();
        ServerDiscovery.ServerInfo selectedServer = studioWindow.getSelectedServerInfo();

        // Step 3: Connect to server or start own server
        if (createNew) {
            // Create new studio: start embedded server and connect to it
            startEmbeddedServerAndConnect(studioName, studioWindow.getCanvasSize());
        } else if (selectedServer != null) {
            // Join existing studio: connect to discovered server
            connectToServer(selectedServer.ip, selectedServer.port, studioName);
        } else {
            // Fallback: try to connect to localhost (for backward compatibility)
            connectToServer("127.0.0.1", 12345, studioName != null ? studioName : "DefaultStudio");
        }

        // Load main window FXML
        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainWindownUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        Scene scene = new Scene(root);

        stage.setScene(scene);
        stage.setMinWidth(scene.getWidth());
        stage.setMinHeight(scene.getHeight());

        canvas.widthProperty().bind(container.widthProperty());
        canvas.heightProperty().bind(container.heightProperty());
        canvas.widthProperty().addListener(w -> onCanvasSizeChange());
        canvas.heightProperty().addListener(h -> onCanvasSizeChange());

        btnSend.setOnAction(event -> {
            sendText(txtMsg.getText());
            txtMsg.clear();
        });

        btnSave.setOnAction(event -> saveToFile());
        btnLoad.setOnAction(event -> loadFromFile());

        stage.setOnCloseRequest(event -> quit());
        stage.show();

        initial();
        animationTimer.start();

        Thread thread = new Thread(this::receiveData);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Start embedded server and connect to it as a client
     */
    private void startEmbeddedServerAndConnect(String studioName, int canvasSize) throws IOException {
        // Find available port
        int port = findAvailablePort();

        // Start embedded server
        embeddedServer = new EmbeddedServer();
        embeddedServer.start(port);

        // Connect to own server as client
        connectToServer("127.0.0.1", port, studioName, canvasSize);
    }

    /**
     * Connect to a server (traditional or discovered)
     */
    private void connectToServer(String ip, int port, String studioName) throws IOException {
        connectToServer(ip, port, studioName, -1);
    }

    /**
     * Connect to a server and select/create studio
     */
    private void connectToServer(String ip, int port, String studioName, int canvasSize) throws IOException {
        // Connect to server
        socket = new Socket(ip, port);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        // Send username
        out.write(NAME);
        out.writeInt(username.length());
        out.write(username.getBytes());
        out.flush();

        // Wait for server to send studio list
        int type = in.read();
        if (type == STUDIO_LIST) {
            int count = in.readInt();
            // Read but don't use (we already selected)
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
            }
        }

        // Select or create studio
        if (canvasSize > 0) {
            // Create new studio
            out.write(CREATE_STUDIO);
            out.writeInt(studioName.length());
            out.write(studioName.getBytes());
            out.writeInt(canvasSize);
            out.flush();
        } else {
            // Select existing studio
            out.write(SELECT_STUDIO);
            out.writeInt(studioName.length());
            out.write(studioName.getBytes());
            out.flush();
        }

        // Server will send INITIAL_SKETCH with the canvas data
    }

    /**
     * Find an available port for the embedded server
     */
    private int findAvailablePort() throws IOException {
        try (ServerSocket tempSocket = new ServerSocket(0)) {
            return tempSocket.getLocalPort();
        }
    }


    void saveToFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Sketch");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("KidPaint Files", "*.kpf"));
        File file = fileChooser.showSaveDialog(stage);
        if (file != null && data != null) {
            try (DataOutputStream fileOut = new DataOutputStream(new FileOutputStream(file))) {
                fileOut.writeInt(data.length);
                fileOut.writeInt(data[0].length);
                for (int row = 0; row < data.length; row++) {
                    for (int col = 0; col < data[0].length; col++) {
                        fileOut.writeInt(data[row][col]);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void loadFromFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Load Sketch");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("KidPaint Files", "*.kpf"));
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try (DataInputStream fileIn = new DataInputStream(new FileInputStream(file))) {
                int rows = fileIn.readInt();
                int cols = fileIn.readInt();
                int[][] loadedData = new int[rows][cols];
                for (int row = 0; row < rows; row++) {
                    for (int col = 0; col < cols; col++) {
                        loadedData[row][col] = fileIn.readInt();
                    }
                }

                // 將整張畫布送到伺服器（只影響同一 Studio）
                out.write(LOAD_SKETCH);
                out.writeInt(rows);
                out.writeInt(cols);
                for (int row = 0; row < rows; row++) {
                    for (int col = 0; col < cols; col++) {
                        out.writeInt(loadedData[row][col]);
                    }
                }
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void sendText(String text) {
        if (text == null || text.isEmpty()) return;

        try {
            out.write(MESSAGE);
            out.writeInt(text.length());
            out.write(text.getBytes());
            out.flush();
        } catch (IOException ex) {
            System.out.println("Connection is dropped!");
        }
    }

    void receiveData() {
        try {
            while (true) {
                int datatype = in.read();
                switch (datatype) {
                    case PIXELS:
                        receivePixels();
                        break;
                    case MESSAGE:
                        receiveMsg();
                        break;
                    case INITIAL_SKETCH:
                        receiveInitialSketch();
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("Disconnected!!!");
        }
    }

    void receiveInitialSketch() throws IOException {
        int rows = in.readInt();
        int cols = in.readInt();
        int[][] newData = new int[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                newData[row][col] = in.readInt();
            }
        }
        data = newData;
        numPixels = Math.max(rows, cols);

        Platform.runLater(this::onCanvasSizeChange);
    }

    void receiveMsg() throws IOException {
        int size = in.readInt();
        byte[] buffer = new byte[size];
        in.readFully(buffer, 0, size);
        String msg = new String(buffer, 0, size);

        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String msgWithTime = "[" + time + "] " + msg;

        Platform.runLater(() -> areaMsg.appendText(msgWithTime + "\n"));
    }

    void receivePixels() throws IOException {
        int color = in.readInt();
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            int x = in.readInt();
            int y = in.readInt();
            if (data != null && y >= 0 && y < data.length && x >= 0 && x < data[0].length) {
                data[y][x] = color;
            }
        }
    }

    /**
     * Update canvas info when the window is resized
     */
    void onCanvasSizeChange() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        padSize = Math.min(w, h);
        startX = (w - padSize) / 2;
        startY = (h - padSize) / 2;

        // 使用最大邊數來計算像素大小（畫布可非 100x100）
        if (data != null) {
            int rows = data.length;
            int cols = data[0].length;
            numPixels = Math.max(rows, cols);
        }
        pixelSize = numPixels > 0 ? padSize / numPixels : 0;
    }

    /**
     * Terminate this program
     */
    void quit() {
        System.out.println("Bye bye");
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Stop embedded server if running
        if (embeddedServer != null) {
            embeddedServer.stop();
        }

        stage.close();
        System.exit(0);
    }

    /**
     * Initialize UI components
     */
    void initial() throws IOException {
        // data 將由伺服器的 INITIAL_SKETCH 填入
        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long l) {
                render();
            }
        };

        chbMode.setValue("Pen");

        // Populate brush size chooser and default selection
        if (chbSize != null) {
            chbSize.getItems().clear();
            chbSize.getItems().addAll("Thin", "Medium", "Thick");
            chbSize.setValue("Thin");
            brushSize = 1;
            chbSize.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
                if (newV == null) return;
                switch (newV) {
                    case "Thin": brushSize = 1; break;
                    case "Medium": brushSize = 3; break;
                    case "Thick": brushSize = 5; break;
                    default: brushSize = 1; break;
                }
            });
        }

        // --- Add your button handlers here! ---
        btnSend.setOnAction(event -> {
            sendText(txtMsg.getText());
            txtMsg.clear();
        });
        btnSave.setOnAction(event -> saveToFile());
        btnLoad.setOnAction(event -> loadFromFile());
        btnReset.setOnAction(event -> resetCanvas());
        // --- End button handlers addition ---

        canvas.setOnMousePressed(event -> {
            isPenMode = chbMode.getValue().equals("Pen") || chbMode.getValue().equals("Eraser");
            filledPixels.clear();
            if (isPenMode)
                penToData(event.getX(), event.getY());
        });
        canvas.setOnMouseDragged(event -> {
            if (isPenMode)
                penToData(event.getX(), event.getY());
        });
        canvas.setOnMouseReleased(event -> {
            if (!isPenMode)
                bucketToData(event.getX(), event.getY());
            if (!filledPixels.isEmpty()) {
                try {
                    sendPixelChanges();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            lastCol = -1;
            lastRow = -1;
        });

        initColorMap();
    }

    void sendPixelChanges() throws IOException {
        if (filledPixels.isEmpty()) return;

        int drawColor = getCurrentDrawColor();
        out.write(PIXELS);
        out.writeInt(drawColor);
        out.writeInt(filledPixels.size());
        for (Point p : filledPixels) {
            out.writeInt(p.x);
            out.writeInt(p.y);
        }
        out.flush();
        filledPixels.clear();
    }

    /**
     * Initialize color map
     */
    void initColorMap() throws IOException {
        Image image = new Image("file:color_map.png");
        ImageView imageView = new ImageView(image);
        imageView.setFitHeight(30.0);
        imageView.setPreserveRatio(true);
        panePicker.getChildren().add(imageView);

        double imageWidth = image.getWidth();
        double imageHeight = image.getHeight();
        double viewWidth = imageView.getBoundsInParent().getWidth();
        double viewHeight = imageView.getBoundsInParent().getHeight();
        double scaleX = imageWidth / viewWidth;
        double scaleY = imageHeight / viewHeight;

        pickColor(image, 0, 0, imageWidth, imageHeight);

        panePicker.setOnMouseClicked(event -> {
            double x = event.getX();
            double y = event.getY();
            int imgX = (int) (x * scaleX);
            int imgY = (int) (y * scaleY);
            pickColor(image, imgX, imgY, imageWidth, imageHeight);
        });
    }

    /**
     * Pick a color from the color map image
     */
    void pickColor(Image image, int imgX, int imgY, double imageWidth, double imageHeight) {
        if (imgX >= 0 && imgX < imageWidth && imgY >= 0 && imgY < imageHeight) {
            PixelReader reader = image.getPixelReader();
            selectedColorARGB = reader.getArgb(imgX, imgY);
            Color color = reader.getColor(imgX, imgY);
            paneColor.setStyle("-fx-background-color:#" + color.toString().substring(2));
        }
    }

    /**
     * Pen mode: update sketch data + filledPixels
     * Now supports brushSize (square brush)
     */
    void penToData(double mx, double my) {
        if (data == null) return;

        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int rows = data.length;
            int cols = data[0].length;
            double cellSizeX = padSize / cols;
            double cellSizeY = padSize / rows;

            int centerRow = (int) ((my - startY) / cellSizeY);
            int centerCol = (int) ((mx - startX) / cellSizeX);

            if (centerRow < 0 || centerRow >= rows || centerCol < 0 || centerCol >= cols) return;

            // Avoid reprocessing identical center repeatedly
            if (centerRow == lastRow && centerCol == lastCol) return;

            lastRow = centerRow;
            lastCol = centerCol;

            // Square brush of size brushSize x brushSize centered on (centerCol, centerRow)
            int half = brushSize / 2;
            int drawColor = getCurrentDrawColor();
            for (int dy = -half; dy <= half; dy++) {
                for (int dx = -half; dx <= half; dx++) {
                    int r = centerRow + dy;
                    int c = centerCol + dx;
                    if (r >= 0 && r < rows && c >= 0 && c < cols) {
                        if (data[r][c] != drawColor) {
                            data[r][c] = drawColor;
                            filledPixels.add(new Point(c, r));
                        }
                    }
                }
            }
        }

    }

    /**
     * Bucket mode
     */
    void bucketToData(double mx, double my) {
        if (data == null) return;

        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
            int rows = data.length;
            int cols = data[0].length;
            double cellSizeX = padSize / cols;
            double cellSizeY = padSize / rows;

            int row = (int) ((my - startY) / cellSizeY);
            int col = (int) ((mx - startX) / cellSizeX);
            if (row < 0 || row >= rows || col < 0 || col >= cols) return;

            paintArea(col, row);
        }
    }

    public void paintArea(int col, int row) {
        if (data == null) return;

        int oriColor = data[row][col];
        int fillColor = getCurrentDrawColor();
        LinkedList<Point> buffer = new LinkedList<Point>();
        if (oriColor != fillColor) {
            buffer.add(new Point(col, row));
            while (!buffer.isEmpty()) {
                Point p = buffer.removeFirst();
                col = p.x;
                row = p.y;

                if (data[row][col] != oriColor) continue;

                data[row][col] = fillColor;
                filledPixels.add(p);

                if (col > 0 && data[row][col - 1] == oriColor) buffer.add(new Point(col - 1, row));
                if (col < data[0].length - 1 && data[row][col + 1] == oriColor) buffer.add(new Point(col + 1, row));
                if (row > 0 && data[row - 1][col] == oriColor) buffer.add(new Point(col, row - 1));
                if (row < data.length - 1 && data[row + 1][col] == oriColor) buffer.add(new Point(col, row + 1));
            }
        }
    }

    Color fromARGB(int argb) {
        return Color.rgb(
                (argb >> 16) & 0xFF,
                (argb >> 8) & 0xFF,
                argb & 0xFF,
                ((argb >> 24) & 0xFF) / 255.0
        );
    }

    /**
     * Render the sketch data to the canvas
     */
    void render() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (data == null) return;

        double x = startX;
        double y = startY;
        int rows = data.length;
        int cols = data[0].length;

        double cellSizeX = padSize / cols;
        double cellSizeY = padSize / rows;

        gc.setStroke(Color.GRAY);
        for (int row = 0; row < rows; row++) {
            x = startX;
            for (int col = 0; col < cols; col++) {
                int argb = data[row][col];
                if (argb == CLEAR_COLOR) {
                    gc.setFill(Color.WHITE); // Use whatever your background color is.
                } else {
                    gc.setFill(fromARGB(argb));
                }
                double w = cellSizeX;
                double h = cellSizeY;
                gc.fillOval(x, y, w, h);
                gc.strokeOval(x, y, w, h);
                x += cellSizeX;
            }
            y += cellSizeY;
        }
    }

    private int getCurrentDrawColor() {
        String mode = chbMode.getValue();
        if (mode != null && mode.equals("Eraser")) {
            return CLEAR_COLOR;
        }
        return selectedColorARGB;
    }

    void resetCanvas() {
        if (data == null) return;

        int rows = data.length;
        int cols = data[0].length;
        // Set all pixels to CLEAR_COLOR (erased)
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                data[r][c] = CLEAR_COLOR;
            }
        }

        // Tell the server to update all clients (reuse LOAD_SKETCH type)
        try {
            out.write(LOAD_SKETCH);
            out.writeInt(rows);
            out.writeInt(cols);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    out.writeInt(CLEAR_COLOR);
                }
            }
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}