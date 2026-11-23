package com.erdstudio;

import com.erdstudio.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public final class App extends Application {
    @Override
    public void start(Stage stage) {
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            var in = App.class.getResourceAsStream("/com/erdstudio/icons/icon-" + size + ".png");
            if (in != null) stage.getIcons().add(new Image(in));
        }
        MainWindow w = new MainWindow(stage);
        w.show(getParameters().getRaw());
        SingleInstance.onMcp(w.mcpHandler());
        SingleInstance.onFiles(files -> javafx.application.Platform.runLater(() -> w.openFromOutside(files)));
    }

    @Override
    public void stop() {
        SingleInstance.stop();
    }
}
