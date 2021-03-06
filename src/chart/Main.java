/******************************************************************************
* Title: Chart - Main Source File
* Author: Mike Schoonover
* Date: 03/12/08
*
* Purpose:
*
* This program provides display, charting, motion tracking, data saving,
* event marking, and firing external hardware event markers (paint markers,
* sirens, signal lights).
*
* Open Source Policy:
*
* This source code is Public Domain and free to any interested party.  Any
* person, company, or organization may do with it as they please.
*
*/

//-----------------------------------------------------------------------------

package chart;

import chart.mksystems.hardware.AScan;
import chart.mksystems.hardware.Channel;
import chart.mksystems.hardware.EncoderCalValues;
import chart.mksystems.hardware.Hardware;
import chart.mksystems.inifile.IniFile;
import chart.mksystems.menu.MainMenu;
import chart.mksystems.settings.Link;
import chart.mksystems.settings.Settings;
import chart.mksystems.stripchart.ChartGroup;
import chart.mksystems.tools.MultipleInstancePreventer;
import chart.mksystems.tools.SwissArmyKnife;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class MainThread
//
// This thread drives the data collection from the hardware.
//
//

class MainThread extends Thread {

    int i = 0;

    public Hardware hardware;
    Settings settings;
    UTCalibrator calWindow;

//-----------------------------------------------------------------------------
// MainThread::MainThread (constructor)
//

public MainThread(UTCalibrator pCalWindow, Settings pSettings)
{

    super("Main Thread");

    calWindow = pCalWindow; settings = pSettings;

}//end of MainThread::MainThread (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainThread::run
//
// Note that if the thread has called hardware.connect and the boards are not
// responding, it may be a while before the thread will respond to an
// interrupt. Also, an interrupt may be missed (caught by some code in
// hardware.connect?) so repeated interrupts should be invoked until the
// thread catches one and dies.
//
// Generally speaking, no methods called by hardware.connect should catch
// InterruptedException so that and interrupt will return here to be caught.
//

@Override
public void run() {

    try{
        while (true){

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            //if connection has not been made to the remotes, do so
            if (!hardware.connected) {hardware.connect();}

            //if the hardware is not active (i.e. has been shut down) bail out
            if (!hardware.active) { return; }

            //turn off all hardware functions
            if (settings.triggerHardwareShutdown){
                hardware.shutDown();
                return;
            }

            //run any miscellaneous background processes
            hardware.runBackgroundProcesses();

            //the hardware.connect function will not return until all boards
            //are setup, so the setup can access sockets without worry of
            //collision with the hardware.collectData function which also
            //accesses those same sockets - after the connect call returns,
            //then collectData will repeatedly be called from this timer and
            //other functions should not read from the sockets to avoid
            //collision with collectData

            //trigger data collection from remote devices
            //this does not display the data - that is handled by a timer

            hardware.collectData();

            //if the calibration window is active, request aScan packets
            //thus, if the user clicks off the window, the AScan will freeze
            //if the currrently selected channel is configured to never freeze,
            //then request aScan packets even if the window is not active
            
            if (calWindow.channels != null &&
                    calWindow.channels[calWindow.currentChannelIndex] != null){
            
                if (calWindow.isActive() || 
                       !calWindow.channels[calWindow.currentChannelIndex].
                                                    freezeScopeWhenNotInFocus){
                    hardware.requestAScan(calWindow.
                         channels[calWindow.currentChannelIndex].channelNum);
                    }
            }// if (calWindow.channels != null...

            hardware.sendDataChangesToRemotes();

            waitSleep(10);

        }//while
    }//try

    catch (InterruptedException e) {
        return;
    }

}//end of MainThread::run
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainThread::waitSleep
//
// Sleeps for pTime milliseconds.
//

private void waitSleep(int pTime) throws InterruptedException
{

    Thread.sleep(pTime);

}//end of MainThread::waitSleep
//-----------------------------------------------------------------------------

}//end of class MainThread
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class MainWindow
//
// Creates a main window JFrame.  Creates all other objects needed by the
// program.
//
// Listens for events generated by the main window.  Calls clean up functions
// on program exit.
//

class MainWindow implements WindowListener, ActionListener, ChangeListener,
                                ComponentListener, DocumentListener, Link {

    Settings settings;
    String language;
    Xfer xfer;

    JFrame mainFrame;
    JDialog measureDialog;
    ControlPanel controlPanel;

    MainMenu mainMenu;
    MainThread mainThread;

    Timer mainTimer;

    int numberOfChannels;
    Channel[] calChannels;

    Hardware hardware;

    EncoderCalValues encoderCalValues = new EncoderCalValues();
    
    Log logWindow;
    JobInfo jobInfo;
    PieceInfo pieceIDInfo;
    Debugger debugger;
    UTCalibrator calWindow;
    Monitor monitorWindow;
    boolean monitorActive = false;
    EncoderCalibrator encoderCalibrator;
    boolean encoderCalibratorActive = false;
    JackStandSetup jackStandSetup;
    boolean jackStandSetupActive = false;
   
    FlagReportPrinter printFlagReportDialog = null;
    int closePrintFlagReportDialogTimer = 0;

    AScan aScan;

    int initialWidth, initialHeight;

    DecimalFormat[] decimalFormats;

    int lastPieceInspected = -1;
    boolean isLastPieceInspectedACal = false;

    static final int ERROR_LOG_MAX_SIZE = 10000;

    private static final String FILE_FORMAT = "UTF-8";
    
//-----------------------------------------------------------------------------
// MainWindow::MainWindow (constructor)
//

public MainWindow()
{

}//end of MainWindow::MainWindow (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::init
//
// Initializes the object.  MUST be called by sub classes after instantiation.
//

public void init()
{

    //turn off default bold for Metal look and feel
    UIManager.put("swing.boldMetal", Boolean.FALSE);

    MultipleInstancePreventer.checkForInstanceAlreadyRunning("IRScan");

    setupJavaLogger(); //redirect to a file

    //force "look and feel" to Java style
    try {
        UIManager.setLookAndFeel(
            UIManager.getCrossPlatformLookAndFeelClassName());
    }
    catch (ClassNotFoundException | InstantiationException |
            IllegalAccessException | UnsupportedLookAndFeelException e) {
        logSevere(e.getMessage() + " - Error: 263");
    }

    //create various decimal formats
    decimalFormats = new DecimalFormat[1];
    decimalFormats[0] = new  DecimalFormat("0000000");

    //convert all ini and config files from old formats to the current format
    //(this does not convert files already in use in the job folders)
    FileFormatConverter converter = new FileFormatConverter(FILE_FORMAT);
    converter.init();

    settings = new Settings(this, this);

    if (Files.exists(Paths.get("Viewer Mode.ini"))) {
        settings.viewerMode = true;
    }

    xfer = new Xfer();

    //create the program's main window
    mainFrame = new JFrame("Java Chart");
    settings.mainFrame = mainFrame; //store for use by other objects

    SwissArmyKnife.setIconImages(mainFrame, Main.class, "IRScan Chart Icon");

    //do not auto exit on close - shut down handled by the timer function
    mainFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    mainFrame.addComponentListener(this);
    mainFrame.addWindowListener(this);

    //change the layout manager
    BoxLayout boxLayout =
                   new BoxLayout(mainFrame.getContentPane(), BoxLayout.Y_AXIS);
    //mainFrame.setLayout(new BoxLayout(mainFrame, BoxLayout.Y_AXIS));
    mainFrame.getContentPane().setLayout(boxLayout);

    loadLanguage(settings.language); //set text on main form

    //create a main menu, passing settings as the object to be installed as
    //the action and item listener for the menu
    mainFrame.setJMenuBar(mainMenu = new MainMenu(settings));

    //loads configurations settings for the program
    loadGeneralConfiguration();

    //loads settings such as the data paths and other values which rarely change
    loadMainStaticSettings();

    //loads settings such as current job name, etc.
    loadMainSettings();

    //read the configuration file and create/setup the charting/control elements
    //loads the job file
    configure();

    mainFrame.setLocation(
                    settings.mainWindowLocationX, settings.mainWindowLocationY);

    mainFrame.pack();
    mainFrame.setVisible(true);

    //store the height and width of the main window after it has been set up so
    //it can be restored to this size if an attempt is made to resize it
    initialWidth = mainFrame.getWidth();
    initialHeight = mainFrame.getHeight();

    //reset the charts
    resetChartGroups();

    //tell cal window to create an image
    calWindow.scope1.createImageBuffer();
    calWindow.scope1.clearPlot();

    //create and start a thread to collect data from the hardware
    mainThread = new MainThread(calWindow, settings);
    mainThread.hardware = hardware;
    if(!settings.viewerMode) { mainThread.start(); }

    //Create and start a timer which will handle updating the displays.
    mainTimer = new Timer(10, this);
    mainTimer.setActionCommand("Timer");
    mainTimer.start();

    //allow all objects to update values dependent on display sizes
    handleSizeChanges();

    //force garbage collection before beginning any time sensitive tasks
    System.gc();

    //if not in viewer mode check to see if license has expired
    if(!settings.viewerMode){
        LicenseValidator  lv = new LicenseValidator(mainFrame, "Graphics.ini");
        lv.validateLicense();
    }

    if (settings.viewerMode){
        displayViewerInstructionsInLogWindow();
    }


}//end of MainWindow::init
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::loadMainStaticSettings
//
// Loads settings such as the data file paths, etc.
// These values do not change from job to job and are only updated by the
// program during setup.
//

private void loadMainStaticSettings()
{

    IniFile configFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        configFile = new IniFile("Main Static Settings.ini",
                                                      Settings.mainFileFormat);
        configFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 385");
        return;
    }

    //set the data folders to empty if values cannot be read from the ini file
    //all functions which write data should abort if the folder names are empty

    settings.primaryDataPath = SwissArmyKnife.formatPath(configFile.readString(
                               "Main Configuration", "Primary Data Path", ""));

    settings.backupDataPath = SwissArmyKnife.formatPath(configFile.readString(
                                "Main Configuration", "Backup Data Path", ""));

    settings.reportsPath = SwissArmyKnife.formatPath(configFile.readString(
                                    "Main Configuration", "Reports Path", ""));

    settings.mapFilesPath = SwissArmyKnife.formatPath(configFile.readString(
                           "Main Configuration", "Map Files Path", ""));

    settings.establishPLCComLink = configFile.readBoolean(
             "PLC Communication", "Establish PLC Communications Link", false);

    settings.plcIPAddressString = configFile.readString(
             "PLC Communication", "PLC Ethernet IP address", "192.168.15.100");

    settings.plcEthernetPort = configFile.readInt(
                            "PLC Communication", "PLC Ethernet Port", 10002);

    settings.panelViewIPAddressString = configFile.readString(
      "PLC Communication", "Panel View Ethernet IP address", "192.168.15.150");

    settings.plcSubnetMask = configFile.readString(
             "PLC Communication", "Ethernet IP Subnet Mask", "255.255.255.0");

}//end of MainWindow::loadMainStaticSettings
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::displayViewerInstructionsInLogWindow
//
// Displays instructions for viewing job data in the log window.
//

