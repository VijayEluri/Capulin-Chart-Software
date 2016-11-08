/******************************************************************************
* Title: JackStandSetup.java
* Author: Mike Schoonover
* Date: 11/06/16
*
* Purpose:
*
* This class displays a window for entering distances for the movable jack
* stands and their sensors.
*
* Open Source Policy:
*
* This source code is Public Domain and free to any interested party.  Any
* person, company, or organization may do with it as they please.
*
*/

//-----------------------------------------------------------------------------

package chart;

import chart.mksystems.inifile.IniFile;
import java.awt.*;
import java.awt.event.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class JackStandSetup
//
// This class displays a window for calibrating the encoder counts per distance
// values.
//

class JackStandSetup extends JDialog implements ActionListener {

    private JPanel mainPanel;

    private JPanel displayPanel;

    private JLabel calMessage;

    JCheckBox displayMonitorCB, enableEyeDistanceInputsCB;
    
    JButton applyBtn;
    
    private Font blackFont, redFont;

    private final ActionListener actionListener;
    private final IniFile configFile;

    int numEntryJackStands, numExitJackStands;
    
    ArrayList<SensorSetGUI> sensorSetGUIs = new ArrayList<>(1);

//-----------------------------------------------------------------------------
// JackStandSetup::JackStandSetup (constructor)
//
//

public JackStandSetup(JFrame frame, IniFile pConfigFile,
                                                ActionListener pActionListener)
{

    super(frame, "Jack Stand Setup");

    actionListener = pActionListener;
    configFile = pConfigFile;

}//end of JackStandSetup::JackStandSetup (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::init
//
// Initializes the object.
//

public void init()
{

    configure(configFile);
        
    setupGUI();
    
    pack();

}//end of JackStandSetup::init
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupGUI
//

private void setupGUI()
{

    mainPanel = new JPanel();
    mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
    mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

    add(mainPanel);
  
    displayMonitorCB = new JCheckBox("Display Monitor");
    displayMonitorCB.setActionCommand("Display Monitor");
    displayMonitorCB.addActionListener(this);
    
    enableEyeDistanceInputsCB = new JCheckBox("Enable Eye Distance Editing");
    enableEyeDistanceInputsCB.setActionCommand("Enable Eye Distance Editing");
    enableEyeDistanceInputsCB.addActionListener(this);
     
    displayPanel = new JPanel();
    mainPanel.add(displayPanel);
    
    setupDisplayPanel(displayPanel);
        
    mainPanel.add(Box.createRigidArea(new Dimension(0,7))); //vertical spacer    
    
    setupControlsPanel(mainPanel);
    
}//end of JackStandSetup::setupGUI
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::resetDisplayPanel
//
// Removes all components from the displayPanel and sets it up again. Useful
// when switching between displaying Monitor values and not displaying them.
//
// Garbage collection run afterwards to clean up all the objects orphaned by
// this operation.
//

private void resetDisplayPanel()
{

    displayPanel.removeAll();
  
    setupDisplayPanel(displayPanel);
    
    pack();
    
    repaint();
    
    System.gc();

}//end of JackStandSetup::resetDisplayPanel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setEyeDistanceEditingEnabled
//
// Sets the enable state of the eye distance inputs text fields.
//

private void setEyeDistanceEditingEnabled()
{

    setJackStandsEyeDistanceEditingEnabled(
                                        enableEyeDistanceInputsCB.isSelected());
    
}//end of JackStandSetup::setEyeDistanceEditingEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupDisplayPanel
//
// Adds various inputs and displays to pPanel. Calling function should
// add the panel to its parent.
//
//

private void setupDisplayPanel(JPanel pPanel)
{

    pPanel.setLayout(new BoxLayout(pPanel, BoxLayout.Y_AXIS));    
    
    setupDisplayGrid(pPanel);
  
    pPanel.add(Box.createRigidArea(new Dimension(0,7))); //vertical spacer

    setupMsgDisplayPanel(pPanel);
        
}//end of JackStandSetup::setupDisplayPanel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupDisplayGrid
//
// Creates a grid of labels, inputs, and displays for the various sensors.
//

private void setupDisplayGrid(JPanel pParentPanel)
{
    
    JPanel panel = new JPanel();
    
    panel.setAlignmentX(Component.CENTER_ALIGNMENT);
    
    //use grid layout with enough rows to display all entry and exit
    //jackstands, unit entry eye, unit exit eye, and two rows for a header
    //and blank separator row, and two separator rows between groups
    
    int numGridRows = numEntryJackStands + numExitJackStands + 2 + 4;
    
    int numGridCols;
    
    if(displayMonitorCB.isSelected()){
        numGridCols = 9;
    }else{
        numGridCols = 4;
    }
    
    panel.setLayout(new GridLayout(numGridRows, numGridCols, 10, 10));

    panel.setOpaque(true);
    pParentPanel.add(panel);
        
    addLabels(panel, 1,"");
    addLabel(panel, "  Eye A", "distance to jack center");
    addLabel(panel, "Jack Stand", "distance to unit");
    addLabel(panel, "  Eye B", "distance to jack center");

        //these only visible if Display Monitor checkbox is checked
    if (displayMonitorCB.isSelected()){
        addLabel(panel, "State", "indicates which sensor last changed and "
                                        + "whether it is blocked or unblocked");
        addLabel(panel, "Encoder 1", "counts at time of state change");
        addLabel(panel, "Encoder 2", "counts at time of state change");
        addLabel(panel, "Direction",
                                "conveyor direction at time of state change");
        addLabel(panel, "% change",
                                "% calibration change since last calculation");        
    }
        
    addLabels(panel, numGridCols, "");
    
    setupEntryJackStandGUIs(panel);

    addLabels(panel, numGridCols, "");
    
    setUpUnitSensorGUIs(panel);
        
    addLabels(panel, numGridCols, "");
    
    setupExitJackStandGUIs(panel);

}//end of JackStandSetup::setupDisplayGrid
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupMsgDisplayPanel
//
// Creates a panel with a label for display eye-encoder matching messages
// from the PLC.
//

private void setupMsgDisplayPanel(JPanel pParentPanel)
{
    
    //do not display if monitor display is not selected
    if(!displayMonitorCB.isSelected()) { return; }
    
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createTitledBorder(
                                        "Sensor-Encoder Calibration Messages"));
    JackStandSetup.setSizes(panel, 700, 50);
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setOpaque(true);
    panel.setAlignmentX(Component.CENTER_ALIGNMENT);
    pParentPanel.add(panel);
        
