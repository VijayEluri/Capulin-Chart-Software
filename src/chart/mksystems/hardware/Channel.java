/******************************************************************************
* Title: Channel.java
* Author: Mike Schoonover
* Date: 4/26/09
*
* Purpose:
*
* This class handles an input channel.
*
* Open Source Policy:
*
* This source code is Public Domain and free to any interested party.  Any
* person, company, or organization may do with it as they please.
*
*/

//-----------------------------------------------------------------------------

package chart.mksystems.hardware;

import chart.mksystems.inifile.IniFile;
import chart.mksystems.stripchart.Threshold;
import chart.mksystems.stripchart.Trace;
import chart.mksystems.threadsafe.*;

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class Channel
//
// This class handles an input channel.
//

public class Channel extends Object{

    IniFile configFile;

    SyncedVariableSet syncedVarMgr;

    public boolean gateSigProcThresholdChanged;

    SyncedInteger flags1SetMask, flags1ClearMask;

    int chassisAddr, slotAddr, boardChannel;

    public UTBoard utBoard;

    public int channelIndex;
    public Gate[] gates;
    public int numberOfGates;

    public DACGate[] dacGates;
    public int numberOfDACGates;

    int scopeMax = 350;

    //used by the calibration window to store reference to the channel selectors
    //and their accompanying copy buttons
    public Object calRadioButton, copyButton;

    public String title, shortTitle, detail, type;

    boolean channelOn;  //true: pulsed/displayed, off: not pulsed/displayed
    boolean channelMasked; //true: pulsed/not display, off: pulsed/displayed

    boolean disabled = true; //overrides mode -- always off if true
    SyncedInteger mode;
    public int previousMode;
    boolean interfaceTracking = false;
    boolean dacEnabled = false, aScanSlowEnabled = false;
    boolean aScanFastEnabled = false, aScanFreeRun = true;
    double aScanDelay = 0;
    int hardwareDelay, softwareDelay;
    public int delayPix = 0;
    double aScanRange = 0;
    public SyncedInteger hardwareRange;
    public SyncedInteger aScanScale;
    SyncedDouble softwareGain;
    SyncedInteger hardwareGain1, hardwareGain2;
    int rejectLevel;
    SyncedInteger aScanSmoothing;
    int dcOffset;
    public double nSPerDataPoint;
    public double uSPerDataPoint;
    public double nSPerPixel; //used by outside classes
    public double uSPerPixel; //used by outside classes

    int firstGateEdgePos, lastGateEdgePos;
    boolean isWallChannel = false;