private void displayViewerInstructionsInLogWindow(){


    logWindow.appendLine("");
    logWindow.appendLine("   --- Instructions for Viewing Job Files ---");
    logWindow.appendLine("");

    logWindow.appendLine(" After reading these instructions, this window can");
    logWindow.appendLine(" be closed by clicking the X in the upper right");
    logWindow.appendLine(" corner or it can be moved out of the way.");

    logWindow.appendLine("");

    logWindow.appendLine(" To select a job for viewing, click:");
    logWindow.appendLine("      File/Change Job");

    logWindow.appendLine("");

    logWindow.appendLine(" To view runs for the selected job, click:");
    logWindow.appendLine("      View/View Chart of a Completed " +
                                                    settings.pieceDescription);

    logWindow.appendLine("");
    logWindow.appendLine(" These commands are located at the top of the ");
    logWindow.appendLine(" main window in the main menu. This Message Log");
    logWindow.appendLine(" window may have to be closed or moved to see the");
    logWindow.appendLine(" main menu.");

}//end of MainWindow::displayViewerInstructionsInLogWindow
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveMainStaticSettings
//
// Saves settings such as the data file paths, etc.
// These values do not change from job to job and are only updated by the
// program during setup.
//

private void saveMainStaticSettings()
{

    IniFile configFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        configFile = new IniFile("Main Static Settings.ini",
                                                     Settings.mainFileFormat);
        configFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 975");
        return;
    }

    configFile.writeString("Main Configuration", "Primary Data Path",
                                                     settings.primaryDataPath);

    configFile.writeString("Main Configuration", "Backup Data Path",
                                                      settings.backupDataPath);

    //force save
    configFile.save();

}//end of MainWindow::saveMainStaticSettings
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::loadGeneralConfiguration
//
// Loads general configuration settings.
//

private void loadGeneralConfiguration()
{

    IniFile configFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        configFile = new IniFile(
                       "Configuration - General.ini", Settings.mainFileFormat);
        configFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 508");
        return;
    }

    settings.primaryFolderName = configFile.readString(
                          "Main Configuration", "Primary Data Folder Name",
                                              "IR Scan Data Files -  Primary");

    settings.backupFolderName = configFile.readString(
                        "Main Configuration", "Backup Data Folder Name",
                        "Backup Data Folder Name=IR Scan Data Files - Backup");

    //replace any forward/back slashes with the filename separator appropriate
    //for the system the program is running on

    String sep = File.separator;
    settings.primaryFolderName = settings.primaryFolderName.replace("/", sep);
    settings.primaryFolderName = settings.primaryFolderName.replace("\\", sep);
    settings.backupFolderName = settings.backupFolderName.replace("/", sep);
    settings.backupFolderName = settings.backupFolderName.replace("\\", sep);

    //if there is a separator at the end, remove for consistency with later code
    settings.primaryFolderName = 
                SwissArmyKnife.stripFileSeparator(settings.primaryFolderName);

    settings.backupFolderName =
                SwissArmyKnife.stripFileSeparator(settings.backupFolderName);

    settings.printResolutionX = configFile.readInt(
                                "Printer", "Printer Resolution X in DPI", 300);

    settings.printResolutionY = configFile.readInt(
                                "Printer", "Printer Resolution Y in DPI", 300);

    settings.printQuality  = configFile.readString(
                   "Printer", "Print Quality (Draft, Normal, High)", "Normal");

}//end of MainWindow::loadGeneralConfiguration
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::loadMainSettings
//
// Loads settings such as the current work order.
// These values often changed as part of normal operation.
//

private void loadMainSettings()
{

    IniFile configFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        configFile = new IniFile("Main Settings.ini", Settings.mainFileFormat);
        configFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 570");
        return;
    }

    settings.currentJobName = configFile.readString(
                             "Main Configuration", "Current Work Order", "");

    JobValidator jobValidator = new JobValidator(settings.primaryDataPath,
            settings.backupDataPath, settings.currentJobName, false, xfer);
    jobValidator.init();

    //if flag returns true, one or both of the root data paths is missing - set
    //both and the job name to empty so they won't be accessed
    if (xfer.rBoolean2){
        settings.primaryDataPath = ""; settings.backupDataPath = "";
        settings.currentJobName = "";
    }

    //if flag returns true, the root folders exist but the job name cannot be
    //found in either - assume that it no longer exists and set the name empty
    if (xfer.rBoolean3) {settings.currentJobName = "";}

    settings.currentJobPrimaryPath = ""; settings.currentJobBackupPath = "";

    //if the root paths and the job name are valid, create the full paths
    if(!settings.currentJobName.equals("")){
        if(!settings.primaryDataPath.equals("")) {
            settings.currentJobPrimaryPath = settings.primaryDataPath +
                                    settings.currentJobName + File.separator;
        }
        if(!settings.backupDataPath.equals("")) {
            settings.currentJobBackupPath = settings.backupDataPath +
                                    settings.currentJobName + File.separator;
        }
    }

    settings.mainWindowLocationX = configFile.readInt(
                            "Main Configuration", "Main Window Location X", 0);

    settings.mainWindowLocationY = configFile.readInt(
                            "Main Configuration", "Main Window Location Y", 0);

    settings.calWindowLocationX = configFile.readInt(
                   "Main Configuration", "Calibrator Window Location X", 0);

    settings.calWindowLocationY = configFile.readInt(
                   "Main Configuration", "Calibrator Window Location Y", 0);

    settings.calWindowAltLocationX = configFile.readInt(
             "Main Configuration", "Calibrator Window Alternate Location X", 0);

    settings.calWindowAltLocationY = configFile.readInt(
             "Main Configuration", "Calibrator Window Alternate Location Y", 0);
        
}//end of MainWindow::loadMainSettings
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveMainSettings
//
// Saves settings such as the current work order.
// These values often changed as part of normal operation.
//

private void saveMainSettings()
{

    IniFile configFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        configFile = new IniFile("Main Settings.ini", Settings.mainFileFormat);
        configFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 637");
        return;
    }

    configFile.writeString("Main Configuration", "Current Work Order",
                                                      settings.currentJobName);

    configFile.writeInt("Main Configuration", "Calibrator Window Location X",
                                                  settings.calWindowLocationX);

    configFile.writeInt("Main Configuration", "Calibrator Window Location Y",
                                                   settings.calWindowLocationY);

    configFile.writeInt("Main Configuration", 
      "Calibrator Window Alternate Location X", settings.calWindowAltLocationX);

    configFile.writeInt("Main Configuration", 
      "Calibrator Window Alternate Location Y", settings.calWindowAltLocationY);
        
    //force save
    configFile.save();

}//end of MainWindow::saveMainSettings
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::configure
//
// Loads configuration settings from the configuration.ini file.  These set
// the number of charts, traces, thresholds, channels, positions, colors, etc.
//
// After obtaining the config data necessary for the main window itself, the
// various child objects are created according to the config data specs.  The
// config file is passed to each child object to allow them to load their own
// config data.
//
// Older versions have used other file formats, so when a job is loaded the
// file format is set to that of the configuration file for that job.
//

private void configure()
{

    String configFilename = settings.currentJobPrimaryPath + "01 - " +
                                settings.currentJobName + " Configuration.ini";

    String detectedFileFormat;

    AtomicBoolean containsWindowsFlags = new AtomicBoolean(false); 
    
    try{
        detectedFileFormat = FileFormatConverter.detectFileFormat(
                                        configFilename, containsWindowsFlags);
    }
    catch(IOException e){
        logSevere(e.getMessage());
        //on error while trying to determine format, try default
        detectedFileFormat = FILE_FORMAT;
    }

    // see notes in IniFile.detectUTF16LEFormat method for explanation of
    //UTF-16LE/UTF-8/Windows ANSI/Windows UTF-8/Windows Unicode issues
        
    settings.jobFileFormat = detectedFileFormat;

    IniFile configFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        configFile = new IniFile(configFilename, settings.jobFileFormat);
        configFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 693");
        return;
    }

    //create an object to hold job info
    jobInfo = new JobInfo(mainFrame, settings.currentJobPrimaryPath,
               settings.currentJobBackupPath, settings.currentJobName, this,
                                                       settings.jobFileFormat);
    jobInfo.init();

    //create an object to hold info about each piece
    pieceIDInfo = new PieceInfo(mainFrame, settings.currentJobPrimaryPath,
        settings.currentJobBackupPath, settings.currentJobName, this, false,
                                                       settings.jobFileFormat);
    pieceIDInfo.init();

    //create a window for displaying messages
    logWindow = new Log(mainFrame); logWindow.setVisible(true);

    //create a window for monitoring status and inputs
    monitorWindow = new Monitor(mainFrame, configFile, this);
    monitorWindow.init();
    
    //create the hardware interface first so the traces can link to it
    hardware = new Hardware(configFile, settings, logWindow.textArea);
    hardware.init();
    //store a pointer in settings for use by other objects
    settings.hardware = hardware;

    //create a window for monitoring status and inputs
    encoderCalibrator = new EncoderCalibrator(mainFrame, configFile, this);
    encoderCalibrator.init();    

    //create a window for entering jack stand distances
    jackStandSetup = new JackStandSetup(mainFrame, configFile, this);
    jackStandSetup.init();
    
