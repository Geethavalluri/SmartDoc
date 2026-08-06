package com.smartdoc;

import java.util.logging.Logger;

import com.smartdoc.ui.SmartDocController;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;




//Developer password :  smartdoc-dev

/**
 * Main JavaFX Application for SmartDoc
 */
public class SmartDocApplication extends Application {
    private static final Logger logger = Logger.getLogger(SmartDocApplication.class.getName());

    @Override
    public void start(Stage primaryStage) {
        try {
            // Load the FXML file
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            Parent root = loader.load();

            // Get the controller
            SmartDocController controller = loader.getController();

            // Create scene
            Scene scene = new Scene(root, 1200, 800);

            // Configure stage
            primaryStage.setTitle("SmartDoc - Local Document Search");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1000);
            primaryStage.setMinHeight(700);

            // Initialize controller with stage reference
            controller.setPrimaryStage(primaryStage);

            primaryStage.show();

            logger.info("SmartDoc application started successfully");

        } catch (Exception e) {
            logger.severe("Failed to start SmartDoc application: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to start application", e);
        }
    }

    @Override
    public void stop() {
        logger.info("SmartDoc application stopping");
        // Cleanup will be handled by controller
    }

    public static void main(String[] args) {
        launch(args);
    }
}
//hii vunnana