    int pulseChannel, pulseBank;

//-----------------------------------------------------------------------------
// Channel::Channel (constructor)
//
// The parameter configFile is used to load configuration data.  The IniFile
// should already be opened and ready to access.
//
// The constructing class should pass a pointer to a SyncedVariableSet for the
// values in this class which can be changed by the user and are sent to the
// remotes so that they will be managed in a threadsafe manner.
//

public Channel(IniFile pConfigFile, int pChannelIndex,
                                              SyncedVariableSet pSyncedVarMgr)
{

    configFile = pConfigFile; channelIndex = pChannelIndex;

    syncedVarMgr = pSyncedVarMgr;

    softwareGain = new SyncedDouble(syncedVarMgr); softwareGain.init();
    hardwareGain1 = new SyncedInteger(syncedVarMgr); hardwareGain1.init();
    hardwareGain2 = new SyncedInteger(syncedVarMgr); hardwareGain2.init();
    aScanSmoothing = new SyncedInteger(syncedVarMgr); aScanSmoothing.init();
    flags1SetMask = new SyncedInteger(syncedVarMgr); flags1SetMask.init();
    flags1ClearMask = new SyncedInteger(syncedVarMgr); flags1ClearMask.init();
    mode = new SyncedInteger(syncedVarMgr); mode.init();
    mode.setValue((int)UTBoard.POSITIVE_HALF);
    hardwareRange = new SyncedInteger(syncedVarMgr); hardwareRange.init();
    aScanScale = new SyncedInteger(syncedVarMgr); aScanScale.init();

    //read the configuration file and create/setup the charting/control elements
    configure(configFile);

}//end of Channel::Channel (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel:initialize
//
// Prepares the channel for operation.
//

public void initialize()
{

    //give the utBoard a link to the gates array
    if (utBoard != null) utBoard.linkGates(boardChannel, gates, numberOfGates);

    //set FPGA values such as sample start delay, sample size by calling each
    //function

    //always set range after setting gate parameters, delay, and interface
    //tracking as these affect the range

    // use this flag to check if any gate is an interface gate

    boolean interfaceGatePresent = false;

    //detect if both a start and end gate have been set - only if both are found
    //is the channel set up for wall as this is the same test the DSPs use
    boolean wallStartGateSet = false, wallEndGateSet = false;

    //flag if interface and wall gates are present
    for (int i = 0; i < numberOfGates; i++){
        if (gates[i].getInterfaceGate()) interfaceGatePresent = true;
        if (gates[i].isWallStartGate) wallStartGateSet = true;
        if (gates[i].isWallEndGate) wallEndGateSet = true;
        }

    //if both wall start and end gates, set channel for wall data and
    //that data will be appended to peak data packets from the DSPs

    if (wallStartGateSet && wallEndGateSet){
        isWallChannel = true;
        if (utBoard != null)
            utBoard.sendWallChannelFlag(boardChannel, isWallChannel);
        }

    //force interface tracking to false if no interface gate was set up
    //if no interface gate is present, the interface tracking checkbox will not
    //be displayed and the user cannot turn it off in case it was stored as on
    //in the job file
    //NOTE: If some but not all channels in a system have interface tracking,
    // using "Copy to All" will copy the interface tracking setting to all
    // channels. This will disable the channels without an interface gate
    // the progam is restarted and this piece of code gets executed.

    if (!interfaceGatePresent) interfaceTracking = false;

    //set bits in flags1 variable
    //all flags1 variables should be set at once in the init to avoid conflicts
    //due to all flag1 setting functions using the same mask storage variables
    int flags1Mask = UTBoard.GATES_ENABLED;
    if (dacEnabled) flags1Mask |= UTBoard.DAC_ENABLED;
    flags1Mask |= UTBoard.ASCAN_FREE_RUN;

    setFlags1(flags1Mask, true);

    //setup various things
    setAScanSmoothing(aScanSmoothing.getValue(), true);
    setRejectLevel(rejectLevel, true);
    setDCOffset(dcOffset, true);
    setMode(mode.getValue(), true);  //setMode also calls setTransducer
    setInterfaceTracking(interfaceTracking, true);
    setDelay(aScanDelay, true);

    //setRange calculates based upon some of the settings above so do last
    setRange(aScanRange, true);

    //send all the changes made above to the remotes
    sendDataChangesToRemotes();

}//end of Channel::initialize
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setMasked
//
// Sets the channelMasked flag.
//

public void setMasked(boolean pMasked)
{

    channelMasked = pMasked;

}//end of Channel::setMasked
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getNumberOfDACGates
//
// Returns the number of DAC gates.
//

public int getNumberOfDACGates()
{

    return numberOfDACGates;

}//end of Channel::getNumberOfDACGates
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACGate
//
// Returns a reference to the specified DAC gate.
//

public DACGate getDACGate(int pGate)
{

    return dacGates[pGate];

}//end of Channel::getDACGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getPointedDACGate
//
// Returns the DAC gate for which pX,pY is within 5 pixels of the adjusted
// start location.  The adjusted location is used because it takes into
// account shifting to track the interface if tracking is enabled.
//
// If no gate satisfies this criteria, -1 is returned.
//

public int getPointedDACGate(int pX, int pY)
{

    int i;

    //scan all gates for start which is near pX,pY
    for (i = 0; i < numberOfDACGates; i++)
        if (dacGates[i].getActive())
            if (pX >= (dacGates[i].gatePixStartAdjusted - 5)
                    && pX <= (dacGates[i].gatePixStartAdjusted + 5)
                    && pY >= (dacGates[i].gatePixLevel - 5)
                    && pY <= (dacGates[i].gatePixLevel + 5))
                break;

    if (i == numberOfDACGates) i = -1; //no match found

    return i;

}//end of Channel::getPointedDACGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getSelectedDACGate
//
// Returns the index of the first DAC gate which has a selectedFlag set true.
//
// If no gate satisfies this criteria, -1 is returned.
//

public int getSelectedDACGate()
{

    int i;

    //scan all gates to find the first selected one
    for (i = 0; i < numberOfDACGates; i++)
        if (dacGates[i].getActive())
            if (dacGates[i].getSelected())
                break;

    if (i == numberOfDACGates) i = -1; //no match found

    return i;

}//end of Channel::getSelectedDACGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setSelectedDACGate
//
// Sets the selectedFlag for the specified gate.  If pGate is invalid, does
// nothing.
//

public void setSelectedDACGate(int pGate, boolean pState)
{

    if (pGate < 0 || pGate >= numberOfDACGates) return;

    dacGates[pGate].setSelected(pState);

}//end of Channel::setSelectedDACGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getActiveDACGateCount
//
// Returns the number of gates which have been set active and thus are in use.
//

public int getActiveDACGateCount()
{

    int c = 0;

    //scan all gates to count the active ones
    for (int i = 0; i < numberOfDACGates; i++) if (dacGates[i].getActive()) c++;

    return c;

}//end of Channel::getActiveDACGateCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::insertDACGate
//
// Inserts a new DAC gate with the specified values into the appropriate
// place in the DAC gate array.
//
// If no gates have yet been set, insertion will be at gate 0.
// If location is before first gate, gates will be shifted to make space at 0.
// If location is after the last gate, insertion will occur at the end.
// If location is encompassed by an existing gate, the gates after that one
//   will be shifted to make space for insertion.
// If all gates are already taken, no insertion will be made.
//
// Gates inserted at the end will be given width of 35.
// Gates inserted at the beginning will have width from the pStart value to
//   the beginning of the first gate.
// Gates inserted between gates will have a width from the pStart value to the
//  end of the gate which encompasses the new location.  That gate will have
//  its width shortened by that amount to make room for the new gate.
//
// The start location of the gate will be adjusted to match the end location
// of the previous gate.
//

public void insertDACGate(int pStart, int pLevel)
{

    //do not allow insertion if gate array is full
    if (getActiveDACGateCount() == numberOfDACGates) return;

    int lastGate = getActiveDACGateCount() - 1;

    //if this is the first gate to be set, insert at gate 0
    if (getActiveDACGateCount() == 0){
        setDACGatePixelValues(0, pStart, pStart+35, pLevel, true, false);
        return;
        }

    //if the new gate is located before the first gate, then insert at the
    //beginning
    if (pStart < dacGates[0].gatePixStart){
        int pEnd = dacGates[0].gatePixStart; //ends where first gate starts
        shiftDACGatesUp(0); //shift gates to make room
        setDACGatePixelValues(0, pStart, pEnd, pLevel, true, false);
        return;
        }

    //if the new gate is located past the last gate, then insert at the end
    if (pStart > dacGates[lastGate].gatePixEnd){
        pStart = dacGates[lastGate].gatePixEnd;
        setDACGatePixelValues(
                        lastGate+1, pStart, pStart+35, pLevel, true, false);
        return;
        }

    //if the new gate's location is encompassed by an existing gate, shift
    //all later gates down and insert the new gate, adjusting the width of the
    //old gate to give space for the new gate

    int i;
    for (i = 0; i < numberOfDACGates; i++)
        if (dacGates[i].getActive())
            if (pStart >= dacGates[i].gatePixStart
                                           && pStart <= dacGates[i].gatePixEnd)
                break;

    //the end of the new gate will equal the start of the following gate
    //if there is no following gate, then the end will be the end of old gate
    int pEnd;

    if (i < lastGate)
        pEnd = dacGates[i+1].gatePixStart;
    else
        pEnd = dacGates[i].gatePixEnd;

    shiftDACGatesUp(i+1); //shift gates to make room

    //adjust the end of the old gate to match the start of the new gate
    setDACPixEnd(i, pStart, false);

    //set up the new gate
    setDACGatePixelValues(i+1, pStart, pEnd, pLevel, true, false);

}//end of Channel::insertDACGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::deleteDACGate
//
// Deletes the DAC gate specified by pGate.
//

public void deleteDACGate(int pGate)
{

    if (pGate < 0 || pGate >= numberOfDACGates) return;

    int lastGate = getActiveDACGateCount() - 1; //need this in a second

    //shift all gates above the one being deleted down one slot
    shiftDACGatesDown(pGate+1);

    //disable the old last gate slot - that one has been moved down one slot
    setDACActive(lastGate, false, false);

    //set the end of the gate before the deleted one to the start of the gate
    //which was after the deleted one to avoid diagonal lines
    //don't do this if the gates involved are at the ends of the array

    if (pGate > 0 && pGate < getActiveDACGateCount())
        setDACPixEnd(pGate - 1, dacGates[pGate].gatePixStart, false);

}//end of Channel::deleteDACGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::deleteAllDACGates
//
// Deletes all the DAC gates by setting their active states to false.
//

public void deleteAllDACGates()
{

    for (int i = 0; i < numberOfDACGates; i++) setDACActive(i, false, false);

}//end of Channel::deleteAllDACGates
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::shiftDACGatesDown
//
// Shifts all gates beginning with index pStart down one slot.
//

public void shiftDACGatesDown(int pStart)
{

    int newFirstGate = pStart - 1;
    if (newFirstGate < 0) return; //protect against shifting out of bounds

    int stop = getActiveDACGateCount() - 1;

    //copy gates to shift them
    for (int i = newFirstGate; i < stop; i++){
        setDACPixStart(i, dacGates[i+1].gatePixStart, false);
        setDACPixEnd(i, dacGates[i+1].gatePixEnd, false);
        setDACPixLevel(i, dacGates[i+1].gatePixLevel, false);
        setDACActive(i, dacGates[i+1].getActive(), false);
        dacGates[i].setSelected(dacGates[i+1].getSelected());
    }

}//end of Channel::shiftDACGatesDown
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::shiftDACGatesUp
//
// Shifts all gates beginning with index pStart up one slot.
//

public void shiftDACGatesUp(int pStart)
{

    int newLastGate = getActiveDACGateCount();
    if (newLastGate >= numberOfDACGates) return; //protect against full array

    //copy gates to shift them
    for (int i = newLastGate; i > pStart; i--){
        setDACPixStart(i, dacGates[i-1].gatePixStart, false);
        setDACPixEnd(i, dacGates[i-1].gatePixEnd, false);
        setDACPixLevel(i, dacGates[i-1].gatePixLevel, false);
        setDACActive(i, dacGates[i-1].getActive(), false);
        dacGates[i].setSelected(dacGates[i-1].getSelected());
    }

}//end of Channel::shiftDACGatesUp
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDACGatePixelValues
//
// Sets the pixel location values and active flag of the specified DAC gate.
//

public void setDACGatePixelValues(int pGate, int pStart, int pEnd,
                                 int pLevel, boolean pActive, boolean pSelected)
{

    setDACPixStart(pGate, pStart, false);
    setDACPixEnd(pGate, pEnd, false);
    setDACPixLevel(pGate, pLevel, false);
    setDACActive(pGate, pActive, false);

    dacGates[pGate].setSelected(pSelected);

}//end of Channel::setDACGatePixelValues
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDACPixStart
//
// Sets the DAC gate start pixel position of pGate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setDACPixStart(
                                   int pGate, int pStart, boolean pForceUpdate)
{

    if (pStart != dacGates[pGate].gatePixStart) pForceUpdate = true;

    if (pForceUpdate) dacGates[pGate].gatePixStart = pStart;

}//end of Channel::setDACPixStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDACPixEnd
//
// Sets the DAC gate end pixel position of pGate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setDACPixEnd(
                                    int pGate, int pEnd, boolean pForceUpdate)
{

    if (pEnd != dacGates[pGate].gatePixEnd) pForceUpdate = true;

    if (pForceUpdate) dacGates[pGate].gatePixEnd = pEnd;

}//end of Channel::setDACPixEnd
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDACPixLevel
//
// Sets the gate level pixel position of pGate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setDACPixLevel(int pGate, int pLevel, boolean pForceUpdate)
{

    if (pLevel != dacGates[pGate].gatePixLevel) pForceUpdate = true;

    if (pForceUpdate) dacGates[pGate].gatePixLevel = pLevel;

}//end of Channel::setDACPixLevel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getNumberOfGates
//
// Returns the number of gates.
//

public int getNumberOfGates()
{

    return numberOfGates;

}//end of Channel::getNumberOfGates
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGate
//
// Returns a reference to the specified gate.
//

public Gate getGate(int pGate)
{

    return gates[pGate];

}//end of Channel::getGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getNewData
//
// Calls the getNewData function for the specified gate.  See the gate
// class for more info.
//
// Returns true if the channel On and Not Masked.  Returns false otherwise so
// so its data won't be used.
//

public boolean getNewData(int pGate, HardwareVars hdwVs)
{

    if (channelOn && !channelMasked){
        gates[pGate].getNewData(hdwVs);
        return(true);
    }
    else{
        gates[pGate].getNewData(hdwVs);
        return(false);
    }

}//end of Channel::getNewData
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getTrace
//
// Calls the getTrace function for the specified gate.  See the gate
// class for more info.
//
//