    calMessage = new JLabel("waiting for message...");
    calMessage.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.add(calMessage);
    
}//end of JackStandSetup::setupMsgDisplayPanel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupControlsPanel
//
// Creates a panel with various controls.
//

private void setupControlsPanel(JPanel pParentPanel)
{
    
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
    panel.setOpaque(true);
    panel.setAlignmentX(Component.CENTER_ALIGNMENT);
    pParentPanel.add(panel);
        
    panel.add(displayMonitorCB);
    
    panel.add(enableEyeDistanceInputsCB);

    panel.add(Box.createHorizontalGlue());
    
    applyBtn = new JButton("Apply");
    applyBtn.setActionCommand("Apply");
    applyBtn.addActionListener(this);
    applyBtn.setEnabled(false);
    panel.add(applyBtn);

}//end of JackStandSetup::setupControlsPanel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::addLabels
//
// Adds label to pPanel with pText and pToolTip.
//

private void addLabel(JPanel pPanel, String pText, String pToolTip)
{
    
    JLabel label;
    pPanel.add(label = new JLabel(pText));
    label.setToolTipText(pToolTip);

}//end of JackStandSetup::addLabelWithToolTip
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::addLabels
//
// Adds pNumLabels labels to mainPanel with pText.
//

private void addLabels(JPanel pPanel, int pNumLabels, String pText)
{
    
    for(int i=0; i<pNumLabels; i++){
    
        pPanel.add(new JLabel(pText));
    
    }
    
}//end of JackStandSetup::addLabels
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupEntryJackStandGUIs
//
// Adds a GUI panel for each entry jack stand.
//