//create a debugger window with a link to the hardware object
    debugger = new Debugger(mainFrame, hardware);
    debugger.init();

    //create an array to hold references to each channel for the strip chart
    //which invoked the opening of the cal window
    //the array is made big enough to hold all possible channels even though
    //they may be split amongst charts and would not require a full array for
    //each calibration window which only handles the number of channels assigned
    //to the chart which invoked the window - it is possible that ALL channels
    //could be assigned to one chart, so it is easier to make the array overly
    //large

    numberOfChannels = hardware.getNumberOfChannels();
    calChannels = new Channel[numberOfChannels];

    //create after hardware object created
    calWindow = new UTCalibrator(mainFrame, hardware, settings);
    calWindow.init();

    settings.configure(configFile);

    //don't use these - let contents set size of window
    //mainFrame.setMinimumSize(new Dimension(1100,1000));
    //mainFrame.setPreferredSize(new Dimension(1100,1000));
    //mainFrame.setMaximumSize(new Dimension(1100,1000));

    String title = configFile.readString(
                      "Main Configuration", "Main Window Title", "Java Chart");

    mainFrame.setTitle(title);

    settings.displayOptionsPanel = configFile.readBoolean(
                         "Main Configuration", "Display Options Panel", false);

    settings.displayPlus6dBBtn = configFile.readBoolean(
                     "Main Configuration", "Display Plus 6 dB Button", false);

    settings.forceDistanceToMarkerToZero = configFile.readBoolean(
              "Markers", "Force Distance To Marker to Zero", false);

    settings.simulationMode = configFile.readBoolean(
                                "Main Configuration", "Simulation Mode", false);

    settings.simulateMechanical = configFile.readBoolean(
                                    "Hardware", "Simulate Mechanical", false);

    settings.copyToAllMode = configFile.readInt(
                                  "Main Configuration", "Copy to All Mode", 0);

    //if true, the traces will be driven by software timer rather than by
    //encoder inputs - used for weldline crabs and systems without encoders
    settings.timerDrivenTracking = configFile.readBoolean(
                                    "Hardware", "Timer Driven Tracking", false);

    settings.timerDrivenTrackingInCalMode = configFile.readBoolean(
                       "Hardware", "Timer Driven Tracking in Cal Mode", false);
    
    settings.numberOfChartGroups = configFile.readInt(
                            "Main Configuration", "Number of Chart Groups", 1);

    //create an array of chart groups per the config file setting
    if (settings.numberOfChartGroups > 0){

        //protect against too many groups
        if (settings.numberOfChartGroups > 10) {
            settings.numberOfChartGroups = 10;
        }

        settings.chartGroups = new ChartGroup[settings.numberOfChartGroups];

        for (int i = 0; i < settings.numberOfChartGroups; i++){
            settings.chartGroups[i] = new ChartGroup(settings, mainFrame,
                                configFile, i, hardware, this, false, hardware);
            mainFrame.add(settings.chartGroups[i]);
            }

        }//if (numberOfChartGroups > 0)

    //give hardware a connection to the charts
    hardware.setChartGroups(settings.chartGroups);

    //create a panel to hold user controls and status displays
    mainFrame.add(controlPanel =
        new ControlPanel(configFile, settings.currentJobPrimaryPath,
        settings.currentJobBackupPath, hardware, mainFrame, this,
        settings.currentJobName, settings, hardware));

    //load user adjustable settings
    loadCalFile();
    
    transferEncoderCalDataFromHardware();

    //force menu settings to match any variables loaded from disk
    mainMenu.refreshMenuSettings();

    //force screen control values to match any variables loaded from disk
    controlPanel.refreshControls();

}//end of MainWindow::configure
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::loadCalFile
//
// This loads the file used for storing calibration information pertinent to a
// job, such as gains, offsets, thresholds, etc.
//
// Each object is passed a pointer to the file so that they may load their
// own data.
//

private void loadCalFile()
{

    IniFile calFile;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        calFile = new IniFile(settings.currentJobPrimaryPath + "00 - "
                            + settings.currentJobName + " Calibration File.ini",
                                                        settings.jobFileFormat);
        calFile.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 831");
        return;
    }

    //if true, traces will restart at left edge of chart for each new piece
    //if false, new piece will be added to end of traces while chart scrolls
    settings.restartNewPieceAtLeftEdge = calFile.readBoolean("General",
                          "Restart Each New Piece at Left Edge of Chart", true);

    //settings which control peak hold display on the A Scan
    settings.showRedPeakLineInGateCenter = calFile.readBoolean("General",
                                    "Show Red Peak Line at Gate Center", false);
    settings.showRedPeakLineAtPeakLocation = calFile.readBoolean("General",
                                  "Show Red Peak Line at Peak Location", false);
    settings.showPseudoPeakAtPeakLocation = calFile.readBoolean("General",
                                     "Show Peak Symbol at Peak Location", true);
    settings.reportAllFlags = calFile.readBoolean("General",
       "Report all flags (do not skip duplicates at the same location)", false);

    settings.autoPrintFlagReports = calFile.readBoolean("General",
                    "Automatically Print Flag Reports After Each Run", false);

    settings.scanSpeed =
                calFile.readInt("General", "Scanning and Inspecting Speed", 10);

    if (settings.scanSpeed < 0 || settings.scanSpeed > 10) {
        settings.scanSpeed = 10;
    }

    settings.graphPrintLayout = calFile.readString(
                                "General", "Printer Paper Size", "8-1/2 x 11");

    //make sure print layout is one of the valid values
    if (!settings.graphPrintLayout.equalsIgnoreCase("8-1/2 x 11")
       && !settings.graphPrintLayout.equalsIgnoreCase("8-1/2 x 14")
       && !settings.graphPrintLayout.equalsIgnoreCase("A4")) {
        settings.graphPrintLayout = "8-1/2 x 11";
    }

    settings.userPrintWidth = calFile.readString(
                                    "General", "Graph Print Width", "Maximum");

    //load info for all charts
    for (int i=0; i < settings.numberOfChartGroups; i++) {
        settings.chartGroups[i].loadCalFile(calFile);
    }

    hardware.loadCalFile(calFile, settings.currentJobPrimaryPath, 
                                                    settings.primaryDataPath);

}//end of MainWindow::loadCalFile
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveCalFile

private void saveCalFile()
{

    //create a CalFileSaver thread object to save the data - this allows status
    //messages to be displayed and updated during the save
    settings.fileSaver = new CalFileSaver(settings, hardware, false);
    settings.fileSaver.init();
    settings.fileSaver.start();

}//end of MainWindow::saveCalFile
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveEverything
//
// Saves all settings.  Some of the save functions may not actually save the
// data if it has not been changed since the last save.
//

private void saveEverything()
{

    //save cal file last - the program won't exit while cal files are being
    //saved so that means all files will be finished saving

    //save miscellaneous settings
    saveMainSettings();
    //save next piece and cal piece numbers
    controlPanel.saveSettings();
    //save all user calibration settings
    saveCalFile();

}//end of MainWindow::saveEverything
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::resetChartGroups
//
// Erases the chart groups and clears all data.
//

private void resetChartGroups()
{

    for (int i = 0; i < settings.numberOfChartGroups; i++) {
        settings.chartGroups[i].resetAll();
    }

}//end of MainWindow::resetChartGroups
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::markSegmentStart
//
// Prepares to record a new data segment.
//
// Resets the counter which is used to determine if a new segment has begun
// and records the start position.
//
// This function should be called whenever a new segment is to start - each
// segment could represent a piece being monitored, a time period, etc.
//

public void markSegmentStart()
{

    for (int i = 0; i < settings.numberOfChartGroups; i++) {
        settings.chartGroups[i].markSegmentStart();
    }

}//end of MainWindow::markSegmentStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::markSegmentEnd
//
// Marks the buffer location of the end of the current segment.
//
// This function should be called whenever a new segment is to end - each
// segment could represent a piece being monitored, a time period, etc.
//
// This function should be called before saving the data so the end points
// of the data to be saved are known.
//

public void markSegmentEnd()
{

    for (int i = 0; i < settings.numberOfChartGroups; i++) {
        settings.chartGroups[i].markSegmentEnd();
    }

}//end of MainWindow::markSegmentEnd
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::segmentStarted
//
// Checks to see if a segment has been started and thus may have data which
// needs to be saved.
//

boolean segmentStarted()
{

    for (int i = 0; i < settings.numberOfChartGroups; i++) {
        if (settings.chartGroups[i].segmentStarted()) {
            return(true);
        }
    }

    return(false);

}//end of MainWindow::segmentStarted
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveSegment
//
// Saves the data for a segment to the primary and backup job folders.
//
// This function should be called whenever a new segment is completed - each
// segment could represent a piece being monitored, a time period, etc.
//
// This function should be called after the segment end has been marked and
// before the next segment start has been marked so that the end points
// of the data to be saved are known.
//

private void saveSegment() throws IOException
{

    String segmentFilename;
    String pieceNumber;

    if (!controlPanel.calMode){
        pieceNumber = decimalFormats[0].format(controlPanel.nextPieceNumber);
        settings.pieceNumberToBeSaved = controlPanel.nextPieceNumber;
    }
    else{
        pieceNumber = decimalFormats[0].format(controlPanel.nextCalPieceNumber);
        settings.pieceNumberToBeSaved = controlPanel.nextCalPieceNumber;
    }

    isLastPieceInspectedACal = controlPanel.calMode;

    //inspected pieces are saved with the prefix 20 while calibration pieces are
    //saved with the prefix 30 - this forces them to be grouped together and
    //controls the order in which the types are listed when the folder is viewed
    //in alphabetical order in an explorer window

    if (!controlPanel.calMode){
        segmentFilename = "20 - " + pieceNumber + ".dat";
        //save number before it changes to the next -- used for reports and such
        lastPieceInspected = controlPanel.nextPieceNumber;
    }
    else{
        segmentFilename = "30 - " + pieceNumber + ".cal";
        //save number before it changes to the next -- used for reports and such
        lastPieceInspected = controlPanel.nextCalPieceNumber;
    }

    saveSegmentHelper(settings.currentJobPrimaryPath + segmentFilename);
    saveSegmentHelper(settings.currentJobBackupPath + segmentFilename);


    //save the info file for each segment
    //info which can be modified later such as heat, lot, id number, etc.

    if (!controlPanel.calMode) {
        segmentFilename = "20 - " + pieceNumber + ".info";
    }
    else {
        segmentFilename = "30 - " + pieceNumber + ".cal info";
    }

    saveSegmentInfoHelper(settings.currentJobPrimaryPath + segmentFilename);
    saveSegmentInfoHelper(settings.currentJobBackupPath + segmentFilename);

    //save data buffers handled by any boards

    if (!controlPanel.calMode) {
        segmentFilename = "20 - " + pieceNumber + " map.dat";
    }
    else {
        segmentFilename = "30 - " + pieceNumber + " map.cal";
    }

    //save map file copies if mapping is active
    saveMap(segmentFilename);
    
}//end of MainWindow::saveSegment
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveSegmentHelper
//
// Saves the data for a segment to the specified file.  See the saveSegment
// function for more info.
//