public Trace getTrace(int pGate)
{

    return gates[pGate].getTrace();

}//end of Channel::getTrace
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setFlags1
//
// Sets mask word to set one or more bits in the DSP's flags1 variable.
// To set a particular bit in the flag, the corresponding bit in pSetMask
// should be set to a 1.  Any bit in pSetMask which is a 0 is ignored.
//
// The command is always sent to the DSP regardless of it being a new value as
// it does not require much overhead and is infrequently used.
//
// NOTE: A delay should be inserted between consecutive calls to setFlags1
// as they are actually sent to the remotes by another thread.  The delay
// should be long enough to ensure that the other thread has had time to send
// the first command before the mask is overwritten by the second change.
//
// NOTE: This functionality should be replaced.  The DSP currently stores
// flags from the host in the same word as status flags it updates.  Thus it
// is necessary for the host to send flag masks to alter only specific bits
// to avoid overwriting the flags the DSP is tracking itself.  The DSP should
// keep its flags in a separate word to avoid overlap
// Then, flags here can be handled as they are in the Channel class.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setFlags1(int pSetMask, boolean pForceUpdate)
{

    if (pSetMask != flags1SetMask.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    flags1SetMask.setValue(pSetMask);

}//end of Channel::setFlags1
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendSetFlags1
//
// Sends the flags1SetMask to the remotes to set bits in variable flags1.
// See setFlags1 function for more info.
//
// NOTE: This functionality should be replaced.  The DSP currently stores
// flags from the host in the same word as status flags it updates.  Thus it
// is necessary for the host to send flag masks to alter only specific bits
// to avoid overwriting the flags the DSP is tracking itself.  The DSP should
// keep its flags in a separate word to avoid overlap
// Then, flags here can be handled as they are in the Channel class.
//

public void sendSetFlags1()
{

    if (utBoard != null)
        utBoard.sendSetFlags1(boardChannel, flags1SetMask.applyValue());
    else
        System.out.println("UT Board not assigned to channel " + channelIndex);

}//end of Channel::sendSetFlags1
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::clearFlags1
//
// Sets mask word to clear one or more bits in the DSP's flags1 variable.
// To clear a particular bit in the flag, the corresponding bit in pSetMask
// should be set to a 0.  Any bit in pSetMask which is a 1 is ignored.
//
// NOTE: A delay should be inserted between consecutive calls to clearFlags1
// as they are actually sent to the remotes by another thread.  The delay
// should be long enough to ensure that the other thread has had time to send
// the first command before the mask is overwritten by the second change.
//
// The command is always sent to the DSP regardless of it being a new value as
// it does not require much overhead and is infrequently used.
//
// NOTE: This functionality should be replaced.  The DSP currently stores
// flags from the host in the same word as status flags it updates.  Thus it
// is necessary for the host to send flag masks to alter only specific bits
// to avoid overwriting the flags the DSP is tracking itself.  The DSP should
// keep its flags in a separate word to avoid overlap
// Then, flags here can be handled as they are in the Channel class.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void clearFlags1(int pClearMask, boolean pForceUpdate)
{

    if (pClearMask != flags1ClearMask.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    flags1ClearMask.setValue(pClearMask);

}//end of Channel::clearFlags1
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendClearFlags1
//
// Sends the flags1ClearMask to the remotes to clear bits in variable flags1.
// See clearFlags1 function for more info.
//
// NOTE: This functionality should be replaced.  The DSP currently stores
// flags from the host in the same word as status flags it updates.  Thus it
// is necessary for the host to send flag masks to alter only specific bits
// to avoid overwriting the flags the DSP is tracking itself.  The DSP should
// keep its flags in a separate word to avoid overlap
// Then, flags here can be handled as they are in the Channel class.
//

public void sendClearFlags1()
{

    utBoard.sendClearFlags1(boardChannel, flags1ClearMask.applyValue());

}//end of Channel::sendClearFlags1
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDACEnabled
//
// Sets appropriate mask word to set or clear the DAC_ENABLED bit in the DSP's
// flags1 variable.
//
// NOTE: A delay should be inserted between calls to this function and setFlags1
// and clearFlags1 as they are actually sent to the remotes by another thread.
// The delay should be long enough to ensure that the other thread has had time
// to send the first command.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setDACEnabled(boolean pEnable, boolean pForceUpdate)
{

    if (pEnable != dacEnabled) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    dacEnabled = pEnable;

    if (dacEnabled)
        flags1SetMask.setValue((int)UTBoard.DAC_ENABLED);
    else
        flags1ClearMask.setValue((int)~UTBoard.DAC_ENABLED);

}//end of Channel::setDACEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACEnabled
//
// Returns the dacEnabled flag.
//

public boolean getDACEnabled()
{

    return dacEnabled;

}//end of Channel::getDACEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setAScanFastEnabled
//
// Sets appropriate mask word to set or clear the ASCAN_FAST_ENABLED bit in the
// DSP's flags1 variable.  This allows Ascan data to be retrieved.
//
// NOTE: The fast AScan function in the DSP takes too much time and will
// generally cause datasets to be skipped because it processes the entire AScan
// buffer in one chunk -- effectively lowering the rep rate.  The function
// should ONLY be enabled when using an AScan display for setup.
//
// NOTE: See setAScanSlowEnabled for a version which can be used during
// inspection mode because it processes the AScan buffer a piece at a time.
//
// NOTE: A delay should be inserted between calls to this function and setFlags1
// and clearFlags1 as they are actually sent to the remotes by another thread.
// The delay should be long enough to ensure that the other thread has had time
// to send the first command.
//
// WARNING: the fast AScan and slow AScan options should never be enabled
// at the same time.  They share some of the same variables in the DSP.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setAScanFastEnabled(boolean pEnable,
                                                           boolean pForceUpdate)
{

    if (pEnable != aScanFastEnabled) pForceUpdate = true;

    aScanFastEnabled = pEnable;

    //flag that new data needs to be sent to remotes
    if (pForceUpdate){
        if (aScanFastEnabled){
            flags1SetMask.setValue((int)UTBoard.ASCAN_FAST_ENABLED);
        }
        else{
            flags1ClearMask.setValue((int)~UTBoard.ASCAN_FAST_ENABLED);
        }
    }

}//end of Channel::setAScanFastEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getAScanFastEnabled
//
// Returns the aScanFastEnabled flag.
//

public boolean getAScanFastEnabled()
{

    return aScanFastEnabled;

}//end of Channel::getAScanFastEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setAScanSlowEnabled
//
// Sets appropriate mask word to set or clear the ASCAN_SLOW_ENABLED bit in the
// DSP's flags1 variable.  This allows Ascan data to be retrieved.
//
// NOTE: This function can be enabled during inspection because it processes
// only a small chunk of the AScan buffer during each pulse cycle.
//
// NOTE: See setAScanFastEnabled for a version which might give faster visual
// response but can only be used during calibration as it might cause data
// peaks to be missed occasionally.
//
// NOTE: A delay should be inserted between calls to this function and setFlags1
// and clearFlags1 as they are actually sent to the remotes by another thread.
// The delay should be long enough to ensure that the other thread has had time
// to send the first command.
//
// WARNING: the fast AScan and slow AScan options should never be enabled
// at the same time.  They share some of the same variables in the DSP.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setAScanSlowEnabled(boolean pEnable,
                                                           boolean pForceUpdate)
{

    if (pEnable != aScanSlowEnabled) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    aScanSlowEnabled = pEnable;

        if (aScanSlowEnabled)
            flags1SetMask.setValue((int)UTBoard.ASCAN_SLOW_ENABLED);
        else
            flags1ClearMask.setValue((int)~UTBoard.ASCAN_SLOW_ENABLED);

}//end of Channel::setAScanSlowEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getAScanSlowEnabled
//
// Returns the aScanSlowEnabled flag.
//

public boolean getAScanSlowEnabled()
{

    return aScanSlowEnabled;

}//end of Channel::getAScanSlowEnabled
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setAScanFreeRun
//
// Sets appropriate mask word to set or clear the ASCAN_FREE_RUN bit in the
// DSP's flags1 variable.  This flag controls whether AScan data is saved
// immediately upon a request or if it is saved when an AScan trigger gate is
// exceeded.  The latter allows the AScan display to be synchronized with a
// signal peak in the gate.
//
// NOTE: A delay should be inserted between calls to this function and setFlags1
// and clearFlags1 as they are actually sent to the remotes by another thread.
// The delay should be long enough to ensure that the other thread has had time
// to send the first command.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setAScanFreeRun(boolean pEnable, boolean pForceUpdate)
{

    if (pEnable != aScanFreeRun) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    aScanFreeRun = pEnable;

    if (aScanFreeRun)
        flags1SetMask.setValue((int)UTBoard.ASCAN_FREE_RUN);
    else
        flags1ClearMask.setValue((int)~UTBoard.ASCAN_FREE_RUN);

}//end of Channel::setAScanFreeRun
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getAScanFreeRun
//
// Returns the aScanFreeRun flag.
//

public boolean getAScanFreeRun()
{

    return aScanFreeRun;

}//end of Channel::getAScanFreeRun
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::calculateGateSpan
//
// Records the position in sample counts of the leading edge of the first
// gate (first in time) and the trailing edge of the last gate (last in time).
// The span between these two values represents the number of samples which
// must be recorded by the FPGA in order to allow processing of data in those
// gates.  The leading point is stored in firstGateEdgePos while the trailing
// point is stored in lastGateEdgePos.
//
// If interface tracking is off, the calculation is made using the gate
// start positions and widths as set.
//
// If interface tracking is on, the gate positions are based on the position
// where the signal exceeds the interface gate.  This is often not known, so
// the worst possible case is computed.  If the gate position is negative
// (gate is located before the interface crossing), then that gate's position
// is calculated from the leading edge of the interface gate.  This is the
// worst case as that is the earliest time the signal could cross the interface
// gate.  If the gate position is positive (gate is located after the
// interface crossing), the gate's position is calculated from the trailing
// edge of the interface gate as that is the worst case.
//
// NOTE: Call this function any time a gate position or width is modified.
//