private void setupEntryJackStandGUIs(JPanel pPanel)
{
    
    //display entry jacks in reverse order to depict physical layout
    
    for (int i=numEntryJackStands-1; i>=0; i--){

        SensorSetGUI set;
        
        set = new JackStandGUI(i, pPanel, this);
        
        set.init("Entry Jack", 
         enableEyeDistanceInputsCB.isSelected(), displayMonitorCB.isSelected());
        
        sensorSetGUIs.add(set);
        
    }
    
}//end of JackStandSetup::setupEntryJackStandGUIs
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setupExitJackStandGUIs
//
// Adds a GUI panel for each exit jack stand.
//

private void setupExitJackStandGUIs(JPanel pPanel)
{

    //display exit jacks in forward order to depict physical layout
    
    for (int i=0; i<numExitJackStands; i++){

        SensorSetGUI set;
        
        set = new JackStandGUI(i, pPanel, this);
        
        set.init("Exit Jack", 
         enableEyeDistanceInputsCB.isSelected(), displayMonitorCB.isSelected());
        
        sensorSetGUIs.add(set);
                
    }
    
}//end of JackStandSetup::setupExitJackStandGUIs
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setUpUnitSensorGUIs
//
// Adds a GUI panel for each unit sensor, such as the exit (inspection start)
// and exit (inspection end).
//

private void setUpUnitSensorGUIs(JPanel pPanel)
{
    
    SensorSetGUI set;

    //use negative set numbers so the numbers are not displayed or used to
    //load cal data...the sensor title is explicit and does need a number
    
    set = new SensorGUI(-1, pPanel, this);

    set.init("Entry Sensor", 
     enableEyeDistanceInputsCB.isSelected(), displayMonitorCB.isSelected());

    sensorSetGUIs.add(set);
                
    set = new SensorGUI(-2, pPanel, this);

    set.init("Exit Sensor", 
     enableEyeDistanceInputsCB.isSelected(), displayMonitorCB.isSelected());

    sensorSetGUIs.add(set);

}//end of JackStandSetup::setUpUnitSensorGUIs
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setJackStandsEyeDistanceEditingEnabled
//
// Sets the enabled flag for all eye distance text fields.
//

private void setJackStandsEyeDistanceEditingEnabled(boolean pEnabled)
{

    sensorSetGUIs.stream().forEach((set) -> {
        set.setEyeDistanceEditingEnabled(pEnabled);
    });
        
}//end of JackStandSetup::setJackStandsEyeDistanceEditingEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setLabelWithFormattedValue
//
//

private void setLabelWithFormattedValue(JLabel pLabel, String pText, 
                                                                 double pValue)
{
    
    pLabel.setText(pText + new  DecimalFormat("#.####").format(pValue));

}//end of JackStandSetup::setLabelWithFormattedValue
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::refreshAllLabels
//
// Updates all labels with their associated variable values.
//

public void refreshAllLabels()
{

    
}//end of JackStandSetup::refreshAllLabels
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::actionPerformed
//
// Catches action events from buttons, etc.
//
// Passes some of them on to the specified actionListener.
//

@Override
public void actionPerformed(ActionEvent e)
{

    if ("Display Monitor".equals(e.getActionCommand())) {
        resetDisplayPanel();
        return;
    }
    
    if ("Enable Eye Distance Editing".equals(e.getActionCommand())) {
        setEyeDistanceEditingEnabled();
        return;
    }

    if ("Text Field Changed".equals(e.getActionCommand())) {
        applyBtn.setEnabled(true);
        return;
    }

    if ("Apply".equals(e.getActionCommand())) {
        handleApplyButton();
        return;
    }
        
}//end of JackStandSetup::actionPerformed
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::updateStatus
//
// Updates the display to show the current state of the I/O.
//

