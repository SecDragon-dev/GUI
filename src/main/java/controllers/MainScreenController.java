package controllers;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import general.Assumption;
import general.Configuration;
import general.Constants;
import general.Utilities;
import io.ConfigManager;
import io.ModelReader;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.text.Text;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import network.AnalysisConnector;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// TODO: Make use of @NotNull and @Nullable annotations to avoid NullPointer-Exceptions.

public class MainScreenController {
    private final String defaultSaveLocation = Constants.USER_HOME_PATH + Constants.FILE_SYSTEM_SEPARATOR + "NewAssumptionSet.xml";
    private Configuration currentConfiguration;
    private Configuration savedConfiguration;
    private AnalysisConnector analysisConnector;
    private HostServices hostServices;
    private File saveFile;
    private Map<String, Map<String, ModelReader.ModelEntity>> modelEntityMap;

    @FXML
    private Button performAnalysisButton;
    @FXML
    private TableView<Assumption> assumptionTableView;
    @FXML
    private TableColumn<Assumption, UUID> idColumn;
    @FXML
    private TableColumn<Assumption, Assumption.AssumptionType> typeColumn;
    @FXML
    private TableColumn<Assumption, Set<String>> entitiesColumn;
    @FXML
    private TableColumn<Assumption, Set<UUID>> dependenciesColumn;
    @FXML
    private TableColumn<Assumption, String> descriptionColumn;
    @FXML
    private TableColumn<Assumption, Double> violationProbabilityColumn;
    @FXML
    private TableColumn<Assumption, Double> riskColumn;
    @FXML
    private TableColumn<Assumption, String> impactColumn;
    @FXML
    private TableColumn<Assumption, Boolean> analyzedColumn;
    @FXML
    private TextArea analysisOutputTextArea;
    @FXML
    private Label analysisPathLabel;
    @FXML
    private Label modelNameLabel;

    public MainScreenController() {
        this.savedConfiguration = new Configuration();
        this.currentConfiguration = new Configuration();
    }