void calculateGateSpan()
{

    //calculate the gate positions as absolutes - see notes at top of function
    //or as worst case distances from the interface gate

    if (!interfaceTracking){

        //find the earliest gate

        int firstGate = 0;
        for (int i = 0; i < numberOfGates; i++)
            if (gates[i].gateStart.getValue()
                                        < gates[firstGate].gateStart.getValue())
                firstGate = i;

        //find the latest gate

        int lastGate = 0;
        for (int i = 0; i < numberOfGates; i++)
            if ((gates[i].gateStart.getValue() + gates[i].gateWidth.getValue())
                            > (gates[lastGate].gateStart.getValue()
                                       + gates[lastGate].gateWidth.getValue()))
                lastGate = i;

        //absolute positioning

        //calculate the position (in samples) of the leading edge of first gate
        firstGateEdgePos =
                 (int)(gates[firstGate].gateStart.getValue() / uSPerDataPoint);

        //calculate the position in number of samples of trailing edge of
        //last gate
        lastGateEdgePos = (int)((gates[lastGate].gateStart.getValue()
                    + gates[lastGate].gateWidth.getValue())  / uSPerDataPoint);

    }// if (!interfaceTracking)
    else{

        int interfaceGateLead =
                        (int)(gates[0].gateStart.getValue() / uSPerDataPoint);
        int interfaceGateTrail = (int)((gates[0].gateStart.getValue() +
                             gates[0].gateWidth.getValue()) / uSPerDataPoint);

        //if there is only one gate, it must be the interface gate so use its
        //position and return
        if (numberOfGates == 1){
            firstGateEdgePos = interfaceGateLead;
            lastGateEdgePos = interfaceGateTrail;
            return;
            }

        //find the earliest gate, not including the interface gate
        //the interface gate always uses absolute positioning whereas the other
        //gates will be relative to the interface crossing if tracking is on

        int firstGate = 1;
        for (int i = 1; i < numberOfGates; i++)
            if (gates[i].gateStart.getValue() <
                                        gates[firstGate].gateStart.getValue())
                firstGate = i;

        //find the latest gate, not including the interface gate (see note above)

        int lastGate = 1;
        for (int i = 1; i < numberOfGates; i++)
            if ((gates[i].gateStart.getValue() + gates[i].gateWidth.getValue())
                           > (gates[lastGate].gateStart.getValue()
                                        + gates[lastGate].gateWidth.getValue()))
                lastGate = i;

        //positioning relative to interface gate
        //interface gate is always gate 0

        //calculate the position (in samples) of the leading edge of first gate
        //relative to the leading edge of interface gate (worst case)
        firstGateEdgePos = (int)((gates[0].gateStart.getValue()
                + gates[firstGate].gateStart.getValue()) / uSPerDataPoint);

        //if the interface gate is before all other gates, use its position
        if (interfaceGateLead < firstGateEdgePos)
            firstGateEdgePos = interfaceGateLead;

        //calculate the position in number of samples of trailing edge of last
        //gate relative to the trailing edge of interface gate (worst case)
        lastGateEdgePos = (int)(
                (gates[0].gateStart.getValue() + gates[0].gateWidth.getValue() +
                gates[lastGate].gateStart.getValue()
                + gates[lastGate].gateWidth.getValue()) / uSPerDataPoint);

        //if the interface gate is after all other gates, use its position
        if (interfaceGateTrail > lastGateEdgePos)
            lastGateEdgePos = interfaceGateTrail;

    }// else of if (!interfaceTracking)

}//end of Channel::calculateGateSpan
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDelay
//
// Sets the delay for the AScan.  This is the amount of time skipped between
// the initial pulse and the start of the data collection.
//
// Note that the delay sent to the FPGA must not delay past the start of the
// earliest gate. Data still needs to be collected for all gates even if the
// user is not viewing that portion of the data.
//
// Thus the delay for the AScan display is comprised of two parts - the delay
// already introduced by the FPGA skipping a specified number of samples after
// the initial pulse and the DSP skipping a specified number of samples when
// it transmits the AScan data set.  This is because the FPGA must start
// recording data at the start of the earliest gate while the AScan may be
// displaying data from a point later than that.
//
// The delay in the FPGA before samples are stored is hardwareDelay while
// the delay in the DSP software for the positioning of the start of the AScan
// data is software delay.  The AScan delay equals hardware delay plus
// software delay.
//
// The hardwareDelay and softwareDelay values represent one sample period for
// each count.  For a 66.666 MHz sample rate, this equates to 15nS per count.
//
// NOTE: This function must be called anytime a gate's position is changed so
// that the sampling start can be adjusted to include the entire gate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setDelay(double pDelay, boolean pForceUpdate)
{

    int oldHardwareDelay = hardwareDelay;
    int oldSoftwareDelay = softwareDelay;

    aScanDelay = pDelay;

    //calculate the number of samples to skip based on the delay in microseconds
    hardwareDelay = (int)(aScanDelay / uSPerDataPoint);
    int totalDelayCount = hardwareDelay;

    //the FPGA sample delay CANNOT be later than the delay to the earliest gate
    //because the gate cannot be processed if samples aren't taken for it
    //if the earliest gate is sooner than the delay, then override the FPGA
    //delay - the remaining delay for an AScan will be accounted for by setting
    //a the aScanDelay in the DSP

    if (firstGateEdgePos < hardwareDelay) hardwareDelay = firstGateEdgePos;

    if (hardwareDelay != oldHardwareDelay) pForceUpdate = true;

    if (utBoard != null && pForceUpdate)
        utBoard.sendHardwareDelay(boardChannel, hardwareDelay);

    //calculate and set the remaining delay left over required to positon the
    //AScan correctly after taking into account the FPGA sample delay

    softwareDelay = totalDelayCount - hardwareDelay;

    if (softwareDelay != oldSoftwareDelay) pForceUpdate = true;

    if (utBoard != null && pForceUpdate)
        utBoard.sendSoftwareDelay(boardChannel, softwareDelay, hardwareDelay);

}//end of Channel::setDelay
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDelay
//
// Returns the AScan signal delay for the DSP.
//

public double getDelay()
{

    return aScanDelay;

}//end of Channel::getDelay
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDelayInSamplePeriods
//
// Returns the delay time between the initial pulse and when the left edge
// of the AScan should start.  This is essentially the same value as aScanDelay,
// but that value is in uSec whereas the value returned by this function is the
// actual number number of samples which were skipped, each sample representing
// 15nS if using a 66.6666MHz sample clock.
//
// This value may be a bit more accurate than converting aScanDelay to sample
// periods.
//
// The returned value represents one sample period for each count.  For a
// 66.666 MHz sample rate, this equates to 15nS per count.
//
// The value is obtained by summing hardwareDelay and softwareDelay.  The
// hardwareDelay is the value fed into the FPGA to set the number of samples
// it ignores before it starts recording.  This sample start point begins at
// the left edge of the aScan OR EARLIER if a gate is set before the aScan
// starts (sampling must occur at the start of the earliest gate in order for
// the gate to be useful).  The software delay is used to delay the sample
// window used for displaying the aScan after the start of sampling.
//
// If the location of a gate or signal is known by the number of sample periods
// after the initial pulse, it can be positioned properly on the aScan display
// by subtracting the value returned returned by this function and scaling
// from sample periods to pixels.
//
// More often, the location is known from the start of the DSP sample buffer.
// In this case, only subtract the softwareDelay from the location -- use
// getSoftwareDelay instead fo this function.
//
// Example:
//
// int peakPixelLoc =
//        (int) ((flightTime * pChannel.nSPerDataPoint) / pChannel.nSPerPixel);
//

public int getDelayInSamplePeriods()
{

    return hardwareDelay + softwareDelay;

}//end of Channel::getDelayInSamplePeriods
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getSoftwareDelay
//
// Returns the delay time between the initial pulse and the start of the DSP
// sample buffer.  Each count represents one sample (or 15nS) if using a
// 66.6666MHz sample clock.
//
// See notes for getDelayInSamplePeriods for more info.
//

public int getSoftwareDelay()
{

    return softwareDelay;

}//end of Channel::getSoftwareDelay
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setRejectLevel
//
// Sets the reject level which is a percentage of the screen height below
// which the signal is attenuated nearly to zero.
//

public void setRejectLevel(int pRejectLevel, boolean pForceUpdate)
{

    if (pRejectLevel != rejectLevel) pForceUpdate = true;

    rejectLevel = pRejectLevel;

    if (utBoard != null && pForceUpdate)
        utBoard.setRejectLevel(boardChannel, pRejectLevel);

}//end of Channel::setRejectLevel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getRejectLevel
//
// Returns the reject level which is a percentage.
//

public int getRejectLevel()
{

    return rejectLevel;

}//end of Channel::getRejectLevel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setAScanSmoothing
//
// Sets the amount of smoothing (averaging) for the aScan display.
//
// The value is also sent to the remotes to specify the amount of averaging
// to use for the data samples.  The AScan data is not averaged by the remotes,
// only the peak data.  Averaging (smoothing) of the AScan data is done in
// the host.  Thus, AScan data can be averaged, peak data can be averaged, or
// both together.
//

public void setAScanSmoothing(int pAScanSmoothing, boolean pForceUpdate)
{

    if (pAScanSmoothing != aScanSmoothing.getValue()) pForceUpdate = true;

    if (pForceUpdate){

        aScanSmoothing.setValue(pAScanSmoothing);

        //update all gates with new averaging value
        for (int i = 0; i < numberOfGates; i++)
            gates[i].setAScanSmoothing(pAScanSmoothing);

    }

}//end of Channel::setAScanSmoothing
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getAScanSmoothing
//
// Returns the amount of smoothing (averaging) for the aScan display.
//

public int getAScanSmoothing()
{

    return aScanSmoothing.getValue();

}//end of Channel::getAScanSmoothing
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendAScanSmoothing
//
// Sends the AScanSmoothing value to the remotes.
//

