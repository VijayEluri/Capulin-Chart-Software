/******************************************************************************
* Title: Trace.java
* Author: Mike Schoonover
* Date: 3/17/08
*
* Purpose:
*
* This class handles a single trace.
*
* Open Source Policy:
*
* This source code is Public Domain and free to any interested party.  Any
* person, company, or organization may do with it as they please.
*
*/

//-----------------------------------------------------------------------------

package chart.mksystems.stripchart;

import chart.Viewer;
import chart.Xfer;
import chart.mksystems.hardware.Hardware;
import chart.mksystems.inifile.IniFile;
import chart.mksystems.settings.Settings;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.util.HashMap;
import javax.swing.*;

//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------
// class Trace
//
// This class creates and controls a trace.
//

public class Trace extends Object{

    Settings settings;
    IniFile configFile;
    TraceGlobals traceGlobals;
    int chartGroup;
    public int chartIndex;
    public StripChart chart;
    int traceIndex;
    Hardware hardware;
    JPanel canvas;
    int canvasXLimit;
    int canvasYLimit;
    PlotVars plotVs, repaintVs;
    TraceHdwVars hdwVs;
    int gridXSpacing, gridXSpacingT;
    public boolean leadTrace = false;
    public boolean trailTrace = false;

    String title;
    public String shortTitle;
    public String keyLabel;
    int keyXPosition, keyYPosition;
    public Rectangle2D keyBounds;
    Color traceColor;
    public int head;
    public boolean flaggingEnabled = false;
    public boolean useVerticalBarToMarkEndMasks = false;
    public boolean suppressTraceInEndMasks = false;
    public double distanceSensorToFrontEdgeOfHead;
    public double delayDistance;
    public double startFwdDelayDistance;
    public double startRevDelayDistance;

    public TraceData traceData;
    TraceDatum traceDatum;

    boolean invert;
    int pixelOffset;
    double pixelScaling;
    double preScaling;
    double preOffset;
    boolean higherMoreSevere;

    public String prevLinearPos = "";
    public String prevAmplitudeText = "";
    public int prevClockPos = -1;

    Color backgroundColor;
    Color gridColor;
    Threshold[] thresholds;
    int numberOfThresholds;
    int flagThreshold;
    //hardware channel of the last value to be stored as a peak
    public int peakChannel;
    //hardware channel of the last flag
    public int lastFlaggedChannel;
    //clock position of the last flag
    public int lastFlaggedClockPos;
    //wall thickness value - used by wall traces
    public double wallThickness;

