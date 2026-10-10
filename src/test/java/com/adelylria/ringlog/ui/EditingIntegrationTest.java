package com.adelylria.ringlog.ui;

import java.awt.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.view.SpeciesOption;
import com.adelylria.ringlog.repository.*;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.ui.panels.*;
import com.adelylria.ringlog.ui.components.WrappingPanel;
import com.adelylria.ringlog.update.UpdateCoordinator;

public final class EditingIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-editing-ux-");
        String oldPreferences = System.getProperty("ringlog.preferences.path");
        System.setProperty("ringlog.preferences.path", root.resolve("preferences.properties").toString());
        MainFrame[] window = new MainFrame[1];
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("data"));
            Database.initialize(paths.databasePath().toString());
            try (var db = Database.getConnection(paths.databasePath().toString()); var sql=db.createStatement()) {
                sql.execute("INSERT INTO species(id,stable_key,scientific_name) VALUES(1,'s','Test species')");
                sql.execute("INSERT INTO bird(id,stable_key,ring_number,species_id) VALUES(1,'b','TEST-01',1)");
                sql.execute("INSERT INTO bird_event(id,stable_key,bird_id,event_type,event_date,observations) VALUES(1,'e',1,'RINGING','2026-01-01','Original')");
            }
            var events = new BirdEventRepository(paths);
            var before = events.findDetail(1).orElseThrow();
            AtomicBoolean slow = new AtomicBoolean();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            CatalogRepository catalog = new CatalogRepository(paths.databasePath().toString()) {
                @Override public List<SpeciesOption> findSpeciesOptions() {
                    if(slow.get()) { entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Timed out"); }
                        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);} }
                    return super.findSpeciesOptions();
                }
            };
            AtomicLong saved = new AtomicLong();
            CapturePanel[] form = new CapturePanel[1];
            SwingUtilities.invokeAndWait(() -> {form[0]=new CapturePanel(events,catalog,saved::set,()->{});form[0].edit(before);});
            await(() -> find(form[0],"speciesField",JComboBox.class).getSelectedItem() != null,"Catalog load");
            SwingUtilities.invokeAndWait(() -> {
                require(!form[0].hasUnsavedChanges(),"Loading an event and its catalogs must not create edits");
                slow.set(true);form[0].refreshCatalogs();
            });
            require(entered.await(5,TimeUnit.SECONDS),"Slow catalog load started");
            SwingUtilities.invokeAndWait(() -> find(form[0],"observationsField",JTextArea.class).setText("My notes during loading"));
            release.countDown(); Thread.sleep(200);
            SwingUtilities.invokeAndWait(() -> {
                require(form[0].hasUnsavedChanges(),"Async catalog refresh cannot swallow text edits");
                form[0].setDiscardConfirmation(pane -> false);
                find(form[0],"cancelEventEdit",JButton.class).doClick();
                require(form[0].hasUnsavedChanges() && saved.get()==0,"Keep editing must not navigate or discard");
                find(form[0],"weightField",JTextField.class).setText("NaN");
                find(form[0],"saveEventButton",JButton.class).doClick();
                require(!find(form[0],"weightError",JLabel.class).getText().isBlank(),"Show the error next to weight");
            });
            require(events.findDetail(1).orElseThrow().equals(before),"Invalid input must not write the DB");
            SwingUtilities.invokeAndWait(() -> {
                find(form[0],"weightField",JTextField.class).setText("24,5");
                find(form[0],"saveEventButton",JButton.class).doClick();
                require(!form[0].confirmLeave(),"Cannot leave while Save is in progress");
                require(!find(form[0],"weightField",JTextField.class).isEnabled(),"Cannot edit fields during Save");
            });
            await(() -> saved.get()==1,"Save completes");
            SwingUtilities.invokeAndWait(() -> {
                require(!form[0].hasUnsavedChanges(),"Successful save clears dirty state");
                require(find(form[0],"weightField",JTextField.class).isEnabled(),"Fields are restored after Save");
            });
            require(events.findDetail(1).orElseThrow().weight()==24.5,"Decimal comma keeps the same stored units");

            PlacePanel places = new PlacePanel(catalog);
            SpeciesPanel species = new SpeciesPanel(catalog);
            SwingUtilities.invokeAndWait(() -> {
                places.setDiscardConfirmation(pane -> false);species.setDiscardConfirmation(pane -> false);
                find(places,"newPlaceButton",JButton.class).doClick();
                find(places,"placeName",JTextField.class).setText("Unsaved place");
                find(places,"cancelPlaceEditorButton",JButton.class).doClick();
                require(places.hasUnsavedChanges() && !places.confirmLeave(),"Place edits must be protected too");
                find(species,"newSpeciesButton",JButton.class).doClick();
                find(species,"speciesScientificName",JTextField.class).setText("Unsaved species");
                require(species.hasUnsavedChanges() && !species.confirmLeave(),"Species edits must be protected too");
                places.setDiscardConfirmation(pane -> true);require(places.confirmLeave(),"Explicit place discard");
                species.setDiscardConfirmation(pane -> true);require(species.confirmLeave(),"Explicit species discard");
            });

            if (!GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeAndWait(() -> {
                    window[0]=new MainFrame(paths);
                    window[0].navigate(MainFrame.NEW_CAPTURE);
                    CapturePanel editor=find(window[0],null,CapturePanel.class);
                    editor.setDiscardConfirmation(pane -> false);
                    find(editor,"ringNumberField",JTextField.class).setText("UNSAVED");
                    window[0].navigate(MainFrame.HOME);
                    require(editor.isVisible(),"Declining sidebar navigation must keep the active form");
                    require(!window[0].confirmLeaveCurrentEditor(),"Window closing must share the same guard");
                    editor.setDiscardConfirmation(pane -> true);
                    window[0].navigate(MainFrame.HOME);
                    require(!editor.isVisible(),"Accepted discard permits navigation");
                });
            }
            CaptureListPanel list=new CaptureListPanel(events,id->{},()->{});
            SwingUtilities.invokeAndWait(() -> {
                list.refresh();
                for(int width: new int[]{1000,800,600,1000}) {
                    list.setSize(width,700);
                    for(int pass=0;pass<8;pass++)layout(list);
                    for(String name:List.of("primaryFilterRow","dateFilterRow")) {
                        WrappingPanel row=find(list,name,WrappingPanel.class);
                        for(Component child:row.getComponents())if(child.isVisible()) {
                            require(child.getY()+child.getHeight()<=row.getHeight(),"Filter row clips controls at width "+width);
                            require(child.getX()+child.getWidth()<=row.getWidth(),"Filter control overflows at width "+width);
                        }
                    }
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var overlay = new com.adelylria.ringlog.ui.update.UpdateOverlay();
                    AtomicBoolean closed = new AtomicBoolean(), guarded = new AtomicBoolean();
                    var coordinator = UpdateCoordinator.application(overlay, () -> closed.set(true));
                    coordinator.setBeforeInstall(() -> {guarded.set(true);return false;});
                    var begin = UpdateCoordinator.class.getDeclaredMethod("beginDownload",com.adelylria.ringlog.update.UpdateAsset.class);
                    begin.setAccessible(true);begin.invoke(coordinator,new Object[]{null});
                    require(guarded.get() && !closed.get(),"Cancelled update must not download, launch installer or close the app");
                } catch(ReflectiveOperationException ex){throw new AssertionError(ex);}
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if(window[0]!=null)window[0].dispose(); });
            Thread.sleep(500);
            if(oldPreferences==null)System.clearProperty("ringlog.preferences.path");else System.setProperty("ringlog.preferences.path",oldPreferences);
            try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
        System.out.println("EditingIntegrationTest: PASS");
    }
    private static void layout(Component c) { if(c instanceof Container parent){parent.doLayout();for(Component child:parent.getComponents())layout(child);} }
    private static <T extends Component> T find(Component root,String name,Class<T> type) {
        if((name==null || name.equals(root.getName())) && type.isInstance(root))return type.cast(root);
        if(root instanceof Container parent)for(Component c:parent.getComponents()){T found=find(c,name,type);if(found!=null)return found;}
        return null;
    }
    private static void await(BooleanSupplier condition,String message)throws Exception {
        for(int i=0;i<150;i++){boolean[] done={false};SwingUtilities.invokeAndWait(()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(30);}
        throw new AssertionError(message);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