public void sendAScanSmoothing()
{

    if (utBoard != null)
        utBoard.setAScanSmoothing(boardChannel, aScanSmoothing.applyValue());

}//end of Channel::sendAScanSmoothing
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDCOffset
//
// Sets the DC offset.
//

public void setDCOffset(int pDCOffset, boolean pForceUpdate)
{

    if (pDCOffset != dcOffset) pForceUpdate = true;

    dcOffset = pDCOffset;

    //divide the input value by 4.857 mv/Count
    //AD converter span is 1.2 V, 256 counts
    //this will give a value of zero offset until input value is +/-5, then it
    //will equal 1 - the value will only change every 5 or so counts because the
    //AD resolution is 4+ mV and the input value is mV

    if (utBoard != null && pForceUpdate)
        utBoard.sendDCOffset(boardChannel, (int)(dcOffset / 4.6875));

}//end of Channel::setDCOffset
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDCOffset
//
// Returns the DC offset value.
//

public int getDCOffset()
{

    return dcOffset;

}//end of Channel::getDCOffset
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setMode
//
// Sets the signal mode setting: rectification style or channel off.
//
// Also sets the channelOn switch true if mode is not CHANNEL_OFF, false if
// it is.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setMode(int pMode, boolean pForceUpdate)
{

    //if the channel is disabled in the configuration file, mode is always off
    if (disabled) pMode = UTBoard.CHANNEL_OFF;

    if (pMode != mode.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    mode.setValue(pMode);

    boolean lChannelOn =
                    (mode.getValue() != UTBoard.CHANNEL_OFF) ? true : false;

    //update the tranducer settings as the on/off status is set that way
    setTransducer(lChannelOn, pulseBank, pulseChannel, pForceUpdate);

}//end of Channel::setMode
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendMode
//
// Sends the signal mode to the remote.
//

public void sendMode()
{

    if (utBoard != null) utBoard.sendMode(boardChannel, mode.applyValue());

}//end of Channel::sendMode
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getMode
//
// Returns the signal mode setting: rectification style or off.
//

public int getMode()
{

    return mode.getValue();

}//end of Channel::getMode
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setRange
//
// Sets the range for the AScan. This is the amount of signal displayed in one
// Ascan.
//
// Note that the data collected amount must be enough to stretch from the
// start of the earliest gate to the end of the latest gate, even if the range
// being displayed on the scope is smaller than this.  Even if the user is
// looking at a smaller area, data must still be collected so that it can be
// processed for each gate. If the displayed AScan range is larger than the
// earliest and latest gates, the range must be set to encompass this as well.
//
// The number of samples stored by the FPGA to ensure coverage from the first
// point of interest (left edge of AScan or earliest gate) to the last point
// of interest (right edge of AScan or latest gate) is stored in hardwareRange.
// This value is the number of samples to be stored on each transducer firing.
//
// The DSP always returns 400 samples for an AScan dataset.  The aScanScale
// value tells the DSP how many samples to compress for each sample placed in
// the AScan buffer.  The DSP stores the peaks from the compressed samples
// and transfers those peaks to the AScan buffer.
//
// When displaying the AScan, the host takes into account the compression
// performed by the DSP (which is always an integer multiple for simplicity)
// and factors in a further fractional scaling to arrive at the desired
// range scale for the AScan.  The DSP's software range is always set to the
// smallest possible integer which will compress the desired number of samples
// (the range) into the 400 sample AScan buffer.  This program will then
// further shrink or stretch the 400 samples as needed to display the desired
// range.
//
// It is desirable to have more compression done by the DSP than is needed
// rather than less.  The AScan buffer can be stretched or squeezed as
// necessary if it is full of data - any extra simply won't be displayed.  For
// this to work, the range of samples to be viewed must be present in the
// AScan buffer even if they are over compressed.
//
// NOTE: On startup, always call setDelay before calling setRange as this
//    function uses data calculated in setDelay.  Thereafter each function only
//    needs to be called if its respective data is modified.
//
// NOTE: This function must be called anytime a gate's position or width is
// changed so that the sample size can be adjusted to include the entire gate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setRange(double pRange, boolean pForceUpdate)
{

    int oldHardwareRange = hardwareRange.getValue();
    int oldAScanScale = aScanScale.getValue();

    aScanRange = pRange;

    //calculate the position of the left edge of the AScan in sample counts
    int leftEdgeAScan = hardwareDelay + softwareDelay;

    //calculate the position of the right edge of the AScan in sample counts

    int rightEdgeAScan = leftEdgeAScan + (int)(aScanRange / uSPerDataPoint);

    int start, stop;

    //determine the earliest event for which samples are required - the left
    //edge of the AScan or the leading edge of the first gate, which ever is
    //first

    if (leftEdgeAScan <= firstGateEdgePos) start = leftEdgeAScan;
    else start = firstGateEdgePos;

    //determine the latest event for which samples are required - the right edge
    //of the AScan or the trailing edge of the last gate, which ever is last

    if (rightEdgeAScan >= lastGateEdgePos) stop = rightEdgeAScan;
    else stop = lastGateEdgePos;

    //calculate the number of required samples to cover the range specified
    hardwareRange.setValue(stop - start);

    //force sample size to be even - see notes at top of
    //UTboard.setHardwareRange for more info
    if (hardwareRange.getValue() % 2 != 0)
        hardwareRange.setValue(hardwareRange.getValue() + 1);

    //calculate the compression needed to fit at least the desired number of
    //AScan samples into the 400 sample AScan buffer - the scale factor is
    //rounded up rather than down to make sure the desired range is collected

    aScanScale.setValue(
                (rightEdgeAScan - leftEdgeAScan) / UTBoard.ASCAN_SAMPLE_SIZE);

    //if the range is not a perfect integer, round it up
    if (((rightEdgeAScan - leftEdgeAScan) % UTBoard.ASCAN_SAMPLE_SIZE) != 0)
        aScanScale.setValue(aScanScale.getValue() + 1);

}//end of Channel::setRange
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendRange
//
// Sends the necessary information regarding the range to the remotes.  Note
// that aScanRange itself is not sent, so it is not a threadsafe variable.
//
// The values which are calculated and sent are threadsafe.
//

private void sendRange()
{

    if (utBoard != null){

        //lock in the synced value since it is used twice here
        int lHardwareRange = hardwareRange.applyValue();

        utBoard.sendHardwareRange(boardChannel, lHardwareRange);

        //tell the DSP cores how big the sample set is
        utBoard.sendDSPSampleSize(boardChannel, lHardwareRange);

        utBoard.sendAScanScale(boardChannel, aScanScale.applyValue());

    }

}//end of Channel::sendRange
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getRange
//
// Returns the signal range.  The range is how much of the signal is displayed.
//

public double getRange()
{

    return aScanRange;

}//end of Channel::getRange
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setSampleBufferStart
//
// Sets the registers in the FPGA which hold the starting address in the
// DSP for the sample buffer.
//

public void setSampleBufferStart()
{

    if (utBoard != null)
        utBoard.sendSampleBufferStart(boardChannel,
                                            UTBoard.AD_RAW_DATA_BUFFER_ADDRESS);

}//end of Channel::setSampleBufferStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setSoftwareGain
//
// Sets the software gain for the DSP.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setSoftwareGain(double pSoftwareGain, boolean pForceUpdate)
{

    if (pSoftwareGain != softwareGain.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    softwareGain.setValue(pSoftwareGain);

    for (int i = 0; i < numberOfDACGates; i++)
        dacGates[i].setSoftwareGain(pSoftwareGain, pForceUpdate);

}//end of Channel::setSoftwareGain
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendSoftwareGain
//
// Sends the software gain to the DSP.
//

public void sendSoftwareGain()
{

    if (utBoard != null)
        utBoard.sendSoftwareGain(boardChannel, softwareGain.applyValue());

}//end of Channel::sendSoftwareGain
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getSoftwareGain
//
// Returns the software gain for the DSP.
//

public double getSoftwareGain()
{

    return softwareGain.getValue();

}//end of Channel::getSoftwareGain
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setHardwareGain
//
// Sets the hardware gains for the DSP.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setHardwareGain(int pHardwareGain1, int pHardwareGain2,
                                                           boolean pForceUpdate)
{

    if ((pHardwareGain1 != hardwareGain1.getValue()) ||
                                 (pHardwareGain2 != hardwareGain2.getValue() ))
        pForceUpdate = true;

    if (pForceUpdate){
        hardwareGain1.setValue(pHardwareGain1);
        hardwareGain2.setValue(pHardwareGain2);
    }

}//end of Channel::setHardwareGain
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendHardwareGain
//
// Sends the hardware gain to the DSP.
//

public void sendHardwareGain()
{

    if (utBoard != null) utBoard.sendHardwareGain(
        boardChannel, hardwareGain1.applyValue(), hardwareGain2.applyValue());

}//end of Channel::sendHardwareGain
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getHardwareGain1
//
// Returns the hardware gain 1 for the DSP.
//

public int getHardwareGain1()
{

    return hardwareGain1.getValue();

}//end of Channel::getHardwareGain1
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getHardwareGain2
//
// Returns the hardware gain 2 for the DSP.
//

public int getHardwareGain2()
{

    return hardwareGain2.getValue();

}//end of Channel::getHardwareGain2
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setInterfaceTracking
//
// Sets the interface tracking on/off flag for pChannel in the DSPs.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setInterfaceTracking(boolean pState, boolean pForceUpdate)
{

    if (pState != interfaceTracking) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    interfaceTracking = pState;

    //switch gate start positions to the appropriate values for the current mode
    for (int i = 0; i < numberOfGates; i++)
        setGateStart(i, interfaceTracking ?
           gates[i].gateStartTrackingOn : gates[i].gateStartTrackingOff, false);

    //determine the span from the earliest gate edge to the latest (in time)
    calculateGateSpan();

    for (int i = 0; i < numberOfGates; i++)
        gates[i].setInterfaceTracking(pState);

    for (int i = 0; i < numberOfDACGates; i++)
        dacGates[i].setInterfaceTracking(pState);

}//end of Channel::setInterfaceTracking
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getInterfaceTracking
//
// Returns the getInterfaceTracking flag.
//

public boolean getInterfaceTracking()
{

    return interfaceTracking;

}//end of Channel::getInterfaceTracking
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setAScanTrigger
//
// Sets the AScan trigger on/off flag for pChannel in the DSPs.  If set, the
// gate will trigger an AScan save when the signal exceeds the gate level.
// This allows the display to be synchronized with signals of interest so they
// are displayed clearly rather than as brief flashes.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setAScanTrigger(int pGate, boolean pState, boolean pForceUpdate)
{

    if (pState != gates[pGate].getIsAScanTriggerGate()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    //store the AScan trigger gate setting for the specified gate
    gates[pGate].setAScanTriggerGate(pState);

    //Look at all gates' AScan trigger settings -- if any gate(s) are set as
    //trigger gates, then the DSP's flags are set to enable the triggered AScan
    //mode.  If no gate is a trigger gate, then the DSP's AScan mode is set to
    //free run.

    boolean triggerGateFound = false;
    for(int i = 0; i < numberOfGates; i++){
        if(gates[i].getIsAScanTriggerGate()){
            triggerGateFound = true;
            setAScanFreeRun(false, false);
            break;
        }
    }//for(int i = 0; i < numberOfGates; i++)

    //no gate was a trigger gate so set DSP's AScan mode to free-run
    if (!triggerGateFound) setAScanFreeRun(true, false);

}//end of Channel::setAScanTrigger
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setTransducer
//
// Sets the transducer's pulse on/off state, pulse bank, and pulse channel
// and transmits these values to the DSP.
//
// Note that the transucer can be on one channel while another is fired for
// pitch/catch configurations.
//
// pBank is the pulser bank to which the transducer is to be assigned.
// pPulsedChannel is the pulser fired for the specified channel.
// pOnOff is 0 if the channel is to be pulsed and 1 if not.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

void setTransducer(boolean pChannelOn, int pPulseBank, int pPulseChannel,
                                                        boolean pForceUpdate)
{

    if (pChannelOn != channelOn) pForceUpdate = true;
    if (pPulseBank != pulseBank) pForceUpdate = true;
    if (pPulseChannel != pulseChannel) pForceUpdate = true;

    channelOn =
            pChannelOn; pulseBank = pPulseBank; pulseChannel = pPulseChannel;

    channelOn = (mode.getValue() != UTBoard.CHANNEL_OFF) ? true : false;

    if (utBoard != null && pForceUpdate)
        utBoard.sendTransducer(boardChannel,
                 (byte)(channelOn ? 1:0), (byte)pulseBank, (byte)pulseChannel);

}//end of Channel::setTransducer
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::linkTraces
//
// This function is called by traces to link their buffers to specific hardware
// channels/gates and give a link back to variables in the Trace object.
//

public void linkTraces(int pChartGroup, int pChart, int pTrace, int[] pDBuffer,
   int[] pDBuffer2, int[] pFBuffer, Threshold[] pThresholds, int pPlotStyle,
   Trace pTracePtr)
{

    for (int i = 0; i < numberOfGates; i++)
        gates[i].linkTraces(pChartGroup, pChart, pTrace, pDBuffer, pDBuffer2,
                         pFBuffer, pThresholds, pPlotStyle, pTracePtr);

}//end of Channel::linkTraces
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::configure
//
// Loads configuration settings from the configuration.ini file.
// The various child objects are then created as specified by the config data.
//

private void configure(IniFile pConfigFile)
{

    //load the nS per data point value and compute the uS per data point as well

    nSPerDataPoint =
      pConfigFile.readDouble("Hardware", "nS per Data Point", 15.0);
    uSPerDataPoint = nSPerDataPoint / 1000;

    String whichChannel = "Channel " + (channelIndex + 1);

    title =
          pConfigFile.readString(
                         whichChannel, "Title", "Channel " + (channelIndex+1));

    shortTitle = pConfigFile.readString(
                        whichChannel, "Short Title", "Ch " + (channelIndex+1));

    detail = pConfigFile.readString(whichChannel, "Detail", title);

    chassisAddr = pConfigFile.readInt(whichChannel, "Chassis", 1);

    slotAddr = pConfigFile.readInt(whichChannel, "Slot", 1);

    boardChannel = pConfigFile.readInt(whichChannel, "Board Channel", 1) - 1;

    pulseChannel = pConfigFile.readInt(whichChannel, "Pulse Channel", 1) - 1;

    pulseBank = pConfigFile.readInt(whichChannel, "Pulse Bank", 1) - 1;

    disabled = pConfigFile.readBoolean(whichChannel, "Disabled", false);

    type = pConfigFile.readString(whichChannel, "Type", "Other");

    numberOfGates = pConfigFile.readInt(whichChannel, "Number Of Gates", 3);

    numberOfDACGates =
            pConfigFile.readInt(whichChannel, "Number Of DAC Gates", 10);

    //read the configuration file and create/setup the gates
    configureGates();

    //read the configuration file and create/setup the DAC gates
    configureDACGates();

}//end of Channel::configure
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::configureGates
//
// Loads configuration settings from the configuration.ini file relating to
// the gates and creates/sets them up.
//

private void configureGates()
{

    //create an array of gates per the config file setting
    if (numberOfGates > 0){

        //protect against too many
        if (numberOfGates > 20) numberOfGates = 20;

        gates = new Gate[numberOfGates];

        for (int i = 0; i < numberOfGates; i++)
            gates[i] = new Gate(configFile, channelIndex, i, syncedVarMgr);

    }//if (numberOfGates > 0)

}//end of Channel::configureGates
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::configureDACGates
//
// Loads configuration settings from the configuration.ini file relating to
// the DAC gates and creates/sets them up.
//

private void configureDACGates()
{

    //create an array of gates per the config file setting
    if (numberOfDACGates > 0){

        //protect against too many
        if (numberOfDACGates > 20) numberOfDACGates = 20;

        dacGates = new DACGate[numberOfDACGates];

        for (int i = 0; i < numberOfDACGates; i++)
            dacGates[i] = new DACGate(configFile, channelIndex, i,
                                                    syncedVarMgr, scopeMax);

    }//if (numberOfDACGates > 0)

}//end of Channel::configureDACGates
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::requestAScan
//
// Requests an AScan dataset for this channel from the appropriate remote
// device.
//

public void requestAScan()
{

    //boardChannel specifies which analog channel on the UT board is associated
    //with this channel object - it is read from the configuration file

    if (utBoard != null)
        utBoard.requestAScan(boardChannel, hardwareDelay);

}//end of Channel::requestAScan
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getAScan
//
// Retrieves an AScan dataset for the specified channel.
//

public AScan getAScan()
{

    //boardChannel specifies which analog channel on the UT board is associated
    //with this channel object - it is read from the configuration file

    if (utBoard != null)
        return utBoard.getAScan();
    else
        return null;

}//end of Channel::getAScan
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::requestPeakData
//
// Sends a request to the remote device for a peak data packet for the
// specified channel.
//
// The channel number sent refers to one of the four analog channels on the
// board.  The UTBoard object has a connection between the board channel
// and the logical channel so it can store the peak from any board channel
// with its associated logical channel.
//

public void requestPeakData()
{

    if (utBoard != null) utBoard.requestPeakData(boardChannel);

}//end of Channel::requestPeakData
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateStart
//
// Sets the gate start position of pGate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateStart(int pGate, double pStart, boolean pForceUpdate)
{

    if (pStart != gates[pGate].gateStart.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].gateStart.setValue(pStart);

    //store the variable as appropriate for the interface tracking mode - this
    //allows switching back and forth between modes
    if (interfaceTracking)
        gates[pGate].gateStartTrackingOn = pStart;
    else
        gates[pGate].gateStartTrackingOff = pStart;

    //determine the span from the earliest gate edge to the latest (in time)
    calculateGateSpan();

}//end of Channel::setGateStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateStart
//
// Returns the gate start position of pGate.
//

public double getGateStart(int pGate)
{

    return gates[pGate].gateStart.getValue();

}//end of Channel::getGateStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateStartTrackingOn
//
// Sets the gate start position for pGate stored for use with
// Interface Tracking.
//
// No force update or call to calculateGateSpan is needed here because the
// value is only stored and not applied.
//

public void setGateStartTrackingOn(int pGate, double pStart)
{

    gates[pGate].gateStartTrackingOn = pStart;

}//end of Channel::setGateStartTrackingOn
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateStartTrackingOn
//
// Returns the gate start position for pGate stored for use with
// Interface Tracking.
//

public double getGateStartTrackingOn(int pGate)
{

    return gates[pGate].gateStartTrackingOn;

}//end of Channel::getGateStartTrackingOn
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateStartTrackingOff
//
// Sets the gate start position for pGate stored for use without
// Interface Tracking.
//
// No force update or call to calculateGateSpan is needed here because the
// value is only stored and not applied.
//

public void setGateStartTrackingOff(int pGate, double pStart)
{

    gates[pGate].gateStartTrackingOff = pStart;

}//end of Channel::setGateStartTrackingOff
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateStartTrackingOff
//
// Returns the gate start position for pGate stored for use with
// Interface Tracking.
//

public double getGateStartTrackingOff(int pGate)
{

    return gates[pGate].gateStartTrackingOff;

}//end of Channel::getGateStartTrackingOff
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setPreviousGateStart
//
// Stores pStart in the previousGateStart variable for gate pGate.
//

public void setPreviousGateStart(int pGate, double pStart)
{

    gates[pGate].previousGateStart = pStart;

}//end of Channel::setPreviousGateStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getPreviousGateStart
//
// Returns the previousGateStart variable for gate pGate.
//

public double getPreviousGateStart(int pGate)
{

    return gates[pGate].previousGateStart;

}//end of Channel::getPreviousGateStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateWidth
//
// Sets the gate width of pGate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateWidth(int pGate, double pWidth, boolean pForceUpdate)
{

    if (pWidth != gates[pGate].gateWidth.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].gateWidth.setValue(pWidth);

    //determine the span from the earliest gate edge to the latest (in time)
    calculateGateSpan();

}//end of Channel::setGateWidth
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateWidth
//
// Gets the gate width of pGate.
//

public double getGateWidth(int pGate)
{

    return gates[pGate].gateWidth.getValue();

}//end of Channel::getGateWidth
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setPreviousGateWidth
//
// Stores pStart in the previousGateWidth variable for gate pGate.
//

public void setPreviousGateWidth(int pGate, double pWidth)
{

    gates[pGate].previousGateWidth = pWidth;

}//end of Channel::setPreviousGateWidth
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getPreviousGateWidth
//
// Returns the previousGateWidth variable for gate pGate.
//

public double getPreviousGateWidth(int pGate)
{

    return gates[pGate].previousGateWidth;

}//end of Channel::getPreviousGateWidth
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateFlags
//
// Gets the gate flags.
//

public int getGateFlags(int pGate)
{

    return gates[pGate].gateFlags.getValue();

}//end of Channel::getGateFlags
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateLevel
//
// Sets the gate level of pGate.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateLevel(int pGate, int pLevel, boolean pForceUpdate)
{

    if (pLevel != gates[pGate].gateLevel.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].gateLevel.setValue(pLevel);

}//end of Channel::setGateLevel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateLevel
//
// Gets the gate level of pGate.
//

public int getGateLevel(int pGate)
{

    return gates[pGate].gateLevel.getValue();

}//end of Channel::getGateLevel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateHitCount
//
// Sets the hit count value for the gate.  This is the number of consecutive
// times the signal must exceed the gate level before it will cause an alarm.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateHitCount(int pGate, int pHitCount, boolean pForceUpdate)
{

    if (pHitCount != gates[pGate].gateHitCount.getValue()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].gateHitCount.setValue(pHitCount);

}//end of Channel::setGateHitCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateHitCount
//
// Returns the hit count value for the gate.  See setGateHitCount for more
// info.
//

public int getGateHitCount(int pGate)
{

    return gates[pGate].gateHitCount.getValue();

}//end of Channel::getGateHitCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateMissCount
//
// Sets the miss count value for the gate.  This is the number of consecutive
// times the signal must fail to exceed the gate level before it will cause an
// alarm.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateMissCount(int pGate, int pMissCount, boolean pForceUpdate)
{

    if (pMissCount != gates[pGate].gateMissCount.getValue())
        pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].gateMissCount.setValue(pMissCount);

}//end of Channel::setGateMissCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getGateMissCount
//
// Returns the miss count value for the gate.  See setGateMissCount for more
// info.
//

public int getGateMissCount(int pGate)
{

    return gates[pGate].gateMissCount.getValue();

}//end of Channel::getGateMissCount
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getSigProcThreshold
//
// Returns the signal processing threshold value for the gate.  See
// setSigProcThreshold for more info.
//

public int getSigProcThreshold(int pGate)
{

    return gates[pGate].sigProcThreshold.getValue();

}//end of Channel::getSigProcThreshold
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateSigProc
//
// Sets the signal processing mode for pGate to pMode.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateSigProc(int pGate, String pMode, boolean pForceUpdate)
{

    if (!pMode.equals(gates[pGate].getSignalProcessing())) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].setSignalProcessing(pMode);

}//end of Channel::setGateSigProc
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setGateSigProcThreshold
//
// Sets the signal processing threshold for pGate.  This value is used by
// various signal processing methods to trigger events.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setGateSigProcThreshold(
                               int pGate, int pThreshold, boolean pForceUpdate)
{

    if (pThreshold != gates[pGate].sigProcThreshold.getValue())
        pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    gates[pGate].sigProcThreshold.setValue(pThreshold);

}//end of Channel::setGateSigProcThreshold
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::isAnyGatePositionChanged
//
// Checks if any gate start, width, or level values have been changed.
//