public void updateStatus()
{

    
}//end of JackStandSetup::updateStatus
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::handleApplyButton
//
// Handles clicks of the Apply button.
//

private void handleApplyButton()
{

    applyBtn.setEnabled(false);
    
}//end of JackStandSetup::handleApplyButton
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::setSizes
//
// Sets the min, max, and preferred sizes of pComponent to pWidth and pHeight.
//

static void setSizes(Component pComponent, int pWidth, int pHeight)
{

    pComponent.setMinimumSize(new Dimension(pWidth, pHeight));
    pComponent.setPreferredSize(new Dimension(pWidth, pHeight));
    pComponent.setMaximumSize(new Dimension(pWidth, pHeight));

}//end of JackStandSetup::setSizes
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandSetup::configure
//
// Loads configuration settings from the configuration.ini file and configures
// the object.
//

private void configure(IniFile pConfigFile)
{

    numEntryJackStands =
            pConfigFile.readInt("Hardware", "Number of Entry Jack Stands", 4); //debug mks -- set to 0

    numExitJackStands =
            pConfigFile.readInt("Hardware", "Number of Exit Jack Stands", 4); //debug mks -- set to 0
    
}//end of JackStandSetup::configure
//-----------------------------------------------------------------------------

}//end of class JackStandSetup
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class SensorSetGUI
//
// This is a parent class which handles one or more sensors as a set.
// It handles GUI components for interfacing with the sensor values.
//

class SensorSetGUI{

    ActionListener actionListener;

    KeyAdapter keyAdapter;
    
    JPanel mainPanel;

    int setNum;
    String title;
    
    JLabel rowLabel;
    JTextField eyeATF;
    JTextField jackStandTF;
    JTextField eyeBTF;
    JLabel stateLbl;
    JLabel encoder1Lbl;
    JLabel encoder2Lbl;
    JLabel directionLbl;
    JLabel percentChangeLbl;        
    
//-----------------------------------------------------------------------------
// SensorSetGUI::SensorSetGUI (constructor)
//
//

public SensorSetGUI(
                int pSetNum, JPanel pMainPanel, ActionListener pActionListener)
{

    mainPanel = pMainPanel; setNum = pSetNum;

    actionListener = pActionListener;
    
    createKeyAdapter();
        
}//end of SensorSetGUI::SensorSetGUI (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// SensorSetGUI::init
//
// Initializes the object.
//

public void init(String pTitle, boolean pEnableEyeDistanceInputs,
                                                       boolean pDisplayMonitor)
{
            
    //if the set number is negative, it is not used in the title
    //in such case, the title is expected to be unique without the number
    
    if (setNum >= 0){
        title = pTitle + " " + setNum;
    }else{
        title = pTitle;
    }
    
}//end of SensorSetGUI::init
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// SensorSetGUI::setEyeDistanceEditingEnabled
//
// Sets the enabled flag for all eye distance text fields.
//

void setEyeDistanceEditingEnabled(boolean pEnabled)
{

    if(eyeATF != null) { eyeATF.setEnabled(pEnabled); }
    if(eyeBTF != null) { eyeBTF.setEnabled(pEnabled); }
        
}//end of SensorSetGUI::setEyeDistanceEditingEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// SensorSetGUI::createKeyAdapter
//
// Creates a KeyAdapter which can be used to monitor when the user makes
// changes in a Text Field.
//

private void createKeyAdapter()
{
    
    keyAdapter = new KeyAdapter(){
        
        @Override
        public void keyPressed(KeyEvent ke)
        {
            //this section will execute only when user is editing the JTextField
            if(!(ke.getKeyChar()==27||ke.getKeyChar()==65535)){
                actionListener.actionPerformed(
                                new ActionEvent(this, 1, "Text Field Changed"));
            }
        }
    };

}//end of SensorSetGUI::createKeyAdapter
//-----------------------------------------------------------------------------

}//end of class SensorSetGUI
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class JackStandGUI
//
// This class a set of labels, inputs, and displays for a single jack stand.
//

