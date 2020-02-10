package qupath.experimental.commands;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.classifiers.object.ObjectClassifiers;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.projects.Project;

/**
 * Command to apply a pre-trained object classifier to an image.
 * 
 * @author Pete Bankhead
 *
 */
public class ObjectClassifierLoadCommand implements PathCommand {
	
	private QuPathGUI qupath;
	
	private String title = "Object Classifiers";
	
	/**
	 * Constructor.
	 * @param qupath
	 */
	public ObjectClassifierLoadCommand(QuPathGUI qupath) {
		this.qupath = qupath;
	}

	@Override
	public void run() {
		
		var project = qupath.getProject();
		if (project == null) {
			Dialogs.showErrorMessage(title, "You need a project open to run this command!");
			return;
		}
		
		Collection<String> names;
		try {
			names = project.getObjectClassifiers().getNames();
			if (names.isEmpty()) {
				Dialogs.showErrorMessage(title, "No object classifiers were found in the current project!");
				return;
			}
		} catch (IOException e) {
			Dialogs.showErrorMessage(title, e);
			return;
		}
			
		var comboClassifiers = new ListView<String>();
		
		comboClassifiers.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		comboClassifiers.getItems().setAll(names);
//		var selectedClassifier = Bindings.createObjectBinding(() -> {
//			String name = comboClassifiers.getSelectionModel().getSelectedItem();
//			if (name != null) {
//				try {
//					return project.getObjectClassifiers().get(name);
//				} catch (Exception e) {
//					Dialogs.showErrorMessage("Load object model", e);
//				}
//			}
//			return null;
//		}, comboClassifiers.getSelectionModel().selectedItemProperty());
		
		// Provide an option to remove a classifier
		var popup = new ContextMenu();
		var miRemove = new MenuItem("Delete selected");
		popup.getItems().add(miRemove);
		miRemove.disableProperty().bind(comboClassifiers.getSelectionModel().selectedItemProperty().isNull());
		comboClassifiers.setContextMenu(popup);
		miRemove.setOnAction(e -> {
			var selected = comboClassifiers.getSelectionModel().getSelectedItem();
			if (selected == null || project == null)
				return;
			try {
				if (!project.getObjectClassifiers().getNames().contains(selected)) {
					Dialogs.showErrorMessage(title, "Unable to delete " + selected + " - not found in the current project");
					return;
				}
				if (!Dialogs.showConfirmDialog(title, "Are you sure you want to delete '" + selected + "'?"))
					return;
				project.getObjectClassifiers().remove(selected);
				comboClassifiers.getItems().remove(selected);
			} catch (Exception ex) {
				Dialogs.showErrorMessage("Error deleting classifier", ex);
			}
		});
		

		var label = new Label("Choose classifier");
		label.setLabelFor(comboClassifiers);
		
//		var enableButtons = qupath.viewerProperty().isNotNull().and(selectedClassifier.isNotNull());
		var btnApplyClassifier = new Button("Apply classifier");
		btnApplyClassifier.textProperty().bind(Bindings.createStringBinding(() -> {
			if (comboClassifiers.getSelectionModel().getSelectedItems().size() > 1)
				return "Apply classifiers sequentially";
			return "Apply classifier";
		}, comboClassifiers.getSelectionModel().getSelectedItems()));
		btnApplyClassifier.disableProperty().bind(comboClassifiers.getSelectionModel().selectedItemProperty().isNull());
		
		btnApplyClassifier.setOnAction(e -> {
			ObjectClassifier<BufferedImage> classifier = null;
			try {
				classifier = getClassifier(project, comboClassifiers.getSelectionModel().getSelectedItems());
			} catch (IOException ex) {
				Dialogs.showErrorMessage(title, ex);
				return;
			}
			for (var viewer : qupath.getViewers()) {
				var imageData = viewer.getImageData();
				if (imageData != null) {
					if (classifier.classifyObjects(imageData, true) > 0)
						imageData.getHierarchy().fireHierarchyChangedEvent(classifier);
				}
			}
		});
		
//		var pane = new BorderPane();
//		pane.setPadding(new Insets(10.0));
//		pane.setTop(label);
//		pane.setCenter(comboClassifiers);
//		pane.setBottom(btnApplyClassifier);

		var pane = new GridPane();
		pane.setPadding(new Insets(10.0));
		pane.setHgap(5);
		pane.setVgap(10);
		int row = 0;
		PaneTools.setFillWidth(Boolean.TRUE, label, comboClassifiers, btnApplyClassifier);
		PaneTools.setVGrowPriority(Priority.ALWAYS, comboClassifiers);
		PaneTools.setHGrowPriority(Priority.ALWAYS, label, comboClassifiers, btnApplyClassifier);
		PaneTools.setMaxWidth(Double.MAX_VALUE, label, comboClassifiers, btnApplyClassifier);
		PaneTools.addGridRow(pane, row++, 0, "Choose object classification model to apply to the current image", label);
		PaneTools.addGridRow(pane, row++, 0, "Choose object classification model to apply to the current image", comboClassifiers);
		PaneTools.addGridRow(pane, row++, 0, "Apply object classification to all open images", btnApplyClassifier);
		
		PaneTools.setMaxWidth(Double.MAX_VALUE, comboClassifiers, btnApplyClassifier);
				
		var stage = new Stage();
		stage.setTitle(title);
		stage.setScene(new Scene(pane));
		stage.initOwner(qupath.getStage());
		stage.sizeToScene();
//		stage.setResizable(false);
		stage.show();
		
	}
	
	
	ObjectClassifier<BufferedImage> getClassifier(Project<BufferedImage> project, List<String> names) throws IOException {
		if (names.isEmpty())
			return null;
		if (names.size() == 1)
			return project.getObjectClassifiers().get(names.get(0));
		List<ObjectClassifier<BufferedImage>> classifiers = new ArrayList<>();
		for (var s : names) {
			classifiers.add(project.getObjectClassifiers().get(s));
		}
		return ObjectClassifiers.createCompositeClassifier(classifiers);
	}
	

}