private boolean isAnyGatePositionChanged()
{

    for (int i = 0; i < numberOfGates; i++)
        if (gates[i].isPositionChanged()) return(true);

   return(false);

}//end of Channel::isAnyGatePositionChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::isAnyGateSigProcThresholdChanged
//
// Checks if any gate's signal processing threshold has changed.
//

private boolean isAnyGateSigProcThresholdChanged()
{

    for (int i = 0; i < numberOfGates; i++)
        if (gates[i].sigProcThreshold.getDataChanged()) return(true);

   return(false);

}//end of Channel::isAnyGateSigProcThresholdChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::isAnyGateFlagsChanged
//
// Checks if any gate flags have changed.
//

private boolean isAnyGateFlagsChanged()
{

    for (int i = 0; i < numberOfGates; i++)
        if (gates[i].isFlagsChanged()) return(true);

   return(false);

}//end of Channel::isAnyGateFlagsChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::isAnyDACGatePositionChanged
//
// Checks if any DAC gate start, width, or gain values have been changed.
//

private boolean isAnyDACGatePositionChanged()
{

    for (int i = 0; i < numberOfDACGates; i++)
        if (dacGates[i].isPositionChanged()) return(true);

   return(false);

}//end of Channel::isAnyDACGatePositionChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::isAnyDACGateFlagsChanged
//
// Checks if any DAC gate flags have changed.
//