class JackStandGUI extends SensorSetGUI{
    
//-----------------------------------------------------------------------------
// JackStandGUI::JackStandGUI (constructor)
//
//

public JackStandGUI(int pJackNum, JPanel pMainPanel,
                                                ActionListener pActionListener)
{

    super(pJackNum, pMainPanel, pActionListener);
        
}//end of JackStandGUI::JackStandGUI (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// JackStandGUI::init
//
// Initializes the object.
//

@Override
public void init(String pTitle, boolean pEnableEyeDistanceInputs,
                                                       boolean pDisplayMonitor)
{

    super.init(pTitle, pEnableEyeDistanceInputs, pDisplayMonitor);
    
    rowLabel = new JLabel(title);
    eyeATF = new JTextField(4); eyeATF.addKeyListener(keyAdapter);
    jackStandTF = new JTextField(4); jackStandTF.addKeyListener(keyAdapter);
    eyeBTF = new JTextField(4); eyeBTF.addKeyListener(keyAdapter);
    stateLbl = new JLabel("?");
    encoder1Lbl = new JLabel("?");
    encoder2Lbl = new JLabel("?");
    directionLbl = new JLabel("?");
    percentChangeLbl = new JLabel("?");
    
    setEyeDistanceEditingEnabled(pEnableEyeDistanceInputs);
    
    if(eyeATF != null) { eyeATF.setText("12454"); } //debug mks -- remove this
    
    mainPanel.add(rowLabel);
    mainPanel.add(eyeATF);
    mainPanel.add(jackStandTF);
    mainPanel.add(eyeBTF);

    //these only visible if Display Monitor checkbox is checked
    if (pDisplayMonitor){
    
        mainPanel.add(stateLbl);
        mainPanel.add(encoder1Lbl);
        mainPanel.add(encoder2Lbl);    
        mainPanel.add(directionLbl);
        mainPanel.add(percentChangeLbl);        
    }
            
}//end of JackStandGUI::init
//-----------------------------------------------------------------------------

}//end of class JackStandGUI
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class SensorGUI
//
// This class a set of labels, inputs, and displays for a single jack stand.
//

class SensorGUI extends SensorSetGUI{
    
//-----------------------------------------------------------------------------
// SensorGUI::SensorGUI (constructor)
//
//

public SensorGUI(int pSensorNum, JPanel pMainPanel,
                                                ActionListener pActionListener)
{

    super(pSensorNum, pMainPanel, pActionListener);
        
}//end of SensorGUI::SensorGUI (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// SensorGUI::init
//
// Initializes the object.
//

@Override
public void init(String pTitle, boolean pEnableEyeDistanceInputs,
                                                       boolean pDisplayMonitor)
{
    
    super.init(pTitle, pEnableEyeDistanceInputs, pDisplayMonitor);    

    rowLabel = new JLabel(title);
    stateLbl = new JLabel("?");
    encoder1Lbl = new JLabel("?");
    encoder2Lbl = new JLabel("?");
    directionLbl = new JLabel("?");
    percentChangeLbl = new JLabel("?");
    
    setEyeDistanceEditingEnabled(pEnableEyeDistanceInputs);
    
    if(eyeATF != null) { eyeATF.setText("12454"); } //debug mks -- remove this
    
    mainPanel.add(rowLabel);
    mainPanel.add(new JLabel(""));
    mainPanel.add(new JLabel(""));
    mainPanel.add(new JLabel(""));

    //these only visible if Display Monitor checkbox is checked
    if (pDisplayMonitor){
    
        mainPanel.add(stateLbl);
        mainPanel.add(encoder1Lbl);
        mainPanel.add(encoder2Lbl);    
        mainPanel.add(directionLbl);
        mainPanel.add(percentChangeLbl);        
    }
            
}//end of SensorGUI::init
//-----------------------------------------------------------------------------

}//end of class SensorGUI
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