    @FXML
    private void initialize() {
        this.idColumn.setCellValueFactory(new PropertyValueFactory<>("id"));
        this.typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        this.entitiesColumn.setCellValueFactory(new PropertyValueFactory<>("affectedEntities"));
        this.dependenciesColumn.setCellValueFactory(new PropertyValueFactory<>("dependencies"));
        this.descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));
        this.violationProbabilityColumn.setCellValueFactory(new PropertyValueFactory<>("probabilityOfViolation"));
        this.riskColumn.setCellValueFactory(new PropertyValueFactory<>("risk"));
        this.impactColumn.setCellValueFactory(new PropertyValueFactory<>("impact"));
        this.analyzedColumn.setCellValueFactory(new PropertyValueFactory<>("analyzed"));

        // TODO: Tidy this up.
        this.descriptionColumn.setCellFactory(tc -> {
            var cell = new TableCell<Assumption, String>();
            var text = new Text();
            cell.setGraphic(text);
            cell.setPrefHeight(Control.USE_COMPUTED_SIZE);
            text.wrappingWidthProperty().bind(this.descriptionColumn.widthProperty());
            text.textProperty().bind(cell.itemProperty());
            return cell ;
        });
        this.impactColumn.setCellFactory(tc -> {
            var cell = new TableCell<Assumption, String>();
            var text = new Text();
            cell.setGraphic(text);
            cell.setPrefHeight(Control.USE_COMPUTED_SIZE);
            text.wrappingWidthProperty().bind(this.impactColumn.widthProperty());
            text.textProperty().bind(cell.itemProperty());
            return cell ;
        });
    }

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    public void handleExitRequest(WindowEvent windowEvent) {
        if (this.hasUnsavedChanges()) {
            var confirmationResult = Utilities.showAlert(Alert.AlertType.CONFIRMATION,
                    "Unsaved Changes",
                    "There exist unsaved changed in the current configuration",
                    "Quit and discard the changes?");

            if (confirmationResult.isPresent() && confirmationResult.get() == ButtonType.CANCEL) {
                windowEvent.consume();
            }
        }
    }

    private boolean testAnalysisConnection(String uri) {
        this.analysisConnector = new AnalysisConnector(uri);

        // Test connection to analysis.
        var connectionSuccess = this.analysisConnector.testConnection().getKey() == 200;

        this.analysisPathLabel.setText((uri == null ? "Not specified" : uri) + (connectionSuccess ? " ✓" : " ❌"));
        return connectionSuccess;
    }

    private boolean hasUnsavedChanges() {
        return !this.currentConfiguration.equals(this.savedConfiguration);
    }

    @FXML
    private void handleNewAssumption(ActionEvent actionEvent) {
        if (this.modelEntityMap == null || this.modelEntityMap.isEmpty()) {
            Utilities.showAlert(Alert.AlertType.WARNING,
                    "Warning",
                    "Unable to create a new assumption!",
                    "A path to a valid model has to be set.");
            return;
        }

        // Create new modal window for entry of assumption parameters.
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("../UI/AssumptionSpecificationScreen.fxml"));
            AnchorPane root = loader.load();

            AssumptionSpecificationScreenController controller = loader.getController();
            Assumption newAssumption = new Assumption();
            controller.initAssumption(newAssumption);
            controller.initModelEntities(this.modelEntityMap);

            var stage = new Stage();
            stage.setScene(new Scene(root));
            stage.setTitle("Assumption Specification");
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(((MenuItem) actionEvent.getSource()).getParentPopup().getOwnerWindow().getScene().getWindow());
            stage.showAndWait();

            // Only add assumption in case it was fully specified by the user.
            if (newAssumption.isFullySpecified()) {
                this.currentConfiguration.getAssumptions().add(newAssumption);
                this.assumptionTableView.getItems().add(newAssumption);
                this.performAnalysisButton.setDisable(this.currentConfiguration.isMissingAnalysisParameters());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @FXML
    private void newAnalysis() {
        // TODO Leverage isSavedField.
        System.out.println("Not implemented!");
    }

    @FXML
    private void openFromFile(ActionEvent actionEvent) {
        var stage = (Stage) ((MenuItem) actionEvent.getSource()).getParentPopup().getOwnerWindow();

        var fileChooser = new FileChooser();
        fileChooser.setTitle("Select an existing File");
        fileChooser.setInitialFileName("NewAssumptions.xml");
        fileChooser.setInitialDirectory(new File(Constants.USER_HOME_PATH));
        var selectedFile = fileChooser.showOpenDialog(stage);

        // File selection has been aborted by the user.
        if (selectedFile == null) {
            return;
        }

        if (!selectedFile.exists() || !selectedFile.isFile()) {
            var alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Warning");
            alert.setHeaderText("Loading Failed");
            alert.setContentText("The specified file could not be read!");

            alert.show();
        }

        try {
            this.savedConfiguration = ConfigManager.readConfig(selectedFile);
            this.currentConfiguration = this.savedConfiguration.clone();

            // Analysis
            this.testAnalysisConnection(this.currentConfiguration.getAnalysisPath());

            // Init model entities from read model path if model is already specified.
            this.modelEntityMap = (this.currentConfiguration.getModelPath() != null) ?
                    ModelReader.readModel(new File(this.currentConfiguration.getModelPath())) : null;

            // Model
            var folders = this.currentConfiguration.getModelPath().split(Constants.FILE_SYSTEM_SEPARATOR.equals("\\") ? "\\\\" : Constants.FILE_SYSTEM_SEPARATOR);
            this.modelNameLabel.setText(folders[folders.length - 1]);

            // Assumptions
            this.assumptionTableView.getItems().clear();
            this.assumptionTableView.getItems().addAll(this.currentConfiguration.getAssumptions());

            // Analysis result
            this.analysisOutputTextArea.setText(this.currentConfiguration.getAnalysisResult());

            this.saveFile = selectedFile;
            this.performAnalysisButton.setDisable(this.currentConfiguration.isMissingAnalysisParameters());
        } catch (StreamReadException e) {
            Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Opening file failed", "The specified file exhibits an invalid structure.");
        } catch (DatabindException e) {
            e.printStackTrace();
            Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Opening file failed", "Could not map the contents of the specified file to a valid Configuration.");
        } catch (FileNotFoundException e) {
            Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Opening file failed", "Could not find the repository file associated with the the specified model.");
        } catch (IOException e) {
            Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Opening file failed", "Encountered a low-level I/O problem when trying to read from the file.");
        }
    }

    @FXML
    private void openRecent() {
        // TODO Maybe start with recent files in the current execution. Otherwise some form of persistent config file is required.
        System.out.println("Not implemented!");
    }

    @FXML
    private void saveToFile() {
        if (this.currentConfiguration.isEmpty()) {
            return;
        }

        // Use default file if not otherwise set by the user.
        if (this.saveFile == null) {
            this.saveFile = new File(this.defaultSaveLocation);
        }

        // Avoid overwriting in case a file with the default name already exists.
        if (this.saveFile.exists() && this.saveFile.getAbsolutePath().equals(this.defaultSaveLocation)) {
            // Add number suffix until there is no conflict.
            int suffix = 1;
            do {
                this.saveFile = new File(this.defaultSaveLocation.substring(0, this.defaultSaveLocation.length() - 4) + suffix + ".xml");
                suffix++;
            } while (this.saveFile.exists());
        }

        // Write to save-file.
        try {
            this.saveFile.createNewFile();
            ConfigManager.writeConfig(this.saveFile, this.currentConfiguration);
            this.savedConfiguration = this.currentConfiguration;
        } catch (IOException e) {
            Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Saving failed", "Could not write to file!");
            e.printStackTrace();
        }
    }

    @FXML
    private void saveAs(ActionEvent actionEvent) {
        var stage = (Stage) ((MenuItem) actionEvent.getSource()).getParentPopup().getOwnerWindow();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Save Location");
        fileChooser.setInitialFileName("NewAssumptions.json");
        fileChooser.setInitialDirectory(new File(Constants.USER_HOME_PATH));
        this.saveFile = fileChooser.showSaveDialog(stage);

        this.saveToFile();
    }

    @FXML
    private void handleQuit() {
        if (!this.savedConfiguration.equals(this.currentConfiguration)) {
            var confirmationResult = Utilities.showAlert(Alert.AlertType.CONFIRMATION,
                    "Unsaved Changes",
                    "There exist unsaved changed in the current configuration",
                    "Quit and discard the changes?");

            if (confirmationResult.isPresent() && confirmationResult.get() == ButtonType.CANCEL) {
                return;
            }
        }

        Platform.exit();
    }

    @FXML
    private void openDocumentation() {
        this.hostServices.showDocument(Constants.DOCUMENTATION_URL);
    }

    @FXML
    private void handleAnalysisExecution() {
        // Check whether forwarding the request to the analysis makes sense.
        if (!this.currentConfiguration.isMissingAnalysisParameters()) {
            if (this.testAnalysisConnection(this.currentConfiguration.getAnalysisPath())) {
                var analysisResponse = this.analysisConnector.performAnalysis(
                        new AnalysisConnector.AnalysisParameter(this.currentConfiguration.getModelPath(), this.currentConfiguration.getAssumptions()));

                if (analysisResponse.getKey() != 0) {
                    this.analysisOutputTextArea.setText(analysisResponse.getValue());
                    this.currentConfiguration.setAnalysisResult(analysisResponse.getValue());
                } else {
                    Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Communication with the analysis failed.", analysisResponse.getValue());
                }
            } else {
                Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Communication with the analysis failed.", "Connection to the analysis could not be established.");
            }
        } else {
            // TODO Error
            System.out.println("Error (Not implemented)");
        }
    }

    @FXML
    private void handleAnalysisPathSelection() {
        var textInputDialog = new TextInputDialog(this.currentConfiguration.getAnalysisPath() == null ? Constants.DEFAULT_ANALYSIS_PATH : this.currentConfiguration.getAnalysisPath());
        textInputDialog.setTitle("Analysis URI");
        textInputDialog.setHeaderText("Please provide the web-service URI of the analysis.");
        textInputDialog.setContentText("URI:");

        var userInput = textInputDialog.showAndWait();

        userInput.ifPresent(input -> {
            this.currentConfiguration.setAnalysisPath(input);
            this.testAnalysisConnection(input);
            this.performAnalysisButton.setDisable(this.currentConfiguration.isMissingAnalysisParameters());
        });
    }

    @FXML
    private void handleModelNameSelection(MouseEvent mouseEvent) {
        var originatingLabel = (Label) mouseEvent.getSource();
        var stage = (Stage) originatingLabel.getScene().getWindow();

        var directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Model Folder");
        directoryChooser.setInitialDirectory(new File(Constants.USER_HOME_PATH));
        var selectedFolder = directoryChooser.showDialog(stage);

        // Check whether the user aborted the selection.
        if (selectedFolder != null) {
            var absolutePathToSelectedFolder = selectedFolder.getAbsolutePath();

            // Check whether the specified folder actually contains a repository file.
            File modelFolder = new File(absolutePathToSelectedFolder);
            if (modelFolder.exists()) {
                // Load contents of repository file.
                this.modelEntityMap = ModelReader.readModel(modelFolder);

                // Accept valid selection.
                this.currentConfiguration.setModelPath(absolutePathToSelectedFolder);
                // Only display last subfolder of the path for better readability.
                var folders = this.currentConfiguration.getModelPath().split(Constants.FILE_SYSTEM_SEPARATOR.equals("\\") ? "\\\\" : Constants.FILE_SYSTEM_SEPARATOR);
                originatingLabel.setText(folders[folders.length - 1]);

                this.performAnalysisButton.setDisable(this.currentConfiguration.isMissingAnalysisParameters());
            } else {
                // Invalid selection due to missing repository file.
                Utilities.showAlert(Alert.AlertType.ERROR, "Error", "Loading model entities failed", "The repository file (default.repository) of the specified model could not be found.");
            }
        }
    }
}