private boolean isAnyDACGateFlagsChanged()
{

    for (int i = 0; i < numberOfDACGates; i++)
        if (dacGates[i].isFlagsChanged()) return(true);

   return(false);

}//end of Channel::isAnyDACGateFlagsChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::isAnyGateHitMissChanged
//
// Checks if any gate Hit/Miss count values have changed.
//

private boolean isAnyGateHitMissChanged()
{

    for (int i = 0; i < numberOfGates; i++)
        if (gates[i].gateHitCount.getDataChanged()
            || gates[i].gateMissCount.getDataChanged()) return(true);

   return(false);

}//end of Channel::isAnyGateHitMissChanged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendGateParameters
//
// Sends gate start, width, and level to the remotes.
//

public void sendGateParameters()
{

    //unknown which gate(s) have changed data, so check them all

    for (int i = 0; i < numberOfGates; i++){

        if (gates[i].isPositionChanged()){

            if (utBoard != null)
                utBoard.sendGate(boardChannel, i,
                    (int)(gates[i].gateStart.applyValue() / uSPerDataPoint),
                    (int)(gates[i].gateWidth.applyValue() / uSPerDataPoint),
                    gates[i].gateLevel.applyValue());

        }//gates[i].gateStart.getDataChanged()...
    }// for (int i = 0; i < numberOfGates; i++)

}//end of Channel::sendGateParameters
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendGateFlags
//
// Sends gate flags to the remotes.
//

public void sendGateFlags()
{

    //unknown which gate(s) have changed data, so check them all

    for (int i = 0; i < numberOfGates; i++){
        if (gates[i].getFlags().getDataChanged()){

            if (utBoard != null) utBoard.sendGateFlags(
                             boardChannel, i, gates[i].getFlags().applyValue());

            }//if (gates[i].getFlags().getDataChanged() == true)
        }// for (int i = 0; i < numberOfGates; i++)

}//end of Channel::sendGateFlags
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendGateSigProcThreshold
//
// Sends gate signal processing thresholds to the remotes.
//

public void sendGateSigProcThreshold()
{

    //unknown which gate(s) have changed data, so check them all

    for (int i = 0; i < numberOfGates; i++){

        if (gates[i].sigProcThreshold.getDataChanged()){

            int threshold = gates[i].sigProcThreshold.applyValue();

            if (utBoard != null)
                utBoard.sendGateSigProcThreshold(boardChannel, i, threshold);

        }//if (gates[i].sigProcThreshold.getDataChanged())
    }// for (int i = 0; i < numberOfGates; i++)

}//end of Channel::sendGateSigProcThreshold
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendGateHitMiss
//
// Sends gate hit and miss values to the remotes.
//