private void saveSegmentHelper(String pFilename)
{

    //create a buffered writer stream

    FileOutputStream fileOutputStream = null;
    OutputStreamWriter outputStreamWriter = null;
    BufferedWriter out = null;

    try{

        fileOutputStream = new FileOutputStream(pFilename);
        outputStreamWriter = new OutputStreamWriter(fileOutputStream,
                                                       settings.jobFileFormat);
        out = new BufferedWriter(outputStreamWriter);

        //write the header information - this portion can be read by the iniFile
        //class which will only read up to the "[Header End]" tag - this allows
        //simple parsing of the header information while ignoring the data
        //stream which  follows the header

        out.write("[Header Start]"); out.newLine();
        out.newLine();
        out.write("Segment Data Version=" + Settings.SEGMENT_DATA_VERSION);
        out.newLine();
        out.write("Measured Length=" + hardware.hdwVs.measuredLength);
        out.newLine();
        out.write("Inspection Direction="
                                     + settings.inspectionDirectionDescription);
        out.newLine();
        out.write("[Header End]"); out.newLine(); out.newLine();

        for (int i = 0; i < settings.numberOfChartGroups; i++) {
            settings.chartGroups[i].saveSegment(out);
        }
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 1127");
    }
    finally{
        try{if (out != null) {out.close();}}
        catch(IOException e){}
        try{if (outputStreamWriter != null) {outputStreamWriter.close();}}
        catch(IOException e){}
        try{if (fileOutputStream != null) {fileOutputStream.close();}}
        catch(IOException e){}
    }

}//end of MainWindow::saveSegmentHelper
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveSegmentInfoHelper
//
// Saves the non-inspection data for a segment to the specified file.  See the
// saveSegment function for more info.
//
// Each piece saved has a *.dat file containing the graph data and a *.info
// file containing info such as joint number, id number, heat number, lot
// number, etc.
//
// In general, data which should never be changed in the future is all saved in
// the *.dat file -- graph data, calibration data, etc.
//
// Data which might need to be modified later is stored in the *.info file.
//

private void saveSegmentInfoHelper(String pFilename)
{

    //create a buffered writer stream

    FileOutputStream fileOutputStream = null;
    OutputStreamWriter outputStreamWriter = null;
    BufferedWriter out = null;

    try{

        fileOutputStream = new FileOutputStream(pFilename);
        outputStreamWriter = new OutputStreamWriter(fileOutputStream,
                                                       settings.jobFileFormat);
        out = new BufferedWriter(outputStreamWriter);

        //write a warning note at the top of the file

        out.newLine();
        out.write(";Do not erase blank line above -"
                   + " has hidden code needed by UTF-16 files.");
        out.newLine(); out.newLine();

        //write the header information - this portion can be read by the iniFile
        //class which will only read up to the "[Header End]" tag - this allows
        //simple parsing of the header information while ignoring the data
        //stream which  follows the header

        out.write("[MetaData]"); out.newLine();
        out.newLine();
        out.write("Segment Data Version=" + Settings.SEGMENT_DATA_VERSION);
        out.newLine();
        out.newLine();
        out.write("[MetaData End]"); out.newLine(); out.newLine();

        out.newLine();

        //allow the pieceInfo object to save its data to the file
        pieceIDInfo.saveDataToStream(out);

    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 1199");
    }
    finally{
        try{if (out != null) {out.close();}}
        catch(IOException e){}
        try{if (outputStreamWriter != null) {outputStreamWriter.close();}}
        catch(IOException e){}
        try{if (fileOutputStream != null) {fileOutputStream.close();}}
        catch(IOException e){}
    }

}//end of MainWindow::saveSegmentInfoHelper
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::saveMap
//
// Saves map file copies if mapping is active. A copy is saved in the primary
// and backup folders and a copy is saved in the folder specified for map
// files. If not specified, the third copy is saved in a folder next to the
// job folder.
//
// This third copy in a separate directory is meant to be accessed by outside
// programs, thus eliminating the need to browse through the main data fiiles.
//

private void saveMap(String pSegmentFilename)
{
    
    hardware.saveAllMapDataSetsToTextFile(
        settings.currentJobPrimaryPath + pSegmentFilename,
        settings.jobFileFormat, settings.inspectionDirectionDescription);

    hardware.saveAllMapDataSetsToTextFile(
        settings.currentJobBackupPath + pSegmentFilename,
        settings.jobFileFormat, settings.inspectionDirectionDescription);

    String lMapsPath = SwissArmyKnife.createFolderForSpecifiedFileType(
        settings.mapFilesPath, settings.currentJobPrimaryPath, 
            settings.currentJobName," ~ Maps", mainFrame);
       
    if (!lMapsPath.equals("")){

        hardware.saveAllMapDataSetsToTextFile(
            lMapsPath + pSegmentFilename,
            settings.jobFileFormat, settings.inspectionDirectionDescription);
        
    }
    
}//end of MainWindow::saveMap
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::handleSizeChanges
//
// Updates any values related to the size of display objects.  Called after
// the display has been set and any time a size may have changed.
//

public final void handleSizeChanges()
{

    for (int i = 0; i < settings.numberOfChartGroups; i++) {
        settings.chartGroups[i].handleSizeChanges();
    }

}//end of MainWindow::handleSizeChanges
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::actionPerformed
//
// Responds to button events.
//