    public boolean positionAdvanced;  //used by external class

//-----------------------------------------------------------------------------
// Trace::Trace (constructor)
//
// The parameter configFile is used to load configuration data.  The IniFile
// should already be opened and ready to access.
//

public Trace(Settings pSettings, IniFile pConfigFile, int pChartGroup,
            StripChart pChart,
            int pChartIndex, int pTraceIndex, TraceGlobals pTraceGlobals,
            Color pBackgroundColor, Color pGridColor, int pGridXSpacing,
                Threshold[] pThresholds, Hardware pHardware)
{

    settings = pSettings; configFile = pConfigFile;
    chartGroup = pChartGroup;
    chart = pChart;
    chartIndex = pChartIndex; traceIndex = pTraceIndex; gridColor = pGridColor;
    traceGlobals = pTraceGlobals;
    gridXSpacing = pGridXSpacing ;
    //some of the code is more efficient with a variable of gridXSpacing-1
    gridXSpacingT = pGridXSpacing - 1;
    backgroundColor = pBackgroundColor; hardware = pHardware;
    thresholds = pThresholds; numberOfThresholds = thresholds.length;

    plotVs = new PlotVars(); repaintVs = new PlotVars();
    hdwVs = new TraceHdwVars();

}//end of Trace::Trace (constructor)
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::init
//
// Initializes the object.  MUST be called by sub classes after instantiation.
//

public void init()
{

    //read the configuration file and create/setup the charting/control elements
    configure(configFile);

    //link the trace to the appropriate channel as specified in the config file
    //hardware may be null if this object is used for viewing only, so skip this
    //step if so
    if (hardware != null) {
        hardware.linkTraces(chartGroup, chartIndex, traceIndex, traceData,
                                            thresholds, hdwVs.plotStyle, this);
    }

}//end of Trace::init
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::configure
//
// Loads configuration settings from the configuration.ini file.
//

private void configure(IniFile pConfigFile)
{

    String section = "Chart Group " + (chartGroup + 1)
             + " Strip Chart " + (chartIndex + 1) + " Trace " + (traceIndex+1);

    title = pConfigFile.readString(section, "Title", "*");

    shortTitle = pConfigFile.readString(section, "Short Title", "*");

    keyLabel = pConfigFile.readString(section, "Key Label", "*");

    keyXPosition = pConfigFile.readInt(section, "Key X Position", 100);

    keyYPosition = pConfigFile.readInt(section, "Key Y Position", 23);

    traceColor = pConfigFile.readColor(section, "Color", Color.BLACK);

    head = pConfigFile.readInt(section, "Head", 1);

    distanceSensorToFrontEdgeOfHead = pConfigFile.readDouble(section,
                             "Distance From Sensor to Front Edge of Head", 0.0);

    int sizeOfDataBuffer = pConfigFile.readInt(
                                        section, "Number of Data Points", 1200);

    invert = pConfigFile.readBoolean(section, "Invert Trace", false);

    pixelOffset = pConfigFile.readInt(section, "Pixel Offset", 0);

    pixelScaling = pConfigFile.readDouble(section, "Pixel Scaling", 1.0);

    preScaling = pConfigFile.readDouble(section, "PreScaling", 1.0);

    preOffset = pConfigFile.readDouble(section, "PreOffset", 0.0);

    higherMoreSevere = pConfigFile.readBoolean(
                                 section, "Higher Signal is More Severe", true);

    useVerticalBarToMarkEndMasks = pConfigFile.readBoolean(
                          section, "Use Vertical Bar to Mark End Masks", false);

    suppressTraceInEndMasks = pConfigFile.readBoolean(
                                 section, "Suppress Traces in End Masks", true);

    hdwVs.setPlotStyle(pConfigFile.readInt(section, "Plot Style", 0));

    hdwVs.setSimDataType(
                     pConfigFile.readInt(section, "Simulation Data Type", 0));

    //create the arrays to hold data points and flag/decoration info
    if (sizeOfDataBuffer > 100000) {sizeOfDataBuffer = 100000;}

    traceData = new TraceData(sizeOfDataBuffer, hdwVs.plotStyle,
                        higherMoreSevere ? TraceData.MAX : TraceData.MIN);
    traceData.init();

    traceDatum = new TraceDatum();

}//end of Trace::configure
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::setCanvas
//
// Stores a pointer to the canvas on which the traces are drawn.
//

public void setCanvas(JPanel pCanvas)
{

    canvas = pCanvas;

}//end of Trace::setCanvas
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::handleSizeChanges
//
// Updates any values related to the size of display objects.  Called after
// the display has been set and any time a size may have changed.
//

public void handleSizeChanges()
{

    canvasXLimit = canvas.getWidth() - 1;
    canvasYLimit = canvas.getHeight() - 1;

}//end of Trace::handleSizeChanges
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::drawKeyLabel
//
// Draws the key label on the graphics device pG2.  The key label describes
// the trace and is drawn in the color of the trace.
//

public void drawKeyLabel(Graphics2D pG2)
{

    if (keyLabel.compareTo("<not visible>") == 0) {return;}

    //set the background color for the text to white so that most colors are
    //more visible

    HashMap<TextAttribute, Object> map = new HashMap<TextAttribute, Object>();

    Font font = new Font(Font.SERIF, Font.PLAIN, 12);

    map.put(TextAttribute.BACKGROUND, Color.WHITE);
    font = font.deriveFont(map);
    pG2.setFont(font);

    String keyString = " " + keyLabel + " ";

    pG2.setColor(traceColor);

    //draw the key text, outline it with a black rectangle, and store the
    //dimensions of the text so mouse clicks on the key can be detected

    //draw the text
    FontRenderContext frc = pG2.getFontRenderContext();
    TextLayout layout = new TextLayout(keyString, font, frc);
    layout.draw(pG2, keyXPosition, keyYPosition);

    //get the boundaries of the text
    keyBounds = layout.getBounds();
    keyBounds.setRect(keyBounds.getX() + keyXPosition,
                      keyBounds.getY() + keyYPosition,
                      keyBounds.getWidth(),
                      keyBounds.getHeight());

    //outline the text with a rectangle
    pG2.setColor(Color.BLACK);
    pG2.draw(keyBounds);

}//end of Trace::drawKeyLabel
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::resetTrace
//
// Clears all data.
//
// Do not call resetTrace from the constructor because all data may not be
// available at that time.
//

public void resetTrace()
{

    plotVs.gridCounter = 0; //used to place grid marks

    plotVs.drawTrace = true; //draw trace when plotting data

    traceGlobals.bufOffset = 0; //left edge of screen starts at position 0

    traceGlobals.scrollCount = 0; //number of pixels chart has been scrolled

    //pixel position on the screen where data is being plotted
    plotVs.pixPtr = -1;

    traceData.totalReset();

}//end of Trace::resetTrace
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::saveSegment
//
// Saves the data for a segment to the open file pOut.
//
// This function should be called whenever a new segment is completed - each
// segment could represent a piece being monitored, a time period, etc.
//
// This function should be called after the segment end has been marked and
// before the next segment start has been marked so that the end points
// of the data to be saved are known.
//

public void saveSegment(BufferedWriter pOut) throws IOException
{

    pOut.write("[Trace]"); pOut.newLine();
    pOut.write("Trace Index=" + traceIndex); pOut.newLine();
    pOut.write("Trace Title=" + title); pOut.newLine();
    pOut.write("Trace Short Title=" + shortTitle); pOut.newLine();
    pOut.newLine();

    traceData.saveSegment(pOut);

}//end of Trace::saveSegment
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::loadSegment
//
// Loads the meta data, data points, and flags for a segment from pIn.  It is
// expected that the Trace section is next in the file.
//
// Returns the last line read from the file so that it can be passed to the
// next process.
//
// For the Trace section, the [Trace] tag may or may not have already
// been read from the file by the code handling the previous section.  If it has
// been read, the line containing the tag should be passed in via pLastLine.
//

public String loadSegment(BufferedReader pIn, String pLastLine)
                                                            throws IOException
{

    //handle entries for the trace itself
    String line = processTraceMetaData(pIn, pLastLine);

    try{
        //read in trace data points
        line = traceData.loadSegment(pIn, line);
    }
    catch(IOException e){

        //add identifying details to the error message and pass it on
        throw new IOException(e.getMessage() + " of Chart Group "
                + chartGroup + " Chart " + chartIndex + " Trace " + traceIndex);
    }

    return(line);

}//end of Trace::loadSegment
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::processTraceMetaData
//
// Processes file entries for the trace such as the title via pIn.
//
// Returns the last line read from the file so that it can be passed to the
// next process.
//
// For the Trace section, the [Trace] tag may or may not have already
// been read from the file by the code handling the previous section.  If it has
// been read, the line containing the tag should be passed in via pLastLine.
//

private String processTraceMetaData(BufferedReader pIn, String pLastLine)
                                                             throws IOException

{

    String line;
    boolean success = false;
    Xfer matchSet = new Xfer(); //for receiving data from function calls

    //if pLastLine contains the [Trace] tag, then skip ahead else read until
    // end of file reached or "[Trace]" section tag reached

    if (Viewer.matchAndParseString(pLastLine, "[Trace]", "",  matchSet)) {
        success = true; //tag already found
    }
    else {
        while ((line = pIn.readLine()) != null){  //search for tag
            if (Viewer.matchAndParseString(line, "[Trace]", "",  matchSet)){
                success = true; break;
            }
        }//while
    }//else

    if (!success) {
        throw new IOException(
            "The file could not be read - section not found for Chart Group "
            + chartGroup + " Chart " + chartIndex + " Trace " + traceIndex);
    }

    //set defaults
    int traceIndexRead = -1;
    String titleRead = "", shortTitleRead = "";

    //scan the first part of the section and parse its entries
    //these entries apply to the chart group itself

    success = false;
    while ((line = pIn.readLine()) != null){

        //stop when next section tag reached (will start with [)
        if (Viewer.matchAndParseString(line, "[", "",  matchSet)){
            success = true; break;
        }

        //read the "Trace Index" entry - if not found, default to -1
        if (Viewer.matchAndParseInt(line, "Trace Index", -1, matchSet)) {
            traceIndexRead = matchSet.rInt1;
        }

        //read the "Trace Title" entry - if not found, default to ""
        if (Viewer.matchAndParseString(line, "Trace Title", "", matchSet)) {
            titleRead = matchSet.rString1;
        }

        //read the "Trace Short Title" entry - if not found, default to ""
        if (Viewer.matchAndParseString(
                                    line, "Trace Short Title", "", matchSet)) {
            shortTitleRead = matchSet.rString1;
        }

    }

    //apply settings
    title = titleRead; shortTitle = shortTitleRead;

    if (!success) {
        throw new IOException(
        "The file could not be read - missing end of section for Chart Group "
                + chartGroup + " Chart " + chartIndex + " Trace " + traceIndex);
    }

    //if the index number in the file does not match the index number for this
    //threshold, abort the file read

    if (traceIndexRead != traceIndex) {
        throw new IOException(
            "The file could not be read - section not found for Chart Group "
                + chartGroup + " Chart " + chartIndex + " Trace " + traceIndex);
    }

    return(line); //should be "[xxxx]" tag on success, unknown value if not

}//end of Trace::processTraceMetaData
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::newDataIsReady
//
// Checks to see if any new data is ready to be plotted or erased.
//
// Returns true if new data is ready, false if not.
//

public boolean newDataIsReady()
{

    return (traceData.newDataIsReady());

}//end of Trace::newDataIsReady
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::plotNewData
//
// Plots new data in the array.  Assumes new data has been added to the next
// slot in the buffer.
//
// Returns the value last plotted.  If plot style is SPAN, this value will be
// from the second data set.
//

public int plotNewData(Graphics2D pG2)
{

    int dataReady = traceData.getNewData(traceDatum);

    if (dataReady == TraceData.NO_NEW_DATA) {return(0);}

    //plot new point
    if (dataReady == TraceData.FORWARD) {
        return plotPoint(pG2, plotVs, traceDatum);
    }

    if (dataReady == TraceData.REVERSE) {
        return erasePoint(pG2, plotVs, traceDatum);
    }

    return(0);

}//end of Trace::plotNewData
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::plotPoint
//
// Plots a single data point in the array.  Assumes new data has been added to
// the next slot in the buffer.
//
// All variables are passed via pVars and pTraceDatum, so different sets can be
// used depending on the context.
//
// Returns the value last plotted.  If plot style is SPAN, this value will be
// from the second data set.
//

public int plotPoint(Graphics2D pG2, PlotVars pVars, TraceDatum pTraceDatum)
{

    //increment the pixel pointer until it reaches the right edge, then shift
    //the screen left and keep pointer the same to create scrolling effect
    //the scrolling starts at canvasXLimit-10 to allow room for flags

    if (pVars.pixPtr < canvasXLimit-10) {
        pVars.pixPtr++;
    }
    else{
        //if this is the lead trace, shift the chart left and erase right slice
        if (leadTrace){
            //scroll the screen 1 pixel to the left
            pG2.copyArea(1, 0, canvas.getWidth(), canvas.getHeight(), -1, 0);
            //erase the line at the far right
            pG2.setColor(backgroundColor);
            pG2.drawLine(canvasXLimit, 0, canvasXLimit, canvas.getHeight());

            //shift the buffer location where the display starts - this is done
            //when plotting new data but not when the screen is being repainted
            //as this section is not reached when called by the repaint code;
            //the drawing stops before the right edge of the screen is reached
            //the counter is global for all traces on a chart because they all
            //are shifted when trace 0 shifts the canvas

            traceGlobals.bufOffset++;
            if (traceGlobals.bufOffset == getDataBuffer1().length) {
                traceGlobals.bufOffset = 0;
            }

            //track the number of pixels the chart has been scrolled - this is
            //used to determine the proper location of grid marks and other
            //decorations when the screen is repainted

            traceGlobals.scrollCount++;

        }//if (traceIndex == 0)

    }//else if (pVars.pixPtr...

    //if this is the lead trace draw the decorations
    if (leadTrace){
        for (int j = 0; j < numberOfThresholds; j++) {
            thresholds[j].drawSlice(pG2, pVars.pixPtr);
        }

        if (pVars.gridCounter++ == gridXSpacingT){
            drawGrid(pG2, pVars.pixPtr, canvasYLimit);
            pVars.gridCounter = 0;
        }

        //if segment start flag set, draw a vertical separator bar
        if ((pTraceDatum.flags & TraceData.SEGMENT_START_SEPARATOR) != 0){
            pG2.setColor(gridColor);
            pG2.drawLine(pVars.pixPtr, canvasYLimit, pVars.pixPtr, 0);
        }

        //if segment end flag set, draw a vertical separator bar
        if ((pTraceDatum.flags & TraceData.SEGMENT_END_SEPARATOR) != 0){
            pG2.setColor(gridColor);
            pG2.drawLine(pVars.pixPtr, canvasYLimit, pVars.pixPtr, 0);
        }

        //if end mask flag set and option enabled, draw a vertical separator bar
        if (useVerticalBarToMarkEndMasks
                        && (pTraceDatum.flags & TraceData.END_MASK_MARK) != 0){
            pG2.setColor(Color.GREEN);
            pG2.drawLine(pVars.pixPtr, canvasYLimit, pVars.pixPtr, 0);
        }

    }// if (leadTrace)

    //apply offset, scaling, limits to y value
    //for span style, the high peaks and low peaks are in separate buffers in
    //the same index position

    if (hdwVs.plotStyle == TraceHdwVars.POINT_TO_POINT){
        pVars.y1 = pTraceDatum.prevData1;
        pVars.y2 = pTraceDatum.newData1;
    }
    else if (hdwVs.plotStyle == TraceHdwVars.STICK){
        pVars.y1 = 0;
        pVars.y2 = pTraceDatum.newData1;
    }
    else if (hdwVs.plotStyle == TraceHdwVars.SPAN){
        pVars.y1 = pTraceDatum.newData1;
        pVars.y2 = pTraceDatum.newData2;
    }

    //save the value plotted so it can be returned on exit
    int lastPlotted = pVars.y2;

    //if the drawTrace flag is false, exit having only drawn decorations
    if (!pVars.drawTrace) {return(lastPlotted);}

    //prepare to draw the trace

    //apply pixel scaling
    pVars.y1 *= pixelScaling; pVars.y2 *= pixelScaling;

    //apply offset
    pVars.y1 += pixelOffset; pVars.y2 += pixelOffset;

    //apply limits
    if (pVars.y1 > canvasYLimit) {pVars.y1 = canvasYLimit;}
    if (pVars.y2 > canvasYLimit) {pVars.y2 = canvasYLimit;}

    //invert y value if specified
    if (invert) {
        pVars.y1 = canvasYLimit - pVars.y1;
        pVars.y2 = canvasYLimit - pVars.y2;
    }

    //debug mks -- remove this
    //offsets the traces so you can see them clearly for debugging
    //pVars.y1 -= 2 * traceIndex;
    //pVars.y2 -= 2 * traceIndex;
    //debug mks

    //if plotting point-point style, draw line from last height to next height
    //if plotting stick style, draw line from 0 to the signal height
    //if plotting span style, draw line between high and low peak in vertical
    // line

    pG2.setColor(traceColor);

    if (hdwVs.plotStyle == TraceHdwVars.POINT_TO_POINT) {
        pG2.drawLine(pVars.pixPtr-1, pVars.y1, pVars.pixPtr, pVars.y2);
    }
    else
    if (hdwVs.plotStyle == TraceHdwVars.STICK) {
        pG2.drawLine(pVars.pixPtr, pVars.y1, pVars.pixPtr, pVars.y2);
    }
    else
    if (hdwVs.plotStyle == TraceHdwVars.SPAN) {
        pG2.drawLine(pVars.pixPtr, pVars.y1, pVars.pixPtr, pVars.y2);
    }

    //if there is a flag set for this data point then draw it - threshold

    //indices are shifted by two as 0 = no flag and 1 = user flag
    if ((flagThreshold =
                ((pTraceDatum.flags & TraceData.THRESHOLD_MASK) >> 9)-2) >= 0){

        int flagY = pVars.y2; //draw flag at height of peak for non-SPAN styles

        //for span mode, draw flag at min or max depending on bit in flagBuffer
        if (hdwVs.plotStyle == TraceHdwVars.SPAN){
            if ((pTraceDatum.flags & TraceData.MIN_MAX_FLAGGED) == 0){
                flagY = pVars.y1;
            }
            else{
                flagY = pVars.y2;
            }
        }//if (hdwVs.plotStyle ==

        thresholds[flagThreshold].drawFlag(pG2, pVars.pixPtr, flagY);
    }
    else
        if (flagThreshold == -1) {drawUserFlag(pG2, pVars.pixPtr, pVars.y2);}

    return(lastPlotted);

}//end of Trace::plotPoint
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::erasesPoint
//
// Erases a single data point in the array.
//
// All variables are passed via pVars and pTraceDatum, so different sets can be
// used depending on the context.
//

public int erasePoint(Graphics2D pG2, PlotVars pVars, TraceDatum pTraceDatum)
{

    //debug mks -- next section for scrolling in reverse NOT tested well!
    // in fact, it probably doesn't work well at all, but for now most
    // reversing doesn't go far enough back to require reverse scroll

    //decrement the pixel pointer until it reaches the left edge, then shift the
    //screen right and keep pointer the same to create scrolling effect

    if (pVars.pixPtr > 0) {
        pVars.pixPtr--;
    }
    else{
        //if this is trailing trace, shift the chart right and erase left slice
        if (trailTrace){
            //scroll the screen 1 pixel to the left
            pG2.copyArea(0, 0, canvas.getWidth(), canvas.getHeight(), 1, 0);
            //erase the line at the far right
            pG2.setColor(backgroundColor);
            pG2.drawLine(0, 0, 0, canvas.getHeight());

            //shift the buffer location where the display starts - this is done
            //when plotting new data but not when the screen is being repainted
            //as this section is not reached when called by the repaint code;
            //the drawing stops before the right edge of the screen is reached
            //the counter is global for all traces on a chart because they all
            //are shifted when trace 0 shifts the canvas

            traceGlobals.bufOffset--;
            if (traceGlobals.bufOffset == -1) {
                traceGlobals.bufOffset = traceData.getDataBuffer1().length-1;
            }

            //track the number of pixels the chart has been scrolled - this is
            //used to determine the proper location of grid marks and other
            //decorations when the screen is repainted

            traceGlobals.scrollCount--;

        }//if (traceIndex == 0)

    }//else if (pixPtr <...


    pG2.setColor(backgroundColor);
    pG2.drawLine(pVars.pixPtr+1, 0, pVars.pixPtr+1, canvas.getHeight());

    //save the last value erased so it can be returned on exit
    int lastPlotted = pVars.y2;

    return(lastPlotted);

}//end of Trace::erasePoint
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::drawUserFlag
//
// Draws a user flag which is a circle using the color of the most severe
// threshold.
//

void drawUserFlag(Graphics2D pG2, int xPos, int pSigHeight)

{

    //add 1 to xPos so flag is drawn to the right of the peak

    pG2.setColor(thresholds[0].thresholdColor);

    pG2.fillOval(xPos+1, pSigHeight, 8, 8);

}//end of Trace::drawUserFlag
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::drawGrid
//
// Draws the grid marks
//

void drawGrid(Graphics2D pG2, int pXPos, int pCanvasYLimit)
{

    //for screen display, zero width/height for grid pixels looks best
    //when rendering for printing, must set width to 1 or pixels disappear
    int width = settings.printMode ?  1 : 0;

    pG2.setColor(gridColor);

    for(int i = 9; i < pCanvasYLimit; i+=10){
        pG2.drawRect(pXPos, i, width, 0);
    }

}//end of Trace::drawGrid
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::getTitle
//
// Returns the trace title.
//

public String getTitle()
{

    return (title);

}//end of Trace::getTitle
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::getDataBuffer1
//
// Returns a reference to dataBuffer1.
//

public int[] getDataBuffer1()
{

    return(traceData.getDataBuffer1());

}//end of Trace::getDataBuffer1
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::getPixXY
//
// Translates the data and data position in the circular buffer to pixel
// x,y values.
//

void getPixXY(PlotVars pVs)
{


}//end of Trace::getPixXY
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::findMinValue
//
// Finds the minimum value in dataBuffer1.
//
// Finds the minimum value in dataBuffer1.  Search begins at pStart position
// in the array and ends at pEnd.
//
// Values of pStart and pEnd will be forced between 0 and dataBuffer1.length
// to avoid errors.
//

public int findMinValue(int pStart, int pEnd)
{

    return(traceData.findMinValue(pStart, pEnd));

}//end of Trace::findMinValue
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::findMaxValue
//
// Finds the maximum value in dataBuffer1.  Search begins at pStart position
// in the array and ends at pEnd.
//
// Values of pStart and pEnd will be forced between 0 and dataBuffer1.length
// to avoid errors.
//

public int findMaxValue(int pStart, int pEnd)
{

    return(traceData.findMaxValue(pStart, pEnd));

}//end of Trace::findMaxValue
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::setLastFlagged
//
// Stores the channel number and clock position which was last flagged.  Also
// stores those values in the chart which owns this trace.
//

public void setLastFlagged(int pChannel, int pClock)
{

    lastFlaggedChannel = pChannel;
    chart.lastFlaggedChannel = lastFlaggedChannel;

    lastFlaggedClockPos = pClock;
    chart.lastFlaggedClockPos = lastFlaggedClockPos;

}//end of Trace::setLastFlagged
//-----------------------------------------------------------------------------

//-----------------------------------------------------------------------------
// Trace::paintComponent
//
// Refreshes the canvas using the data in the buffers.
//
// If the end of valid data is reached in the buffer before the full chart is
// redrawn, the repaintVs.drawTrace flag is set false so that plotPoint can
// still be called to draw decorations across the entire chart without drawing
// undefined trace data.
//

public void paintComponent(Graphics2D pG2)
{

    //set starting point to the current buffer offset of the screen display

    traceData.prepareForRepaint(traceGlobals.bufOffset);

    // the repaintVS object is used here to avoid conflict with the
    // plotVs object which tracks plotting of new data

    repaintVs.pixPtr = -1;

    //for repainting, the gridCounter starts at one to sync up with drawing by
    //the plotNewData code

    repaintVs.gridCounter = traceGlobals.scrollCount % gridXSpacing;

    //start with drawing trace allowed - will be set false by plotPoint when
    //an undefined data point reached which signifies the end of valid data

    repaintVs.drawTrace = true;

    //stop short of the end of the screen to avoid triggering chart scroll
    //in the plotPoint function

    int stop = canvasXLimit-10;

    for (int i = 0; i < stop; i++){

        traceData.getDataAtRepaintPoint(traceDatum);

        //stop tracing at end of valid data -- see notes in method header
        if ((traceDatum.flags & TraceData.DATA_VALID) == 0) {
            repaintVs.drawTrace = false;
        }

        plotPoint(pG2, repaintVs, traceDatum);
    }

}//end of Trace::paintComponent
//-----------------------------------------------------------------------------

}//end of class Trace
//-----------------------------------------------------------------------------
//-----------------------------------------------------------------------------