public void sendGateHitMiss()
{
    //unknown which gate(s) have changed data, so check them all

    for (int i = 0; i < numberOfGates; i++){

        if (gates[i].gateHitCount.getDataChanged()
             || gates[i].gateMissCount.getDataChanged()){

            if (utBoard != null)
                utBoard.sendHitMissCounts(boardChannel, i,
                gates[i].gateHitCount.applyValue(),
                gates[i].gateMissCount.applyValue());

        }//if (gates[i].gateHitCount.getDataChanged()...
    }// for (int i = 0; i < numberOfGates; i++)

}//end of Channel::sendGateHitMiss
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendDACGateParameters
//
// Sends DAC gate start, width, and level to the remotes.
//

public void sendDACGateParameters()
{

    //unknown which gate(s) have changed data, so check them all
    //clear the flags even if utBoard is null so they won't be checked again

    for (int i = 0; i < numberOfDACGates; i++){

        if (dacGates[i].isPositionChanged()){

            if (utBoard != null){

                utBoard.sendDAC(boardChannel, i,
                    (int)(dacGates[i].gateStart.applyValue() / uSPerDataPoint),
                    (int)(dacGates[i].gateWidth.applyValue() / uSPerDataPoint),
                    dacGates[i].gainForRemote.applyValue());

            }//if (utBoard != null)
        }//if (dacGates[i].isPositionChanged())
    }// for (int i = 0; i < numberOfDACGates; i++)

}//end of Channel::sendDACGateParameters
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setDACActive
//
// Sets the DAC gate active flag.
//
// If pForceUpdate is true, the value(s) will always be sent to the DSP.  If
// the flag is false, the value(s) will be sent only if they have changed.
//

public void setDACActive(int pGate, boolean pValue, boolean pForceUpdate)
{

    if (pValue != dacGates[pGate].getActive()) pForceUpdate = true;

    if (!pForceUpdate) return; //do nothing unless value change or forced

    dacGates[pGate].setActive(pValue);

}//end of Channel::setDACActive
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// DACGate::copyGate
//
// Copies the appropriate values from source gate pSourceGate to gate indexed
// by gate pDestGate.
//

public void copyGate(int pDestGate, DACGate pSourceGate)
{

    //copy pSourceGate to pDestGate
    dacGates[pDestGate].copyFromGate(pSourceGate);

}//end of DACGate::copyGate
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendDACGateFlags
//
// Sends DAC flags to the remotes.
//

public void sendDACGateFlags()
{

    //unknown which gate(s) have changed data, so check them all

    for (int i = 0; i < numberOfDACGates; i++){
        if (dacGates[i].gateFlags.getDataChanged()){

            if (utBoard != null) utBoard.sendDACGateFlags(
                          boardChannel, i, dacGates[i].getFlags().applyValue());

        }//if (dacGates[i].gateFlags.getDataChanged())
    }// for (int i = 0; i < numberOfDACGates; i++)

}//end of Channel::sendDACGateFlags
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::calculateDACGateTimeLocation
//
// Forces the specified DAC gate to update its position, width, and level time
// values by calculating them from its pixel location values.
//

public void calculateDACGateTimeLocation(int pDACGate,
       double pUSPerPixel, int pDelayPix, int pCanvasHeight, int pVertOffset,
                                                          boolean pForceUpdate)
{

    dacGates[pDACGate].calculateGateTimeLocation(
                            pUSPerPixel, pDelayPix, pCanvasHeight, pVertOffset);

}//end of Channel::calculateDACGateTimeLocation
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::sendDataChangesToRemotes
//
// If any data has been changed, sends the changes to the remotes.
//

public void sendDataChangesToRemotes()
{

    //do nothing if no data changed for any synced variables
    if (!syncedVarMgr.getDataChangedMaster()) return;

    if (flags1SetMask.getDataChanged()) sendSetFlags1();

    if (flags1ClearMask.getDataChanged()) sendClearFlags1();

    if (softwareGain.getDataChanged()) sendSoftwareGain();

    if (hardwareGain1.getDataChanged()) sendHardwareGain();

    if (hardwareGain2.getDataChanged()) sendHardwareGain();

    if (aScanSmoothing.getDataChanged()) sendAScanSmoothing();

    if (mode.getDataChanged()) sendMode();

    if (hardwareRange.getDataChanged() || aScanScale.getDataChanged())
        sendRange();

    if (isAnyGatePositionChanged()) sendGateParameters();

    if (isAnyGateFlagsChanged()) sendGateFlags();

    if (isAnyGateHitMissChanged()) sendGateHitMiss();

    if (isAnyGateSigProcThresholdChanged()) sendGateSigProcThreshold();

    if (isAnyDACGatePositionChanged()) sendDACGateParameters();

    if (isAnyDACGateFlagsChanged()) sendDACGateFlags();

}//end of Channel::sendDataChangesToRemotes
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACGateStart
//
// Returns the DAC gate start position of pGate.
//

public double getDACGateStart(int pGate)
{

    return dacGates[pGate].gateStart.getValue();

}//end of Channel::getDACGateStart
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACGateWidth
//
// Gets the DAC gate width of pGate.
//

public double getDACGateWidth(int pGate)
{

    return dacGates[pGate].gateWidth.getValue();

}//end of Channel::getDACGateWidth
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACGateLevel
//
// Gets the DAC gate level of pGate.
//

public int getDACGateLevel(int pGate)
{

    return dacGates[pGate].gateLevel.getValue();

}//end of Channel::getDACGateLevel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACGateActive
//
// Returns the DAC gate active flag.
//

public boolean getDACGateActive(int pGate)
{

    return dacGates[pGate].getActive();

}//end of Channel::getDACGateActive
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::getDACGateSelected
//
// Returns the DAC gate selected flag.
//

public boolean getDACGateSelected(int pGate)
{

    return dacGates[pGate].getSelected();

}//end of Channel::getDACGateSelected
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::setAllDACGateDataChangedFlags
//
// Sets all data changed flags related to the DAC gates.  Setting them true
// will force all data to be sent to the remotes.
//

public void setAllDACGateDataChangedFlags(boolean pValue)
{

    //not used at this time

}//end of Channel::setAllDACGateDataChangedFlags
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::loadCalFile
//
// This loads the file used for storing calibration information pertinent to a
// job, such as gains, offsets, thresholds, etc.
//
// Each object is passed a pointer to the file so that they may load their
// own data.
//

public void loadCalFile(IniFile pCalFile)
{

    String section = "Channel " + (channelIndex + 1);

    aScanDelay = pCalFile.readDouble(section, "Sample Delay", 0);
    aScanRange = pCalFile.readDouble(section, "Range", 53.0);
    softwareGain.setValue(pCalFile.readDouble(section, "Software Gain", 0));
    hardwareGain1.setValue(
                        pCalFile.readInt(section, "Hardware Gain Stage 1", 2));
    hardwareGain2.setValue(
                        pCalFile.readInt(section, "Hardware Gain Stage 2", 1));
    interfaceTracking = pCalFile.readBoolean(
                                        section, "Interface Tracking", false);
    dacEnabled = pCalFile.readBoolean(section, "DAC Enabled", false);
    mode.setValue(pCalFile.readInt(section, "Signal Mode", 0));
    //default previousMode to mode if previousMode has never been saved
    previousMode =
            pCalFile.readInt(section, "Previous Signal Mode", mode.getValue());
    channelOn = (mode.getValue() != UTBoard.CHANNEL_OFF) ? true : false;
    rejectLevel = pCalFile.readInt(section, "Reject Level", 0);
    aScanSmoothing.setValue(
                      pCalFile.readInt(section, "AScan Display Smoothing", 1));

    // call each gate to load its data
    for (int i = 0; i < numberOfGates; i++) gates[i].loadCalFile(pCalFile);

    // call each DAC gate to load its data
    for (int i = 0; i < numberOfDACGates; i++)
        dacGates[i].loadCalFile(pCalFile);

    //determine the span from the earliest gate edge to the latest (in time)
    calculateGateSpan();

}//end of Channel::loadCalFile
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Channel::saveCalFile
//
// This saves the file used for storing calibration information pertinent to a
// job, such as gains, offsets, thresholds, etc.
//
// Each object is passed a pointer to the file so that they may save their
// own data.
//

public void saveCalFile(IniFile pCalFile)
{

    String section = "Channel " + (channelIndex + 1);

    pCalFile.writeDouble(section, "Sample Delay", aScanDelay);
    pCalFile.writeDouble(section, "Range", aScanRange);
    pCalFile.writeDouble(section, "Software Gain", softwareGain.getValue());
    pCalFile.writeInt(
                    section, "Hardware Gain Stage 1", hardwareGain1.getValue());
    pCalFile.writeInt(
                    section, "Hardware Gain Stage 2", hardwareGain2.getValue());
    pCalFile.writeBoolean(section, "Interface Tracking", interfaceTracking);
    pCalFile.writeBoolean(section, "DAC Enabled", dacEnabled);
    pCalFile.writeInt(section, "Signal Mode", mode.getValue());
    pCalFile.writeInt(section, "Previous Signal Mode", previousMode);
    pCalFile.writeInt(section, "Reject Level", rejectLevel);
    pCalFile.writeInt(
                section, "AScan Display Smoothing", aScanSmoothing.getValue());

    // call each gate to save its data
    for (int i = 0; i < numberOfGates; i++)
        gates[i].saveCalFile(pCalFile);

    // call each DAC gate to save its data
    for (int i = 0; i < numberOfDACGates; i++)
       dacGates[i].saveCalFile(pCalFile);

}//end of Channel::saveCalFile
//-----------------------------------------------------------------------------

}//end of class Channel
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