@Override
public void actionPerformed(ActionEvent e)
{

    //handle timer calls
    if ("Timer".equals(e.getActionCommand())) {
        processMainTimerEvent();
        return;
    }
        
    //this part handles saving all data
    if ("Save".equals(e.getActionCommand())) {
        if(isConfigGoodA()) {saveEverything();}
        return;
    }

    //this part handles creating a new job
    if ("New Job".equals(e.getActionCommand())) {
        if(isConfigGoodB()) {createNewJob();}
        return;
    }

    //this part handles switching to a different job
    if ("Change Job".equals(e.getActionCommand())) {
        if(isConfigGoodB()) {changeJob();}
        return;
    }

    //this part handles saving current settings to a preset
    if ("Copy From Another Job".equals(e.getActionCommand())) {
        if(isConfigGoodA()) {copyPreset();}
        return;
    }

    //this part handles saving current settings to a preset
    if ("Save Preset".equals(e.getActionCommand())) {
        savePreset();
        return;
    }

    //this part handles loading a preset
    if ("Load Preset".equals(e.getActionCommand())) {
        if(isConfigGoodA()) {changePreset();}
        return;
    }

    //this part handles renaming a preset
    if ("Rename Preset".equals(e.getActionCommand())) {
        RenamePreset renamePreset = new RenamePreset(mainFrame,
                settings.primaryDataPath, settings.backupDataPath, xfer);
        renamePreset.init();
        return;
    }

    //this part handles deleting a preset
    if ("Delete Preset".equals(e.getActionCommand())) {
        DeletePreset deletePreset = new DeletePreset(mainFrame,
                settings.primaryDataPath, settings.backupDataPath, xfer);
        deletePreset.init();
        return;
    }

    //this part displays the log window
    if ("Log".equals(e.getActionCommand())) {
        logWindow.setVisible(true);
        return;
    }

    //this part writes various status and error messages to the log window
    if ("Status".equals(e.getActionCommand())) {logStatus();}

    //this part opens a viewer window for viewing saved segments
    if (e.getActionCommand().startsWith("View Chart of a Completed")) {

        if(isConfigGoodA()) {
            Viewer viewer;
            viewer = new Viewer(settings, jobInfo,
                 settings.currentJobPrimaryPath, settings.currentJobBackupPath,
                 settings.currentJobName);
            viewer.init();
        }
        return;
    }

    //this part creates a Viewer Program package for viewing job data on any
    //computer
    if ("Create Viewer Package for Viewing on Any Computer".equals(
                                                       e.getActionCommand())) {

        //read values passed to viewerCreate from config file

        ViewerCreator viewerCreator = new ViewerCreator(mainFrame,
                "IR Scan Job Viewer",
                settings.currentJobName,
                settings.currentJobPrimaryPath,
                settings.currentJobBackupPath,
                settings.primaryFolderName,
                settings.backupFolderName);
        viewerCreator.init();
    }

    //this part opens a window to print a flag report
    if ("Print Flag Report for Last Piece Inspected".equals(
                                                       e.getActionCommand())) {
        printFlagReportForLastPieceInspected();
    }

    //this part opens a window to print a flag report
    if ("Print Flag Report for User Selection".equals(e.getActionCommand())) {
        printFlagReport(-1, false);
    }

    //this part displays a list of calibration files which can be viewed/printed
    if ("View Calibration Records".equals(e.getActionCommand())) {

        //create a CalFileSaver thread object to save the data - this allows
        //status messages to be displayed and updated during the save
        settings.fileSaver = new CalFileSaver(settings, hardware, true);
        settings.fileSaver.init();
        settings.fileSaver.start();

    }

    //this part handles starting the status monitor
    if ("Monitor".equals(e.getActionCommand())) {
        monitorWindow.setVisible(true);
        hardware.startMonitor();
        return;
    }

    //this part handles starting the encoder calibrator
    if ("Encoders".equals(e.getActionCommand())) {
        encoderCalibrator.setVisible(true);
        hardware.startMonitor();
        return;
    }

    //this part handles starting the jack stand setup window
    if ("Set Up Jack Stands".equals(e.getActionCommand())) {
        jackStandSetup.setVisible(true);
        return;
    }

    //this part handles opening the debugger window
    if ("Debugger".equals(e.getActionCommand())) {
        debugger.setVisible(true);
        return;
    }

    //this part handles opening the Job Info window
    if ("Job Info".equals(e.getActionCommand())) {
        if(isConfigGoodA()) {jobInfo.setVisible(true);}
        return;
    }

    //this part handles opening the piece Identifier Info window
    if ("View / Edit Identifier Info".equals(e.getActionCommand())) {
        if(isConfigGoodA()) {pieceIDInfo.setVisible(true);}
        return;
    }

    //this part handles zeroing the encoder counts
    if ("Zero Encoder Counts".equals(e.getActionCommand())) {
        hardware.zeroEncoderCounts();
        return;
    }

    //transfers encoder calibration data from the encoder calibration
    //window to the hardware object
    if ("Transfer Encoder Calibration Data".equals(e.getActionCommand())) {
        transferEncoderCalDataToHardware();        
        return;
    }

    //transfers jack stand calibration data from the Jack Stand Setup
    //window to the hardware object
    if ("Transfer Jack Stand Setup Data".equals(e.getActionCommand())) {
        transferJackStandCalDataToHardware();
        return;
    }
    
    //this part handles pulsing Output Channel 0
    if ("Pulse Audible Alarm".equals(e.getActionCommand())) {
        hardware.pulseAlarmMarker(0);
        return;
    }

    //this part handles flipping Output Channel 0 between min and max
    if ("Flip Analog Output".equals(e.getActionCommand())){
        hardware.flipAnalogOutput(0);
        return;
    }
    
    //this part handles repairing a job
    if ("Repair Job".equals(e.getActionCommand())) {

        if(!isConfigGoodA()) {return;}

        //create with pRobust set true so paths will be recreated if necessary
        JobValidator jobValidator = new JobValidator(
                settings.currentJobPrimaryPath, settings.currentJobBackupPath,
                settings.currentJobName, true, xfer);
        jobValidator.init();

        displayInfoMessage(
                        "The repair is complete.  Click OK to reload the job.");

        //reload the job to refresh any changes from the repair
        //exit the program, passing true to instantiate a new program which will
        //reload the job on startup - it is required to create a new
        //program and kill the old one so that all of the configuration data for
        //the job will be loaded properly

        triggerProgramExit(false, true);

        return;
    }

    //this part handles updating the Rabbit code by a slave thread
    if ("Update UT Rabbit Code".equals(e.getActionCommand())) {
        startRabbitUpdater(Hardware.UT_RABBITS);
        return;
    }

    //this part handles updating the Rabbit code by a slave thread
    if ("Update Control Rabbit Code".equals(e.getActionCommand())) {
        startRabbitUpdater(Hardware.CONTROL_RABBITS);
        return;
    }

    //this part handles setting up the system
    if ("Set Up System".equals(e.getActionCommand())) {setupSystem(); return;}

    //this part handles renewing the license
    if ("Renew License".equals(e.getActionCommand())) {
        //asks user to enter a new license renewal code
        //(the window doesn't refer to it being a license renewal code but
        // rather asks for a response code so the user isn't alerted to the fact
        // that an expiration date is in use)
        LicenseValidator  lv = new LicenseValidator(mainFrame, "Graphics.ini");
        //NOTE: the old license file will not be altered unless the user enters
        // a new valid code.  This way a working system won't be broken by an
        // attempt to update the license before the expiration.  The new license
        // will not take effect until the next resart.
        lv.requestLicenseRenewal(false);
        return;
    }

    //this part handles requests by ChartGroup objects to open the cal window
    //the chart index number is appended to command string, so use startsWith
    if (e.getActionCommand().startsWith("Open Calibration Window")) {
        displayCalWindow(e.getActionCommand());
        return;
    }

    //prepare for the next piece to be inspected
    if ("Prepare for Next Piece".equals(e.getActionCommand())) {
        prepareForNextPiece();
        return;
    }

    //this part processes a finished piece by saving data, adjusting next piece
    //number, etc.
    if ("Process finished Piece".equals(e.getActionCommand())) {
        try{
            processFinishedPiece();
        }
        catch(IOException ioe){
            logSevere(ioe.getMessage() + " - Error: 1468");
        }
        return;
    }// if ("Process finished Piece".equals(e.getActionCommand()))

    //this part handles displaying the "About" window
    if ("About".equals(e.getActionCommand())) {
        About about = new About(mainFrame);
        return;
    }

    if ("Display Configuration Info".equals(e.getActionCommand())){
        settings.displayConfigInfo(logWindow.textArea);
        logWindow.setVisible(true);
    }

    if ("Set Primary Location for Calibration Window".equals(
                                                    e.getActionCommand())){
        settings.setCalibrationWindowPrimaryLocation(
                    calWindow.getLocation().x, calWindow.getLocation().y);
    }
        
    if ("Set Alternate Location for Calibration Window".equals(
                                                    e.getActionCommand())){
        settings.setCalibrationWindowAlternateLocation(
                    calWindow.getLocation().x, calWindow.getLocation().y);
    }

}//end of MainWindow::actionPerformed
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Main::transferEncoderCalDataToHardware
//
// Transfers data from the EncoderCalibrator window to the Hardware object.
//

public void transferEncoderCalDataToHardware()
{
        
    hardware.setEncoderCalValues(
                      encoderCalibrator.getEncoderCalValues(encoderCalValues));
        
    hardware.setChannelsEncoderCountDistanceToMarker();
    
}//end of Main::transferEncoderCalDataToHardware
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Main::transferJackStandCalDataToHardware
//
// Transfers data from the JackStandSetup window to the Hardware object.
//
// The values are transfered via an EncoderCalValues object.
//

public void transferJackStandCalDataToHardware()
{
        
    hardware.setEncoderCalValues(
                      jackStandSetup.getEncoderCalValues(encoderCalValues));
    
}//end of Main::transferJackStandCalDataToHardware
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Main::transferEncoderCalDataFromHardware
//
// Transfers data to the EncoderCalibrator window from the hardware object.
//

public void transferEncoderCalDataFromHardware()
{
    
    encoderCalibrator.setEncoderCalValues(
                               hardware.getEncoderCalValues(encoderCalValues));

}//end of MainWindow::transferEncoderCalDataFromHardware
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Main::startRabbitUpdater
//
// Starts a process in a slave thread which installs new firmware on the Rabbit
// micro-controllers.  Which boards are updated is selected by pWhichRabbits
// -- all, UT, or Control boards.
//

public void startRabbitUpdater(int pWhichRabbits)
{

    String which = "";

    if (pWhichRabbits == Hardware.ALL_RABBITS) {which = "all";}
    else if (pWhichRabbits == Hardware.UT_RABBITS) {which = "the UT";}
    else if (pWhichRabbits == Hardware.CONTROL_RABBITS) {which = "the Control";}

    int n = JOptionPane.showConfirmDialog( null,
    "Update " + which + " Rabbit micro-controllers?",
    "Warning", JOptionPane.YES_NO_OPTION);

    //bail out if user does not click yes
    if (n != JOptionPane.YES_OPTION) {return;}

    if (pWhichRabbits == Hardware.UT_RABBITS
            || pWhichRabbits == Hardware.ALL_RABBITS){

        hardware.startUTRabbitUpdater = true;

    }

    if (pWhichRabbits == Hardware.CONTROL_RABBITS
            || pWhichRabbits == Hardware.ALL_RABBITS){

        hardware.startControlRabbitUpdater = true;

    }

}//end of Main::startRabbitUpdater
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::printFlagReport
//
// Generates and prints a flag report.  If pPieceToPrint is not -1 then that
// piece will be printed with pIsCalPiece specifying inspection or cal piece.
// If it is -1, then a dialog will be displayed to allow the user to specify the
// piece or pieces to print.
//
// If the auto print flag is true, the report is generated as a text file and
// printed. If false, the report is only generated.
//

private void printFlagReport(int pPieceToPrint, boolean pIsCalPiece)
{

    FlagReportPrinter lPrintFlagReportDialog = new FlagReportPrinter(
       mainFrame, settings, jobInfo,
       settings.currentJobPrimaryPath, settings.currentJobBackupPath,
       settings.reportsPath, settings.currentJobName,
       (int)mainFrame.getLocation().getX() + 80,
       (int)mainFrame.getLocation().getY() + 30,
       settings.pieceDescriptionPlural, settings.pieceDescriptionPluralLC,
       settings.printClockColumn,
       hardware, pPieceToPrint, pIsCalPiece);

    lPrintFlagReportDialog.init();

    lPrintFlagReportDialog.generateAndPrint(settings.autoPrintFlagReports);

    //if the piece to be printed was specified, the dialog displays a message
    //but requires no user input -- in that case, set up so that timer can
    //automatically close the dialog after a pause

    if(pPieceToPrint != -1){
        printFlagReportDialog = lPrintFlagReportDialog;
        closePrintFlagReportDialogTimer = 100;
    }

}//end of MainWindow::printFlagReport
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::processFinishedPiece
//
// Process a completed piece by saving it, analyzing it, etc.
//

public void processFinishedPiece() throws IOException
{

    //if an inspection was started, save the data and increment to the next
    //piece number - if an inspection was not started, ignore so that the
    //piece number is not incremented needlessly when the user clicks "Stop"
    //or "Next Run" without having inspected a piece

    if (segmentStarted()){

        markSegmentEnd();  //mark the buffer location of the end of the segment

        //display the min wall from the just finished piece -- do this before
        //saving the segment so peak wall marker(s) get saved
        updatePrevMinWallDisplay();

        //if data paths are good, save the data for the segment
        if(isConfigGoodA()) {saveSegment();}

        //increment the next piece or next cal piece number
        controlPanel.incrementPieceNumber();

        if(settings.autoPrintFlagReports){
            printFlagReportForLastPieceInspected();
        }

    }

}//end of MainWindow::processFinishedPiece
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::prepareForNextPiece
//
// Prepare to process a new piece.
//

public void prepareForNextPiece()
{

    //if so configured, reset all traces to begin at the left edge of the chart,
    // otherwise new piece will be appended to right end of traces and the chart
    // will scroll to display new data

    if (settings.restartNewPieceAtLeftEdge) {resetChartGroups();}

    //mark the starting point of a new piece in the data buffers
    markSegmentStart();

}//end of MainWindow::prepareForNextPiece
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::handlePieceTransition
//
// Saves the data for the piece just processed and prepares to process a new
// piece.
//

public void handlePieceTransition()throws IOException
{

    //save the piece just finished
    processFinishedPiece();

    //prepare buffers for next piece
    prepareForNextPiece();

    //prepare hardware interface for new piece
    hardware.setMode(Hardware.INSPECT);

}//end of MainWindow::handlePieceTransition
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::printFlagReportForLastPieceInspected
//

