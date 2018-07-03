/*
    Copyright 2013-2018 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.universalgcodesender;

import com.google.gson.JsonObject;
import com.willwinder.universalgcodesender.firmware.DefaultFirmwareSettings;
import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.TinyGGcodeCommandCreator;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.Overrides;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.types.TinyGGcodeCommand;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_CHECK;
import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_IDLE;
import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_SENDING;
import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_SENDING_PAUSED;

/**
 * TinyG Control layer, coordinates all aspects of control.
 *
 * @author wwinder
 * @author Joacim Breiler
 */
public class TinyGController extends AbstractController {
    private static final Logger LOGGER = Logger.getLogger(TinyGController.class.getSimpleName());
    private static final String NOT_SUPPORTED_YET = "Not supported yet.";

    private final DefaultFirmwareSettings firmwareSettings;
    private final Capabilities capabilities;

    private ControllerStatus controllerStatus;
    private String firmwareVersion;

    public TinyGController() {
        super(new TinyGCommunicator());
        capabilities = new Capabilities();
        commandCreator = new TinyGGcodeCommandCreator();

        // TODO add a firmware settings handler for tinyg
        firmwareSettings = new DefaultFirmwareSettings();

        controllerStatus = new ControllerStatus(StringUtils.EMPTY, ControllerState.UNKNOWN, new Position(0, 0, 0, UnitUtils.Units.MM), new Position(0, 0, 0, UnitUtils.Units.MM));
        firmwareVersion = "TinyG unknown version";
    }

    @Override
    public Boolean handlesAllStateChangeEvents() {
        return false;
    }

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public IFirmwareSettings getFirmwareSettings() {
        return firmwareSettings;
    }

    @Override
    public long getJobLengthEstimate(File gcodeFile) {
        return 0;
    }

    @Override
    protected void closeCommBeforeEvent() {
    }

    @Override
    protected void closeCommAfterEvent() {
    }

    @Override
    protected void openCommAfterEvent() throws Exception {
    }

    @Override
    protected void cancelSendBeforeEvent() throws Exception {
        pauseStreaming();
    }

    @Override
    protected void cancelSendAfterEvent() throws Exception {
        // Canceling the job on the controller (which will also flush the buffer)
        comm.sendByteImmediately((byte) 0x04);

        // Work around for clearing the sent buffer size
        comm.softReset();

        // We will end up in an alarm state, clear the alarm
        killAlarmLock();
    }

    @Override
    protected void pauseStreamingEvent() throws Exception {
        comm.sendByteImmediately(TinyGUtils.COMMAND_PAUSE);
    }

    @Override
    protected void resumeStreamingEvent() throws Exception {
        comm.sendByteImmediately(TinyGUtils.COMMAND_RESUME);
    }

    @Override
    protected Boolean isIdleEvent() {
        return getControlState() == COMM_IDLE || getControlState() == COMM_CHECK;
    }

    @Override
    public void restoreParserModalState() {
        // no op
    }

