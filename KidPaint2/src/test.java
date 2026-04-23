//import javafx.animation.AnimationTimer;
//import javafx.application.Platform;
//import javafx.fxml.FXMLLoader;
//import javafx.fxml.FXML;
//
//import javafx.scene.Scene;
//import javafx.scene.canvas.Canvas;
//import javafx.scene.canvas.GraphicsContext;
//import javafx.scene.control.Button;
//import javafx.scene.control.ChoiceBox;
//import javafx.scene.control.TextArea;
//import javafx.scene.control.TextField;
//import javafx.scene.image.Image;
//import javafx.scene.image.ImageView;
//import javafx.scene.image.PixelReader;
//import javafx.scene.layout.Pane;
//import javafx.scene.paint.Color;
//import javafx.stage.Stage;
//import javafx.scene.Parent;
//
//import javax.xml.soap.Text;
//import java.io.DataInputStream;
//import java.io.DataOutputStream;
//import java.io.IOException;
//import java.net.Socket;
//import java.util.LinkedList;
//
//public class MainWindow {
//
//    // Protocol message types
//    final int NAME = 0;
//    final int PIXELS = 1;
//    final int MESSAGE = 2;
//    // *** MODIFICATION START ***
//    final int INITIAL_SKETCH = 3; // New type for receiving the full sketch
//    // *** MODIFICATION END ***
//
//
//    Socket socket;
//    DataInputStream in;
//    DataOutputStream out;
//
//    @FXML ChoiceBox<String> chbMode;
//    @FXML Canvas canvas;
//    @FXML Pane container;
//    @FXML Pane panePicker;
//    @FXML Pane paneColor;
//    @FXML Button btnSend;
//    @FXML Button btnSave;
//    @FXML Button btnLoad;
//    @FXML TextField txtMsg;
//    @FXML TextArea areaMsg;
//
//    String username;
//    int numPixels = 10;
//    Stage stage;
//    AnimationTimer animationTimer;
//    int[][] data;
//    double pixelSize, padSize, startX, startY;
//    int selectedColorARGB;
//    boolean isPenMode = true;
//    LinkedList<Point> filledPixels = new LinkedList<Point>();
//
//    int lastRow = -1;
//    int lastCol = -1;
//
//    class Point{
//        int x, y;
//        Point(int x, int y) {
//            this.x = x;
//            this.y = y;
//        }
//    }
//
//    public MainWindow(Stage stage, String username) throws IOException {
//        this.username = username;
//
//        socket = new Socket("127.0.0.1", 12345);
//        in = new DataInputStream(socket.getInputStream());
//        out = new DataOutputStream(socket.getOutputStream());
//
//        // Send username to server
//        out.write(NAME);
//        out.writeInt(username.length());
//        out.write(username.getBytes());
//        out.flush();
//
//        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainWindownUI.fxml"));
//        loader.setController(this);
//        Parent root = loader.load();
//        Scene scene = new Scene(root);
//
//        this.stage = stage;
//        stage.setScene(scene);
//        stage.setMinWidth(scene.getWidth());
//        stage.setMinHeight(scene.getHeight());
//
//        canvas.widthProperty().bind(container.widthProperty());
//        canvas.heightProperty().bind(container.heightProperty());
//        canvas.widthProperty().addListener(w->onCanvasSizeChange());
//        canvas.heightProperty().addListener(h->onCanvasSizeChange());
//
//        btnSend.setOnAction(event -> {
//            sendText(txtMsg.getText());
//            txtMsg.clear();
//        });
//
//        stage.setOnCloseRequest(event -> quit());
//        stage.show();
//        initial();
//
//        animationTimer.start();
//
//        // Start a new thread to listen for data from the server
//        Thread thread = new Thread(this::receiveData);
//        thread.setDaemon(true); // Allows the app to exit even if this thread is running
//        thread.start();
//    }
//
//    void sendText(String text){
//        if (text == null || text.trim().isEmpty()) return;
//        try {
//            out.write(MESSAGE);
//            byte[] textBytes = text.getBytes();
//            out.writeInt(textBytes.length);
//            out.write(textBytes);
//            out.flush();
//        }catch (IOException ex) {
//            System.out.println("Connection is dropped!");
//        }
//    }
//
//    // *** MODIFIED METHOD ***
//    void receiveData(){
//        try {
//            // The first thing the server sends is the initial sketch.
//            // We must read this before entering the main loop.
//            receiveInitialSketch();
//
//            // After getting the initial sketch, loop forever to get live updates.
//            while (true) {
//                int datatype = in.read(); // Blocks until data is available
//                switch (datatype) {
//                    case PIXELS:
//                        receivePixels();
//                        break;
//                    case MESSAGE:
//                        receiveMsg();
//                        break;
//                    default:
//                        // Handle unexpected data type if necessary
//                        System.out.println("Received unknown data type: " + datatype);
//                        break;
//                }
//            }
//        } catch (IOException e){
//            Platform.runLater(() -> areaMsg.appendText("--- Disconnected from server ---\n"));
//            System.out.println("Disconnected from server.");
//        }
//    }
//
//    // *** NEW METHOD ***
//    /**
//     * Receives the full sketch data from the server. This is expected to be
//     * called once upon connecting.
//     */
//    void receiveInitialSketch() throws IOException {
//        int type = in.read();
//        if (type != INITIAL_SKETCH) {
//            throw new IOException("Protocol error: Expected INITIAL_SKETCH (3), but got " + type);
//        }
//
//        int rows = in.readInt();
//        int cols = in.readInt();
//
//        // Initialize data array if it's different, though it's fixed at 10x10
//        if (rows != data.length || cols != data[0].length) {
//            data = new int[rows][cols];
//            numPixels = rows; // Update numPixels if dynamic
//        }
//
//        for (int row = 0; row < rows; row++) {
//            for (int col = 0; col < cols; col++) {
//                data[row][col] = in.readInt();
//            }
//        }
//        System.out.println("Initial sketch received and loaded.");
//    }
//
//    void receiveMsg() throws IOException{
//        int size = in.readInt();
//        byte[] buffer = new byte[size];
//        in.read(buffer,0,size);
//        String msg = new String(buffer, 0, size);
//
//        // UI updates must be on the JavaFX Application Thread
//        Platform.runLater(() -> areaMsg.appendText(msg + "\n"));
//    }
//
//    void receivePixels() throws IOException {
//        int color = in.readInt();
//        int size = in.readInt();
//        for (int i=0;i<size;i++){
//            int x = in.readInt();
//            int y = in.readInt();
//            // Ensure coordinates are within bounds before updating
//            if (y >= 0 && y < data.length && x >= 0 && x < data[0].length) {
//                data[y][x] = color;
//            }
//        }
//    }
//
//    void onCanvasSizeChange() {
//        double w = canvas.getWidth();
//        double h = canvas.getHeight();
//        padSize = Math.min(w, h);
//        startX = (w - padSize)/2;
//        startY = (h - padSize)/2;
//        pixelSize = padSize / numPixels;
//    }
//
//    void quit() {
//        System.out.println("Bye bye");
//        try {
//            if (socket != null && !socket.isClosed()) {
//                socket.close();
//            }
//        } catch (IOException e) {
//            // Ignore
//        }
//        stage.close();
//        System.exit(0);
//    }
//
//    void initial() throws IOException {
//        data = new int[numPixels][numPixels]; // Initially blank
//
//        animationTimer = new AnimationTimer() {
//            @Override
//            public void handle(long l) {
//                render();
//            }
//        };
//
//        chbMode.setValue("Pen");
//
//        canvas.setOnMousePressed(event -> {
//            isPenMode = chbMode.getValue().equals("Pen");
//            filledPixels.clear();
//            if (isPenMode)
//                penToData(event.getX(), event.getY());
//        });
//
//        canvas.setOnMouseDragged(event -> {
//            if (isPenMode)
//                penToData(event.getX(), event.getY());
//        });
//
//        canvas.setOnMouseReleased(event->{
//            if (!isPenMode)
//                bucketToData(event.getX(), event.getY());
//
//            if (!filledPixels.isEmpty()) {
//                try {
//                    sendPixelChanges();
//                } catch (IOException e) {
//                    System.err.println("Failed to send pixel changes: " + e.getMessage());
//                }
//            }
//            lastCol = -1;
//            lastRow = -1;
//        });
//
//        initColorMap();
//    }
//
//    void sendPixelChanges() throws IOException {
//        out.write(PIXELS);
//        out.writeInt(selectedColorARGB);
//        out.writeInt(filledPixels.size());
//
//        for (Point p : filledPixels) {
//            out.writeInt(p.x);
//            out.writeInt(p.y);
//        }
//        out.flush();
//    }
//
//    void initColorMap() throws IOException {
//        Image image = new Image("file:color_map.png");
//        ImageView imageView = new ImageView(image);
//
//        imageView.setFitHeight(30.0);
//        imageView.setPreserveRatio(true);
//        panePicker.getChildren().add(imageView);
//
//        double imageWidth = image.getWidth();
//        double imageHeight = image.getHeight();
//        double viewWidth = imageView.getBoundsInParent().getWidth();
//        double viewHeight = imageView.getBoundsInParent().getHeight();
//
//        double scaleX = imageWidth / viewWidth;
//        double scaleY = imageHeight / viewHeight;
//
//        pickColor(image, 0, 0, imageWidth, imageHeight);
//
//        panePicker.setOnMouseClicked(event -> {
//            double x = event.getX();
//            double y = event.getY();
//            int imgX = (int)(x * scaleX);
//            int imgY = (int)(y * scaleY);
//            pickColor(image, imgX, imgY, imageWidth, imageHeight);
//        });
//    }
//
//    void pickColor(Image image, int imgX, int imgY, double imageWidth, double imageHeight) {
//        if (imgX >= 0 && imgX < imageWidth && imgY >= 0 && imgY < imageHeight) {
//            PixelReader reader = image.getPixelReader();
//            selectedColorARGB = reader.getArgb(imgX, imgY);
//            Color color = reader.getColor(imgX, imgY);
//            paneColor.setStyle("-fx-background-color:#" + color.toString().substring(2));
//        }
//    }
//
//    void penToData(double mx, double my) {
//        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
//            int row = (int) ((my - startY) / pixelSize);
//            int col = (int) ((mx - startX) / pixelSize);
//
//            if (row!= lastRow || col != lastCol) {
//                if (data[row][col] != selectedColorARGB) {
//                    data[row][col] = selectedColorARGB;
//                    filledPixels.add(new Point(col, row));
//                }
//                lastRow = row;
//                lastCol = col;
//            }
//        }
//    }
//
//    void bucketToData(double mx, double my) {
//        if (mx > startX && mx < startX + padSize && my > startY && my < startY + padSize) {
//            int row = (int) ((my - startY) / pixelSize);
//            int col = (int) ((mx - startX) / pixelSize);
//            paintArea(col, row);
//        }
//    }
//
//    public void paintArea(int col, int row) {
//        int oriColor = data[row][col];
//        LinkedList<Point> buffer = new LinkedList<Point>();
//
//        if (oriColor != selectedColorARGB) {
//            buffer.add(new Point(col, row));
//
//            while(!buffer.isEmpty()) {
//                Point p = buffer.removeFirst();
//                col = p.x;
//                row = p.y;
//
//                if (data[row][col] != oriColor) continue;
//
//                data[row][col] = selectedColorARGB;
//                filledPixels.add(p);
//
//                if (col > 0 && data[row][col-1] == oriColor) buffer.add(new Point(col-1, row));
//                if (col < data[0].length - 1 && data[row][col+1] == oriColor) buffer.add(new Point(col+1, row));
//                if (row > 0 && data[row-1][col] == oriColor) buffer.add(new Point(col, row-1));
//                if (row < data.length - 1 && data[row+1][col] == oriColor) buffer.add(new Point(col, row+1));
//            }
//        }
//    }
//
//    Color fromARGB(int argb) {
//        return Color.rgb(
//                (argb >> 16) & 0xFF,
//                (argb >> 8) & 0xFF,
//                argb & 0xFF,
//                ((argb >> 24) & 0xFF) / 255.0
//        );
//    }
//
//    void render() {
//        GraphicsContext gc = canvas.getGraphicsContext2D();
//        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
//
//        double x = startX;
//        double y = startY;
//
//        gc.setStroke(Color.GRAY);
//        // Corrected loop: iterate through rows first, then columns
//        for (int row = 0; row < numPixels; row++) {
//            for (int col = 0; col < numPixels; col++) {
//                // Corrected indexing: use data[row][col]
//                gc.setFill(fromARGB(data[row][col]));
//                gc.fillOval(x, y, pixelSize, pixelSize);
//                gc.strokeOval(x, y, pixelSize, pixelSize);
//                x += pixelSize;
//            }
//            x = startX;
//            y += pixelSize;
//        }
//    }
//}