private void printFlagReportForLastPieceInspected()
{

    //generate the flag report text file for the last
    printFlagReport(lastPieceInspected, isLastPieceInspectedACal);

}//end of MainWindow::printFlagReportForLastPieceInspected
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::logStatus
//
// This method displays various status and error messages in the log window.
// The log window is displayed.
//
// This method can be used to see the number and types of errors which have
// occurred since the last time it was called.
//

public void logStatus()
{

    logWindow.setVisible(true);

    logWindow.section(); logWindow.appendLine("System Status");
    logWindow.appendLine("");

    hardware.logStatus(logWindow);

    logWindow.separate();

}//end of MainWindow::logStatus
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Viewer::updatePrevMinWallDisplay
//
// Finds the minimum value for the Min Wall trace and updates the display.
//
// The chart group containing the trace is returned via hardware.hdwVs.
// The chart containing the trace is returned via hardware.hdwVs.
// The trace is returned via hardware.hdwVs.
//

public String updatePrevMinWallDisplay()
{

    String result, wallText;
    hardware.hdwVs.chartGroup = null;
    hardware.hdwVs.chart = null;
    hardware.hdwVs.plotter = null;

    //check all chart groups for a Wall Max trace -- if there are more than one,
    //the value from the first one found will be used

    result = "";

    for (int i = 0; i < settings.numberOfChartGroups; i++){
        wallText =
              settings.chartGroups[i].getWallMinOrMaxText(true, hardware.hdwVs);
        if (!wallText.isEmpty()){
            hardware.hdwVs.chartGroup = settings.chartGroups[i];
            result = result + wallText;
            break;
        }
    }//for (int i = 0; i < numberOfChartGroups; i++)

    if(hardware.hdwVs.chart != null) {
        hardware.hdwVs.chart.updatePreviousMinWallValue(hardware.hdwVs.minWall);
    }

    return(result);

}//end of MainWindow::updatePrevMinWallDisplay
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::isConfigGoodA
//
// This function checks for various configuration errors and returns true if
// it is okay to save/load to the data folders.
//
// If there is an error, an error message will be displayed and the function
// will return false.
//

public boolean isConfigGoodA()
{

    //verify the data folder paths
    if (!isConfigGoodB()) {return(false);}

    //verify the job name
    if (settings.currentJobName.equals("")){
        displayErrorMessage("No job is selected."
              + " Use File/New Job or File/Change Job to correct this error.");
        return(false);
        }

    return(true);  //no configuration error

}//end of MainWindow::isConfigGoodA
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::isConfigGoodB
//
// This function checks for if the data folder paths are good and returns true
// if it is okay to save/load to the data folders.
//
// If there is an error, an error message will be displayed and the function
// will return false.  The error message does not specify which path is bad
// because both are often set empty when either is bad.
//

public boolean isConfigGoodB()
{

    if (settings.primaryDataPath.equals("")){
        displayErrorMessage("The root Primary or Backup Data Path is invalid."
                + " Use Help/Set Up System to repair this error.");
        return(false);
    }

    if (settings.backupDataPath.equals("")){
        displayErrorMessage("The root Primary or Backup Data Path is invalid."
                + " Use Help/Set Up System to repair this error.");
        return(false);
    }

    return(true);  //no configuration error

}//end of MainWindow::isConfigGoodB
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::createNewJob
//
// Allows the user to create a new job.
//

public void createNewJob()
{

    //NOTE: save must be done BEFORE calling the dialog window else new changes
    //may be overwritten or written to the wrong directory as the dialog window
    //may save files or switch directories

    saveEverything(); //save all data

    NewJob newJob = new NewJob(mainFrame, settings.primaryDataPath,
                        settings.backupDataPath, xfer, settings.jobFileFormat);
    newJob.init();

    //if the NewJob window set rBoolean1 true, switch to the new job
    if (xfer.rBoolean1){

        settings.currentJobName = xfer.rString1; //use the new job name
        saveMainSettings(); //save the new current job name so it will be loaded

        //update the data paths
        settings.currentJobPrimaryPath =
                     settings.primaryDataPath + settings.currentJobName + "/";
        settings.currentJobBackupPath =
                     settings.backupDataPath + settings.currentJobName + "/";

        //save a copy of the job info to the new work order
        jobInfo.prepareForNewJob(settings.currentJobPrimaryPath,
                       settings.currentJobBackupPath, settings.currentJobName);

        //exit the program, passing true to instantiate a new program which will
        //load the new job on startup - it is required to create a new
        //program and kill the old one so that all of the configuration data for
        //the job will be loaded properly

        triggerProgramExit(false, true);

    }// if (xfer.rBoolean1)

}//end of MainWindow::createNewJob
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::changeJob
//
// Allows the user to switch to a different job.
//

public void changeJob()
{

    //NOTE: save must be done BEFORE calling the dialog window else new changes
    //may be overwritten or written to the wrong directory as the dialog window
    //may save files or switch directories

    saveEverything(); //save all data

    ChooseJob chooseJob = new ChooseJob(mainFrame, settings.primaryDataPath,
                                                 settings.backupDataPath, xfer);

     //does the actual choose job work -- info passed back via xfer object
    chooseJob.init();

    //if the ChooseJob window set rBoolean1 true, switch to the new job
    if (xfer.rBoolean1){

        settings.currentJobName = xfer.rString1; //use the new job name
        saveMainSettings(); //save the new current job name so it will be loaded

        //exit the program, passing true to instantiate a new program which will
        //load the new work order on startup - it is required to create a new
        //program and kill the old one so that all of the configuration data for
        //the job will be loaded properly

        triggerProgramExit(false, true);

        }

}//end of MainWindow::changeJob
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::copyPreset
//
// Allows the user to copy a preset from a different job.
//
// The selected preset will be copied from the selected job folder to the
// current job folder.  The program will then be restarted to load the settings.
//

public void copyPreset()
{

    //NOTE: save must be done BEFORE calling the dialog window else new changes
    //may be overwritten or written to the wrong directory as the dialog window
    //may save files or switch directories

    saveEverything(); //save all data

    CopyPreset copyPreset = new CopyPreset(mainFrame, settings.primaryDataPath,
                        settings.backupDataPath, xfer, settings.currentJobName,
                        settings.jobFileFormat);

    copyPreset.init(); //initialize and to the actual work

    //if the ChooseJob window set rBoolean1 true, switch to the new preset
    if (xfer.rBoolean1){

        //no need to save main settings - the selected preset will have been
        //copied to the job folder so it will be loaded on restart

        //exit the program, passing true to instantiate a new program which will
        //load the new work order on startup - it is required to create a new
        //program and kill the old one so that all of the configuration data for
        //the job will be loaded properly

        triggerProgramExit(false, true);

    }

}//end of MainWindow::copyPreset
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::savePreset
//
// Allows the user to save the current settings as a preset file.
//
// The calibration file will be copied from the current job folder to the
// presets folder and renamed as specified by the user.
//

public void savePreset()
{

    // make sure the current settings have been saved to the calibration file
    // before it is copied to the presets folder

    saveEverything(); //save all data

    SavePreset savePreset = new SavePreset(mainFrame,
            settings.primaryDataPath, settings.backupDataPath, xfer,
            settings.currentJobName);

    savePreset.init();

}//end of MainWindow::savePreset
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::changePreset
//
// Allows the user to switch to a different preset.
//
// The selected preset will be copied from the presets folder to the job
// folder.  The program will then be restarted to load the settings.
//

public void changePreset()
{

    //NOTE: save must be done BEFORE calling the dialog window else new changes
    //may be overwritten or written to the wrong directory as the dialog window
    //may save files or switch directories

    saveEverything(); //save all data

    LoadPreset loadPreset = new LoadPreset( mainFrame, settings.primaryDataPath,
                        settings.backupDataPath, xfer, settings.currentJobName);

    loadPreset.init(); //initialize and to the actual work

    //if the ChooseJob window set rBoolean1 true, switch to the new preset
    if (xfer.rBoolean1){

        //no need to save main settings - the selected preset will have been
        //copied to the job folder so it will be loaded on restart

        //exit the program, passing true to instantiate a new program which will
        //load the new work order on startup - it is required to create a new
        //program and kill the old one so that all of the configuration data for
        //the job will be loaded properly

        triggerProgramExit(false, true);

        }

}//end of MainWindow::changePreset
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::setupSystem
//
// Prepares the system for use by creating necessary folders and any other
// initialization required when the software is copied to a new computer.
//
// If the data directories are already present, the settings in Main Static
// Settings.ini will be changed to point to the directories.
//

public void setupSystem()
{

    int n = JOptionPane.showConfirmDialog( mainFrame,
        "This feature should only be used by a technician -"
          + " do you want to continue?",
        "Warning", JOptionPane.YES_NO_OPTION);

    //bail out if user does not click yes
    if (n != JOptionPane.YES_OPTION) {return;}

    //create and display a file chooser
    final JFileChooser fc = new JFileChooser();
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    int returnVal = fc.showOpenDialog(mainFrame);

    if (returnVal != JFileChooser.APPROVE_OPTION){
        displayWarningMessage("Setup cancelled - nothing done.");
        return;
    }

    //selected file will actually be the selected directory because mode is
    //JFileChooser.DIRECTORIES_ONLY
    String targetDir = fc.getSelectedFile().toString();

    //Users often get confused and try to create the data folders when they already
    //exist - they often click on one of the existing folders in the browser
    //window which would cause a new set of folders to be installed in the
    //existing folder, which makes things confusing.  To avoid this, check to see
    //if one of the folder names is part of the selected folder and truncate the
    //selected folder so that the parent folder is used instead.

    int p;

    if ((p = targetDir.indexOf(settings.primaryFolderName)) != -1){
        //chop off all after the offending directory
        targetDir = targetDir.substring(0, p);
    }

    if ((p = targetDir.indexOf(settings.backupFolderName)) != -1){
        //chop off all after the offending directory
        targetDir = targetDir.substring(0, p);
    }

    //add a separator if not one already at the end
    if (!targetDir.endsWith(File.separator)) {targetDir += File.separator;}

    //create the primary data directory
    //note the extra space before "Primary" in the path name - this forces the
    //primary to be listed first alphabetically when viewed in a file navigator
    File primaryDir = new File (targetDir + settings.primaryFolderName);
    if (!primaryDir.exists() && !primaryDir.mkdirs()){
        displayErrorMessage("Could not create the primary data directory -"
                + " no directories created.");
        return;
    }

    //create the backup data directory
    File backupDir = new File (targetDir + settings.backupFolderName);
    if (!backupDir.exists() && !backupDir.mkdirs()){
        displayErrorMessage("Could not create the backup data directory -"
                + " only the primary directory was created.");
        return;
    }

    //only save the new paths to the Main Static Settings.ini file if both
    //folders were successfully created or already existed
    //toString will return paths with / or \ separators depending on the system
    //the software is installed on - apply a separator the to end as well
    settings.primaryDataPath = primaryDir.toString() + File.separator;
    settings.backupDataPath = backupDir.toString() + File.separator;

    saveMainStaticSettings();

}//end of MainWindow::setupSystem
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::displayCalWindow
//
// Prepares and displays the calibration window.
//