    @Override
    protected void rawResponseHandler(String response) {
        JsonObject jo;

        try {
            jo = TinyGUtils.jsonToObject(response);
        } catch (Exception ignored) {
            // Some TinyG responses aren't JSON, those will end up here.
            //this.messageForConsole(response + "\n");
            return;
        }

        if (TinyGUtils.isRestartingResponse(jo)) {
            this.dispatchConsoleMessage(MessageType.INFO, "[restarting] " + response + "\n");
        } else if (TinyGUtils.isReadyResponse(jo)) {
            if (TinyGUtils.isTinyGVersion(jo)) {
                firmwareVersion = "TinyG " + TinyGUtils.getVersion(jo);
            }

            capabilities.addCapability(CapabilitiesConstants.JOGGING);
            capabilities.addCapability(CapabilitiesConstants.CONTINUOUS_JOGGING);
            capabilities.removeCapability(CapabilitiesConstants.FIRMWARE_SETTINGS);
            capabilities.removeCapability(CapabilitiesConstants.SETUP_WIZARD);

            setCurrentState(COMM_IDLE);
            dispatchConsoleMessage(MessageType.INFO, "[ready] " + response + "\n");

            try {
                comm.sendByteImmediately(TinyGUtils.COMMAND_ENQUIRE_STATUS);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (jo.has("ack")) {
            // TODO what do we do with ack=false, or if we don't get any response at all?
            dispatchConsoleMessage(MessageType.INFO, "[ack] " + response + "\n");
            sendInitCommands();
        } else if (TinyGUtils.isStatusResponse(jo)) {
            updateControllerStatus(jo);
            dispatchConsoleMessage(MessageType.INFO, response + "\n");
            checkStreamFinished();
        } else if (TinyGGcodeCommand.isOkErrorResponse(response)) {
            if (jo.get("r").getAsJsonObject().has(TinyGUtils.FIELD_STATUS_RESULT)) {
                updateControllerStatus(jo.get("r").getAsJsonObject());
                checkStreamFinished();
            } else if (rowsRemaining() > 0) {
                try {
                    commandComplete(response);
                } catch (Exception e) {
                    this.dispatchConsoleMessage(MessageType.ERROR, Localization.getString("controller.error.response")
                            + " <" + response + ">: " + e.getMessage());
                }
            }

            this.dispatchConsoleMessage(MessageType.INFO, response + "\n");
        } else if (TinyGGcodeCommand.isQueueReportResponse(response)) {
            LOGGER.log(Level.FINE, "Queue buffer usage: " + jo.get("qr").getAsString());
        } else {
            // Display any unhandled messages
            this.dispatchConsoleMessage(MessageType.INFO, "[unhandled message] " + response + "\n");
        }
    }

    private void updateControllerStatus(JsonObject jo) {
        // Save the old state
        ControllerState previousState = controllerStatus.getState();
        UGSEvent.ControlState previousControlState = getControlState(previousState);

        // Notify our listeners about the new status
        controllerStatus = TinyGUtils.updateControllerStatus(controllerStatus, jo);
        dispatchStatusString(controllerStatus);

        // Notify state change to our listeners
        UGSEvent.ControlState newControlState = getControlState(controllerStatus.getState());
        if (previousControlState.equals(newControlState)) {
            LOGGER.log(Level.FINE, "Changing state from " + previousControlState + " to " + newControlState);
            setCurrentState(newControlState);
        }
    }

    private void sendInitCommands() {
        // Enable JSON mode
        // 0=text mode, 1=JSON mode
        comm.queueStringForComm("{ej:1}");

        // JSON verbosity
        // 0=silent, 1=footer, 2=messages, 3=configs, 4=linenum, 5=verbose
        comm.queueStringForComm("{jv:4}");

        // Queue report verbosity
        // 0=off, 1=filtered, 2=verbose
        comm.queueStringForComm("{qv:0}");

        // Status report verbosity
        // 0=off, 1=filtered, 2=verbose
        comm.queueStringForComm("{sv:1}");

        // Request initial status report
        comm.queueStringForComm("{sr:n}");

        comm.streamCommands();

        // Refresh the status update
        setStatusUpdateRate(getStatusUpdateRate());
    }

    @Override
    public void performHomingCycle() throws Exception {
        throw new UnsupportedOperationException(NOT_SUPPORTED_YET);
    }

    @Override
    public void resetCoordinatesToZero() throws Exception {
        throw new UnsupportedOperationException(NOT_SUPPORTED_YET);
    }

    @Override
    public void returnToHome() throws Exception {
        if (controllerStatus.getWorkCoord().getZ() < 0) {
            sendCommandImmediately(new GcodeCommand("G90 G0 Z0"));
        }
        sendCommandImmediately(new GcodeCommand("G90 G0 X0 Y0"));
        sendCommandImmediately(new GcodeCommand("G90 G0 Z0"));
    }

    @Override
    public void killAlarmLock() throws Exception {
        sendCommandImmediately(new GcodeCommand(TinyGUtils.COMMAND_KILL_ALARM_LOCK));
    }

    @Override
    public void toggleCheckMode() throws Exception {
        throw new UnsupportedOperationException(NOT_SUPPORTED_YET);
    }

    @Override
    public void viewParserState() throws Exception {
        if (this.isCommOpen()) {
            sendCommandImmediately(new GcodeCommand(TinyGUtils.COMMAND_STATUS_REPORT));
        }
    }

    @Override
    public void updateParserModalState(GcodeCommand command) {
        if (command.getCommandString().startsWith("{\"gc")) {
            String gcode = StringUtils.substringBetween(command.getCommandString(), "{\"gc\":\"", "\"");
            GcodeCommand gcodeCommand = new GcodeCommand(gcode);
            super.updateParserModalState(gcodeCommand);
        }
    }

    @Override
    public void softReset() throws Exception {
        // TODO This doesn't work, it will disconnect from the host
        //this.comm.sendByteImmediately(TinyGUtils.COMMAND_RESET);

        this.comm.sendByteImmediately(TinyGUtils.COMMAND_KILL_JOB);
        this.comm.sendByteImmediately(TinyGUtils.COMMAND_QUEUE_FLUSH);
        this.comm.sendByteImmediately((byte) '\n');
        sendInitCommands();
    }

    @Override
    public void setWorkPosition(Axis axis, double position) throws Exception {
        throw new UnsupportedOperationException(NOT_SUPPORTED_YET);
    }

    @Override
    protected void isReadyToStreamCommandsEvent() throws Exception {
    }

    @Override
    protected void isReadyToSendCommandsEvent() throws Exception {
    }

    @Override
    protected void statusUpdatesEnabledValueChanged(boolean enabled) {
        // We don't care about this
    }

    @Override
    protected void statusUpdatesRateValueChanged(int rate) {
        // Status report interval in milliseconds (50ms minimum interval)
        comm.queueStringForComm("{si:" + rate + "}");
    }

    @Override
    public void sendOverrideCommand(Overrides command) throws Exception {
        throw new UnsupportedOperationException(NOT_SUPPORTED_YET);
    }

    @Override
    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    @Override
    public ControllerState getState() {
        if (controllerStatus == null) {
            return ControllerState.UNKNOWN;
        }

        return controllerStatus.getState();
    }

    @Override
    public UGSEvent.ControlState getControlState() {
        return getControlState(getState());
    }

    public UGSEvent.ControlState getControlState(ControllerState controllerState) {
        switch (controllerState) {
            case JOG:
            case RUN:
                return COMM_SENDING;
            case HOLD:
            case DOOR:
                return COMM_SENDING_PAUSED;
            case IDLE:
                if (isStreaming()) {
                    return COMM_SENDING_PAUSED;
                } else {
                    return COMM_IDLE;
                }
            case ALARM:
                return COMM_IDLE;
            case CHECK:
                if (isStreaming() && comm.isPaused()) {
                    return COMM_SENDING_PAUSED;
                } else if (isStreaming() && !comm.isPaused()) {
                    return COMM_SENDING;
                } else {
                    return COMM_CHECK;
                }
            default:
                return COMM_IDLE;
        }
    }
}