public void displayCalWindow(String pActionCommand)
{

    // the index number of the chart group and the chart which initiated the cal
    // window display is appended to the action command - parse them to integers

    int invokingChartGroupIndex =
       Integer.valueOf( pActionCommand.substring(pActionCommand.indexOf('~')+1,
                                             pActionCommand.lastIndexOf('~')));

    int invokingChartIndex = Integer.valueOf(
                 pActionCommand.substring(pActionCommand.lastIndexOf('~')+1));

    //clear out the list of channels assigned to the chart
    for (int i = 0; i < numberOfChannels; i++) {calChannels[i] = null;}

    //For UT, clicking either trace labeled ID or OD brings up all channels
    //for the chart regardless of trace - this is because ID/OD is tied to all
    //traces.  In the future, perhaps add switch for other types of systems so
    //that clicking on one trace only brings up the channels tied to that trace.

    //used to calculate number of channels tied to the invoking chart
    int i = 0, numberOfChartChannels;

    //scan through all channels and their gates, processing data from any that
    //match the current graph and trace number - see note above for future mods
    //to match the trace number also for non-UT systems

    int numberOfGates;

    for (int ch = 0; ch < numberOfChannels; ch++){

        //each channel gets a assigned a screen control by the UTCalibrator
        //object -- clear any previous settings out
        hardware.getChannels()[ch].calRadioButton = null;

        //get the number of gates for the currently selected channel
        numberOfGates = hardware.getNumberOfGates(ch);

        for (int g = 0; g < numberOfGates; g++){

            //store a reference to each channel with a gate which is tied to the
            //invoking chart group and chart and count the number of matches
            //multiple gates for a single channel may be tied to the same trace,
            //so break to the next channel when the first gate matches so the
            //channel is not added more than once

            if (hardware.getGate(ch, g).chartGroup == invokingChartGroupIndex &&
                        hardware.getGate(ch, g).chart == invokingChartIndex){
                calChannels[i++] = hardware.getChannels()[ch];
                break;
            }
        }//for (int g = 0;...
    }//for (int ch = 0;...

    numberOfChartChannels = i; //this is the number of matching channels found

    calWindow.setChannels(numberOfChartChannels, calChannels,
            settings.chartGroups
                [invokingChartGroupIndex].getStripChart(invokingChartIndex),
                numberOfChannels, hardware.getChannels());

    //if the cal window is currently at the alternate screen location, set
    //it to the primary location -- this allows the user to quickly move the
    //window to an easily accessible position...but if the user has moved the
    //window somewhere specific then it will open at that location again
    
    if (calWindow.getLocation().x == settings.calWindowAltLocationX
          && calWindow.getLocation().y == settings.calWindowAltLocationY){
        calWindow.setLocation(
                    settings.calWindowLocationX, settings.calWindowLocationY);
    }

    calWindow.setVisible(true);

}//end of MainWindow::displayCalWindow
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::processMainTimerEvent
//
// Handles any duties for the timer such as scanning the transducer and
// plotting the results.
//

public void processMainTimerEvent()
{

    if (settings.beginExitProgram){
        //shut down hardware, clean up, save data
        //will also set beginExitProgram false and exitProgram true so this
        //will only get called once
        prepareToExitProgram(settings.saveOnExit);
        return;
    }

    //After the beginExitProgram has been called, exitProgram will be true.  Monitor
    //the pointer fileSaver - if it is not null then data is still being saved.
    //When it goes null, saving is finished and the program can be shut down.
    //Must stop the timer or it will call again during the destruction of objects
    //it accesses.

    if (settings.exitProgram) {

        if (settings.fileSaver != null) {return;} //wait until saving is done

        exitProgram();

        return;

    }

    //if the hardware interface has received an end of piece signal, save the
    //finished piece and prepare for the next one
    if (hardware.prepareForNewPiece){
        hardware.prepareForNewPiece = false;
        try{
            handlePieceTransition();
        }
        catch(IOException e){
            logStackTrace("Error: 2052", e);
        }
    }

    //plot data on the graphs - if no data is being collected by the hardware
    //because it is not in scan or inspect mode, then nothing will be plotted
    doScan();

    //allow the Capulin1 interface to handle necessary tasks
    hardware.doTasks();

    //if in monitor mode, retrieve I/O status info from Capulin1
    if (monitorWindow.isVisible()) {
        monitorActive = true;
        byte[] monitorBuffer = hardware.getMonitorPacket(true);
        monitorWindow.updateStatus(monitorBuffer);
    }
    else if (monitorActive){ //if in monitor mode and window closes, exit mode
        monitorActive = false;
        if (!encoderCalibratorActive) { hardware.stopMonitor(); }
    }

    //if Encoder Calibrator visible, update I/O status from the hardware
    //using the same functions as the Monitor window
    if (encoderCalibrator.isVisible()) {
        encoderCalibratorActive = true;
        byte[] monitorBuffer = hardware.getMonitorPacket(true);
        encoderCalibrator.updateStatus(monitorBuffer);
    }
    else if (encoderCalibratorActive){
        //if Encoder Calibrator visible and window closes, exit the mode
        encoderCalibratorActive = false;
        if (!monitorActive) { hardware.stopMonitor(); }
    }

    //if Jack Stand Setup window is visible, update I/O status Hardware object
    if (jackStandSetup.isVisible()) {
        jackStandSetupActive = true;
        jackStandSetup.setEncoderCalValues(
                               hardware.getEncoderCalValues(encoderCalValues));
    }
    else if (jackStandSetupActive){
        //if window visible and window closes, exit the mode
        jackStandSetupActive = false;
    }
        
    //if the cal window is opened, allow it to update it's display with the latest
    //A-Scan from the currently selected channel
    if (calWindow.isVisible()){
        //the channel index in the UTCalibrator object only relates to the list of
        //channels currently being handled by the UTCalibrator - use the channel's
        //actual channelIndex number stored in the channel object itself for
        //retrieving data

        if (calWindow.channels[calWindow.currentChannelIndex] != null){

            aScan = hardware.getAScan(
                calWindow.channels[calWindow.currentChannelIndex].channelNum);

            if (aScan != null) {
                calWindow.displayData(aScan.range,
                        aScan.interfaceCrossingPosition, aScan.buffer);
            }
        }
    }// if (calWindow.isVisible())

    //hide the flag report dialog if need be
    if (printFlagReportDialog != null
            && closePrintFlagReportDialogTimer-- == 0){

        printFlagReportDialog.closeAndDispose();
        printFlagReportDialog = null;

    }

}//end of MainWindow::processMainTimerEvent
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::doScan
//
// Handles update of displays.
//

public void doScan()
{

    for (int i = 0; i < settings.numberOfChartGroups; i++) {
        settings.chartGroups[i].plotData();
    }

}//end of MainWindow::doScan
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::stateChanged
//
// Responds to value changes in spinners, etc.
//
// You can tell which item was changed by using similar to:
//
// Object source = e.getSource();
//
// For simplicities sake, the following just updates all controls any time any
// one control is changed.
//

@Override
public void stateChanged(ChangeEvent e)
{

    //copy values and states of all display controls to the Global variable set
    updateAllSettings();

}//end of MainWindow::stateChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::insertUpdate
//
// Responds to value changes in text areas and fields, specifically when a
// character is inserted. NOTE: This will get called after EVERY character
// inserted - updateAllSettings will get called every time but this shouldn't
// be a problem.
//

@Override
public void insertUpdate(DocumentEvent ev)
{

    //copy values and states of all display controls to the Global variable set
    updateAllSettings();

}//end of MainWindow::insertUpdate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::removeUpdate
//
// Responds to value changes in text areas and fields, specifically when a
// character is removed. NOTE: This will get called after EVERY character
// removed - updateAllSettings will get called every time but this shouldn't
// be a problem.
//

@Override
public void removeUpdate(DocumentEvent ev)
{

    //copy values and states of all display controls to the Global variable set
    updateAllSettings();

}//end of MainWindow::removeUpdate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::changedUpdate
//
// Responds to value changes in text areas and fields, specifically when the
// style of the text is changed.
//

@Override
public void changedUpdate(DocumentEvent ev)
{

    //copy values and states of all display controls to the Global variable set
    updateAllSettings();

}//end of MainWindow::changedUpdate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::updateAllSettings
//
// Copies the values and states of all display controls to the corresponding
// variables in the Settings object.
//
// This function should be called when ANY control is modified so that the new
// states and values will be copied to the variable set.
//

public void updateAllSettings()
{

    //settings.scanXDistance = scanXDistance.getDoubleValue();
    //settings.transducerInfo.serialNumber = transducerSN.getText();

    //flag that settings have been modified
    settings.setOptionsModifiedFlag(true);

}//end of MainWindow::updateAllSettings
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::changeLanguage
//
// Calls all objects to force them to load new translation text per the
// language passed via pLanguage.
//

@Override
public void changeLanguage(String pLanguage)
{

    //update text directly controlled by this object
    loadLanguage(pLanguage);

    //force all other objects to update their text displays

    mainMenu.loadLanguage(pLanguage);

    //force all components to repaint to make sure the text is updated
    mainFrame.getContentPane().repaint();

}//end of MainWindow::changeLanguage
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::loadLanguage
//
// Sets the text displayed by various controls according to the selected
// language.
//
// Sets class variable "language" to "pLanguage" so outside classes can call
// this function to both set the variable and load new translations.
//


private void loadLanguage(String pLanguage)
{

    language = pLanguage;

    IniFile ini;

    //if the ini file cannot be opened and loaded, exit without action
    try {
        ini = new IniFile(
       "language\\Main Window - Capulin UT.language", Settings.mainFileFormat);
        ini.init();
    }
    catch(IOException e){
        logSevere(e.getMessage() + " - Error: 2360");
        return;
    }

}//end of MainWindow::loadLanguage
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::triggerProgramExit
//
// Triggers program exit by signalling the main timer function to perform
// the shutdown process.
//
// If pRestart = true, a new main frame is created.  This must be
// done to switch between configurations and presets as everything must be
// rebuilt to match the settings in the config file.
//
// If pSave is true, all job info data files are saved when the program is
// exited.
//

public void triggerProgramExit(boolean pSave, boolean pRestart)
{

    //signal timer to begin shut down process
    settings.beginExitProgram = true;
    //signal timer to save data or not
    settings.saveOnExit = pSave;
    //signal timer to restart the program or not
    settings.restartProgram = pRestart;

}//end of MainWindow::triggerProgramExit
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::prepareToExitProgram
//
// Saves files and cleans up in preparation for shut down.
//
// If pSave is true, all job info data files are saved.
//

public void prepareToExitProgram(boolean pSave)
{

    //stop the timer from calling this program repeatedly until close
    settings.beginExitProgram = false;
    //signal timer to watch for time to exit
    settings.exitProgram = true;

    //tell the main thread to shut down the hardware
    settings.triggerHardwareShutdown = true;

    //if flag is true, save all data
    //the timer function should monitor conditions to make sure save is
    //completed before exiting since the save is handled by a separate thread
    if (pSave) {saveEverything();}

}//end of MainWindow::prepareToExitProgram
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::exitProgram
//
// Destroys the main window component, exiting the program.
//
// If pRestart = true, a new main frame is created.  This must be
// done to switch between configurations and presets as everything must be
// rebuilt to match the settings in the config file.
//

private void exitProgram()
{

    //stop calling this timer during shutdown
    mainTimer.stop();

    // stop the main execution thread
    // wait until the main thread dies before shutting down
    // If the thread is in hardware.connect, it will miss the interrupt
    // (caught by code somewhere in the connect process?) so keep interrupting
    // until successful.
    // Note that if the thread has called hardware.connect and the boards are
    // not responding, it may be a while before the thread will respond to an
    // interrupt.

    while (mainThread.isAlive()){ mainThread.interrupt(); }

    //release the lock on multiple instance preventer file to allow a new
    //instance to be created without a warning
    MultipleInstancePreventer.removeLock();

    //disposing of the frame will exit the program
    mainFrame.setVisible(false);
    mainFrame.dispose();
    mainFrame = null;

    //to get the program to cleanly exit while debugging in the IDE, must
    //use System.exit(0) - this may cause an exception if the program is
    //used as an Applet, so may want to test if program is an Applet and
    //skip this step in that case

    MainWindow mainWindow;

    //if a restart was requested, make a new main frame and start over
    if (settings.restartProgram) {

        mainWindow = new MainWindow();
        mainWindow.init();
        return;
    }
    else {
        System.exit(0);
    }

}//end of MainWindow::exitProgram
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::windowClosing
//
// Handles actions necessary when the main window (and the program) are closed
// by clicking on the "X" icon.
//
// The default close option for the main JFrame should have been set to
// DO_NOTHING_ON_CLOSE in the constructor because we want to save files
// and clean up before actually closing.  If the default option is left in
// place, EXIT_ON_CLOSE, then the program will exit after this WindowClosing
// function finishes - aborting any file saving or clean up in progress.
//
// To close the program, a flag is set to signal the main timer to start the
// shut down sequence in an orderly fashion.
//

@Override
public void windowClosing(WindowEvent e)
{

    triggerProgramExit(true, false);

}//end of MainWindow::windowClosing
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::finalize
//
// Handles actions necessary when the main window (and the program) are closed
// by clicking on the "X" icon or by dispatching a WINDOW_CLOSING event from
// code triggered by the "File/Exit" option.
//
// NOTE: This function doesn't seem to get called for a JFrame when it is
// disposed of - the Java VM probably ceases running before that happens.
//

// function not used -- comments left here as instructional --

//end of MainWindow::finalize
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::(various window listener functions)
//
// These functions are implemented per requirements of interface WindowListener
// but do nothing at the present time.  As code is added to each function, it
// should be moved from this section and formatted properly.
//

@Override
public void windowClosed(WindowEvent e){}
@Override
public void windowOpened(WindowEvent e){}
@Override
public void windowIconified(WindowEvent e){}
@Override
public void windowDeiconified(WindowEvent e){}
@Override
public void windowActivated(WindowEvent e){}
@Override
public void windowDeactivated(WindowEvent e){}

//end of MainWindow::(various window listener functions)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::componentResized
//
// Handles actions necessary when the window is resized by the user.
//

@Override
public void componentResized(ComponentEvent e)
{

    //pack the window back to its smallest size again, effectively preventing
    //the resize attempt

    mainFrame.pack();

}//end of MainWindow::componentResized
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::displayErrorMessage
//
// Displays an error dialog with message pMessage.
//

private void displayErrorMessage(String pMessage)
{

    JOptionPane.showMessageDialog(mainFrame, pMessage,
                                            "Error", JOptionPane.ERROR_MESSAGE);

}//end of MainWindow::displayErrorMessage
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::displayInfoMessage
//
// Displays an information dialog with message pMessage.
//

private void displayInfoMessage(String pMessage)
{

    JOptionPane.showMessageDialog(mainFrame, pMessage,
                                       "Info", JOptionPane.INFORMATION_MESSAGE);

}//end of MainWindow::displayInfoMessage
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::displayWarningMessage
//
// Displays a warning dialog with message pMessage.
//

private void displayWarningMessage(String pMessage)
{

    JOptionPane.showMessageDialog(mainFrame, pMessage,
                                       "Warning", JOptionPane.WARNING_MESSAGE);

}//end of MainWindow::displayWarningMessage
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::deleteFileIfOverSizeLimit
//
// If file pFilename is larger than pLimit, the file is deleted.
//

private void deleteFileIfOverSizeLimit(String pFilename, int pLimit)
{

    //delete the logging file if it has become too large

    Path p1 = Paths.get(pFilename);

    try {
        if (Files.size(p1) > pLimit){
            Files.delete(p1);
        }
    }
    catch(NoSuchFileException nsfe){
        //do nothing if file not found -- will be recreated as needed
    }
    catch (IOException e) {
        //do nothing if error on deletion -- will be deleted next time
        logStackTrace("Error: 2488", e);
    }

}//end of MainWindow::deleteFileIfOverSizeLimit
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::setupJavaLogger
//
// Prepares the Java logging system for use. Output is directed to a file.
//
// Each time the method is called, it checks to see if the file is larger
// than the maximum allowable size and deletes the file if so.
//

private void setupJavaLogger()
{

    String logFilename = "Java Logger File.txt";

    //prevent the logging file from getting too big
    deleteFileIfOverSizeLimit(logFilename, ERROR_LOG_MAX_SIZE);

    //remove all existing handlers from the root logger (and thus all child
    //loggers) so the output is not sent to the console

    Logger rootLogger = Logger.getLogger("");
    Handler[] handlers = rootLogger.getHandlers();
    for(Handler handler : handlers) {
        rootLogger.removeHandler(handler);
    }

    //add a new handler to send the output to a file

    Handler fh;

    try{

        //write log to logFilename, 10000 byte limit on each file, rotate
        //between two files, append the the current file each startup

        fh = new FileHandler(logFilename, 10000, 2, true);

        //direct output to a file for the root logger and  all child loggers
        Logger.getLogger("").addHandler(fh);

        //use simple text output rather than default XML format
        fh.setFormatter(new SimpleFormatter());

        //record all log messages
        Logger.getLogger("").setLevel(Level.WARNING);

    }
    catch(IOException e){
        logStackTrace("Error: 2539", e);
    }

}//end of MainWindow::setupJavaLogger
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::logSevere
//
// Logs pMessage with level SEVERE using the Java logger.
//

void logSevere(String pMessage)
{

    Logger.getLogger(getClass().getName()).log(Level.SEVERE, pMessage);

}//end of MainWindow::logSevere
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::logStackTrace
//
// Logs stack trace info for exception pE with pMessage at level SEVERE using
// the Java logger.
//

void logStackTrace(String pMessage, Exception pE)
{

    Logger.getLogger(getClass().getName()).log(Level.SEVERE, pMessage, pE);

}//end of MainWindow::logStackTrace
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// MainWindow::(various component listener functions)
//
// These functions are implemented per requirements of interface
// ComponentListener but do nothing at the present time.  As code is added to
// each function, it should be moved from this section and formatted properly.
//

@Override
public void componentHidden(ComponentEvent e){}
@Override
public void componentShown(ComponentEvent e){}
@Override
public void componentMoved(ComponentEvent e){}

//end of MainWindow::(various component listener functions)
//-----------------------------------------------------------------------------


}//end of class MainWindow
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------s

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class Main
//

public class Main{

//-----------------------------------------------------------------------------
// Main::createAndShowGUI
//
// Create the GUI and show it. For thread safety, this method should be invoked
// from the event-dispatching thread.  This is usually done by using
// invokeLater to schedule this funtion to be called from inside the event-
// dispatching thread.  This is necessary because the main function is not
// operating in the event-dispatching thread.  See the main function for more
// info.
//

private static void createAndShowGUI()
{

    //instantiate an object to create and handle the main window JFrame
    MainWindow mainWindow = new MainWindow();
    mainWindow.init();

}//end of Main::createAndShowGUI
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Main::main
//

public static void main(String[] args)
{

    //Schedule a job for the event-dispatching thread:
    //creating and showing this application's GUI.

    javax.swing.SwingUtilities.invokeLater(() -> {
        createAndShowGUI();
    });

}//end of Main::main
//-----------------------------------------------------------------------------

}//end of class Main
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// Useful debugging code

// - sets to a light green
//setBackground(new Color(153, 204, 0));
//end

// - sets to red
//setBackground(new Color(255, 0, 0));
//end

//displays message on bottom panel of IDE
//System.out.println("File not found");

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
