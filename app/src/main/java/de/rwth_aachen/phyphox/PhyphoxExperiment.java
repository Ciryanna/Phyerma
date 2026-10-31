package de.rwth_aachen.phyphox;

import android.app.Activity;
import android.content.Context;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.camera.core.CameraControl;
import androidx.collection.ArraySet;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import de.rwth_aachen.phyphox.Bluetooth.Bluetooth;
import de.rwth_aachen.phyphox.Bluetooth.BluetoothInput;
import de.rwth_aachen.phyphox.Bluetooth.BluetoothOutput;
import de.rwth_aachen.phyphox.camera.CameraInput;
import de.rwth_aachen.phyphox.camera.VideoInput;
import de.rwth_aachen.phyphox.camera.depth.DepthInput;
import de.rwth_aachen.phyphox.NetworkConnection.NetworkConnection;

//This class holds all the information that makes up an experiment
//There are also some functions that the experiment should perform
public class PhyphoxExperiment implements Serializable, ExperimentTimeReference.Listener {
    int versionMinor;
    int versionMajor;

    boolean loaded = false; //Set to true if this instance holds a successfully loaded experiment
    boolean isLocal; //Set to true if this experiment was loaded from a local file. (if false, the experiment can be added to the library)
    public boolean isLink = false; //Set to true if this file is not an experiment but an entry pointing at a web page. Such a file has no views and is never run: opening it opens its first link (see Experiment.openLinkEntry).
    byte[] source = null; //This holds the original source file
    Set<String> resources = new ArraySet<>();
    String resourceFolder = null;
    long crc32 = 0;
    String message = ""; //Holds error messages
    String title = ""; //The title of this experiment
    String baseTitle = ""; //The title of this experiment without translations
    String stateTitle = ""; //The title of this experiment
    String category = ""; //The category of this experiment
    String baseCategory = ""; //The category of this experiment without translations
    String icon = ""; //The icon. This is either a base64-encoded drawable (typically png) or (if its length is 3 or less characters) it is a short form which should be used in a simple generated logo (like "gyr" for gyroscope). (The experiment list will use the first three characters of the title if this is completely empty)
    String description = "There is no description available for this experiment."; //A long text, explaining details about the experiment
    //A link to external documentation or similar stuff. The label identifies the link (it is the
    //key a translated link is matched on), displayText is what is actually shown to the user (the
    //translation attribute of the applied translation block or the label itself) and highlighted
    //links additionally show up in the experiment's menu.
    //(See translation-link-matching in phyphox-docs for the semantics implemented here.)
    public static class Link implements Serializable {
        public final String label;
        public final String displayText;
        public final String url;
        public final boolean highlighted;

        public Link(String label, String displayText, String url, boolean highlighted) {
            this.label = label;
            this.displayText = displayText;
            this.url = url;
            this.highlighted = highlighted;
        }
    }

    public List<Link> links = new ArrayList<>(); //This contains links to external documentation or similar stuff, with the selected translation block already applied

    //The subset of links that should show up in the experiment's menu, in declaration order
    public List<Link> getHighlightedLinks() {
        List<Link> highlighted = new ArrayList<>();
        for (Link link : links) {
            if (link.highlighted)
                highlighted.add(link);
        }
        return highlighted;
    }
    public Vector<ExpView> experimentViews = new Vector<>(); //Instances of the experiment views (see expView.java) that define the views for this experiment
    public ExperimentTimeReference experimentTimeReference; //This class holds the time of the first sensor event as a reference to adjust the sensor time stamp for all sensors to start at a common zero
    public Vector<SensorInput> inputSensors = new Vector<>(); //Instances of sensorInputs (see sensorInput.java) which are used in this experiment
    public DepthInput depthInput = null;
    public CameraInput cameraInput = null;
    public GpsInput gpsIn = null;
    public Vector<BluetoothInput> bluetoothInputs = new Vector<>(); //Instances of bluetoothInputs (see sensorInput.java) which are used in this experiment
    public Vector<BluetoothOutput> bluetoothOutputs = new Vector<>(); //Instances of bluetoothOutputs (see sensorInput.java) which are used in this experiment
    public final Vector<DataBuffer> dataBuffers = new Vector<>(); //Instances of dataBuffers (see dataBuffer.java) that are used to store sensor data, analysis results etc.
    public final Map<String, Integer> dataMap = new HashMap<>(); //This maps key names (string) defined in the experiment-file to the index of a dataBuffer
    public Vector<Analysis.AnalysisModule> analysis = new Vector<>(); //Instances of analysisModules (see analysis.java) that define all the mathematical processes in this experiment
    //Fair on purpose. Everything that reads buffer data takes this lock - the remote server's
    //get and export handlers, the exporter, the state writer - while the analysis and the sensor
    //callbacks take it over and over, several times per pass with no gap in between. A non-fair
    //lock lets a thread that re-acquires immediately barge past a waiter, so on a device slow
    //enough that the analysis never leaves a gap, a reader is starved indefinitely: the lab saw
    //every export of doppler fail on a Galaxy A3 while a 167-byte /get went unanswered for
    //minutes and /config, the one endpoint that touches no buffer, replied instantly. FIFO costs
    //a park/unpark per contended acquisition, which is nothing next to the few hundred
    //acquisitions a second this sees.
    public Lock dataLock = new ReentrantLock(true);

    double analysisSleep = 0.; //Pause between analysis cycles. At 0 analysis is done as fast as possible.
    DataBuffer analysisDynamicSleep = null;
    double lastAnalysis = 0.0; //This variable holds the system time of the moment the last analysis process finished. This is necessary for experiments, which do analysis after given intervals
    boolean analysisRan = false; //Whether an analysis pass has run since the experiment was opened or (re)started. The first run is exempt from the requireFill gate (spec/analysis.yml, decided 2026-08-24), and this cannot be read off lastAnalysis: that holds the experiment time, which is exactly zero as long as the experiment has never been started.
    double analysisTime; //This variable holds the experiment time of the moment the current analysis process started.
    double analysisLinearTime; //Same with the current system time
    boolean analysisOnUserInput = false; //Do the data analysis only if there is fresh input from the user.
    boolean newUserInput = true; //Will be set to true if the user changed any values
    DataBuffer requireFill = null; //Observe this buffer and only execute analysis cycle if this buffer has enough values
    int requireFillThreshold = 1; //Threshold fore 'requireBuffer'
    DataBuffer requireFillDynamic = null; //Instead of using the threshold, use the last value of another buffer to control 'requireFill'
    boolean newData = true; //Will be set to true if we have fresh data to present
    boolean recordingUsed = true; //This keeps track, whether the recorded data has been used, so the next call reading from the mic can clear the old data first

    int cycle = 0; //Keeps track of the current cycle for the cycles attribute of analysis modules

    boolean timedRun = false; //Timed run enabled?
    double timedRunStartDelay = 3.; //Start delay for timed runs
    double timedRunStopDelay = 10.; //Stop delay for timed runs

    //Audio output is handled in its own class, which will be instantiated by the file parser if required
    public AudioOutput audioOutput = null;

    //Parameters for audio record
    transient AudioRecord audioRecord = null; //Instance of AudioRecord. Not used if null.
    String micOutput; //The key name of the buffer which receives the data from audio recording.
    String micRateOutput; //The key name of the buffer which receives the sample rate of audio recording.
    int micRate = 48000; //The recording rate in Hz
    int micBufferSize = 0; //The size of the recording buffer
    int minBufferSize = 0; //The minimum buffer size requested by the device
    boolean appendAudioInput = false; //Append audio input on start of analysis cycle instead of replacing old data
    boolean forceAudioRecordingCompatibilityFormat = false; //Some Xiaomi device do not properly work with ENCODING_PCM_FLOAT if the Google Assistent voice trigger is enabled. This forces the use of the good old 16bit int format

    //Optional acoustic-sync audio track (see SyncAudioTrack). Off until the user enables it from
    //the experiment menu; the choice is persisted app-wide by the activity.
    public boolean syncRecording = false;
    transient public SyncAudioTrack syncTrack = null;
    public String syncTrackError = null; //Init/enable failure to surface to the user

    //Optional <video> input: records MP4 segments of the camera stream while measuring. It rides
    //the cameraInput's single camera session (created for it if the experiment declares video
    //without camera) - see camera/VideoInput and camera/analyzer/VideoEncoder.
    public VideoInput videoInput = null;

    //Parameters for flash light
    public FlashlightOutput flashlightOutput = null;
    //Network connections
    List<NetworkConnection> networkConnections = new ArrayList<>();

    public DataExport exporter; //An instance of the DataExport class for exporting functionality (see DataExport.java)

    //The constructor will just instantiate the DataExport. Everything else will be set directly by the phyphoxFile loading function (see phyphoxFile.java)
    PhyphoxExperiment() {
        exporter = new DataExport(this);
        experimentTimeReference = new ExperimentTimeReference(this);
    }

    public void onExperimentTimeReferenceUpdated(ExperimentTimeReference experimentTimeReference) {
        for (ExpView ev : experimentViews) {
            for (ExpView.expViewElement eve : ev.elements) {
                eve.onTimeReferenceUpdate(experimentTimeReference);
            }
        }
    }

    //Create a new buffer
    public DataBuffer createBuffer(String key, String clearGroup, int size, ExperimentTimeReference experimentTimeReference) {
        if (key == null)
            return null;

        DataBuffer output = new DataBuffer(key, clearGroup, size, experimentTimeReference);
        dataBuffers.add(output);
        dataMap.put(key, dataBuffers.size() - 1);
        return output;
    }

    //Helper function to get a dataBuffer by its key name
    public DataBuffer getBuffer(String key) {
        Integer index = this.dataMap.get(key);
        //Some sanity checks. To make this function more fail-safe
        if (index == null)
            return null;
        if (index >= dataBuffers.size())
            return null;

        return dataBuffers.get(index);
    }

    public String[] getClearGroups() {
        Set<String> clearGroups = new HashSet<String>();
        for (DataBuffer buffer : dataBuffers) {
            if (buffer.clearGroup != null && !buffer.clearGroup.equals("_"))
                clearGroups.add(buffer.clearGroup);
        }
        return clearGroups.stream()
                .sorted()
                .toArray(String[]::new);
    }

    //Do the export using the DataExport class (see DataExport.java)
    public void export(Activity c) {
        exporter.export(c, true);
    }

    //This function gets called in the main loop and takes care of any inputElements in the current experiment view
    public void handleInputViews(boolean measuring) {
        if (!loaded)
            return;
        if (dataLock.tryLock()) {
            try {
                for (ExpView ev : experimentViews) {
                        for (ExpView.expViewElement eve : ev.elements) {
                            try {
                                if (eve.onMayWriteToBuffers(this)) //The element may now write to its buffers if it wants to do it on its own...
                                    newUserInput = true;
                            } catch (Exception e) {
                                Log.e("handleInputViews", "Unhandled exception in view module (input) " + eve.toString() + " while sending data.", e);
                            }
                        }
                }
            } finally {
                dataLock.unlock();
            }
        }
        //else: Lock not aquired, but this is not urgent. Try another time instead of blocking the UI thread
        if (newUserInput && !measuring)
            processAnalysis(false);
    }

    //called by th main loop to initialize the analysis process
    public void processAnalysis(boolean measuring) {
        if (!loaded)
            return;

        //Send and receive network data if used
        for (NetworkConnection networkConnection : networkConnections) {
            dataLock.lock();
            try {
                networkConnection.pushDataToBuffers();
            } finally {
                dataLock.unlock();
            }
        }

        //Get the data from the audio recording if used
        if (audioRecord != null) {
            boolean sampleRateWritten = false;
            DataBuffer sampleRateBuffer = null;
            if (!micRateOutput.isEmpty())
                sampleRateBuffer = getBuffer(micRateOutput);

            if (measuring) {
                DataBuffer recording = getBuffer(micOutput);
                final int readBufferSize = Math.max(Math.min(recording.size, 4800), minBufferSize); //The dataBuffer for the recording
                float[] buffer = new float[readBufferSize]; //The temporary buffer to read to
                short[] oldBuffer = new short[readBufferSize]; //Used for <23 compatibility
                int bytesRead = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !forceAudioRecordingCompatibilityFormat)
                    bytesRead = audioRecord.read(buffer, 0, readBufferSize, AudioRecord.READ_NON_BLOCKING);
                else
                    bytesRead = audioRecord.read(oldBuffer, 0, readBufferSize);
                //The sync track taps the stream at the read site: every consumed frame passes
                //here exactly once, including the first read that the experiment deliberately
                //does not append, and regardless of per-cycle buffer clearing.
                if (syncTrack != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !forceAudioRecordingCompatibilityFormat)
                        syncTrack.tap(buffer, bytesRead);
                    else
                        syncTrack.tap(oldBuffer, bytesRead);
                }
                if (lastAnalysis != 0) { //The first recording data does not make sense, but we had to read it to clear the recording buffer...
                    dataLock.lock();
                    try {
                        if (recordingUsed) {
                            if (!appendAudioInput)
                                recording.clear(false); //We only want fresh data
                            if (sampleRateBuffer != null)
                                sampleRateBuffer.append(audioRecord.getSampleRate());
                            sampleRateWritten = true;
                            recordingUsed = false;
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !forceAudioRecordingCompatibilityFormat)
                            recording.append(buffer, bytesRead);
                        else
                            recording.append(oldBuffer, bytesRead);

                    } finally {
                        dataLock.unlock();
                    }
                }
            }
            if (!sampleRateWritten) {
                //Even if we do not use the first recording, we write the audio rate so it is available early.
                dataLock.lock();
                try {
                    if (sampleRateBuffer != null)
                        sampleRateBuffer.append(audioRecord.getSampleRate());
                } finally {
                    dataLock.unlock();
                }
            }
        }

        Double sleep = analysisSleep;
        if (analysisDynamicSleep != null && !Double.isNaN(analysisDynamicSleep.value) && !Double.isInfinite(analysisDynamicSleep.value)) {
            sleep = analysisDynamicSleep.value;
        }

        //Check if the actual math should be done
        if (!newUserInput) {
            if (analysisOnUserInput) {
                //If set by the experiment, the analysis is only done when there is new input from the user
                return; //No new input. Nothing to do.
            } else if (measuring && experimentTimeReference.getExperimentTime() - lastAnalysis <= sleep) {
                //This is the default: The analysis is done periodically. Either as fast as possible or after a period defined by the experiment
                return; //Too soon. Nothing to do
            }
        }

        newUserInput = false;

        if (measuring) {
            for (SensorInput sensor : inputSensors)
                sensor.updateGeneratedRate();
        } else
            cycle = 0;

        if (requireFill != null && analysisRan) {
            int threshold = requireFillThreshold;
            if (requireFillDynamic != null && requireFillDynamic.getFilledSize() > 0)
                threshold = (int)requireFillDynamic.value;
            if (requireFill.getFilledSize() < threshold) {
                return;
            }
        }

        dataLock.lock();
        analysisTime = experimentTimeReference.getExperimentTime();
        analysisLinearTime = experimentTimeReference.getLinearTime();

        //Call all the analysis modules and let them do their work.
        try {
            for (Analysis.AnalysisModule mod : analysis) {
                try {
                    Thread.yield();
                    mod.updateIfNotStatic(cycle);
                } catch (Exception e) {
                    Log.e("processAnalysis", "Unhandled exception in analysis module " + mod.toString() + ".", e);
                }
            }
        } finally {
            dataLock.unlock();
        }
        cycle++;


        //Play audio
        if (measuring && audioOutput != null) {
            audioOutput.play();
        }

        //Control flashlight
        if (flashlightOutput != null) {
            flashlightOutput.updateState();
        }

        if (measuring) {
            //Send the results to the bluetooth outputs (if used)
            for (BluetoothOutput btOut : bluetoothOutputs) {
                dataLock.lock();
                try {
                    btOut.sendData();
                } finally {
                    dataLock.unlock();
                }
            }
        }

        //Send and receive network data if used
        for (NetworkConnection networkConnection : networkConnections) {
            dataLock.lock();
            try {
                networkConnection.doExecute();
                networkConnection.pushDataToBuffers();
            } finally {
                dataLock.unlock();
            }
        }

        recordingUsed = true;
        newData = true; //We have fresh data to present.
        lastAnalysis = experimentTimeReference.getExperimentTime(); //Remember when we were done this time
        analysisRan = true; //A run that was gated above does not get here, so this marks a pass that actually happened
    }

    //called by the main loop after everything is processed. Here we have to send all the analysis results to the appropriate views
    public boolean updateViews(int currentView, boolean force) {
        if (!loaded)
            return true;
        if (!(newData || force)) //New data to present? If not: Nothing to do, unless an update is forced
            return true;

        try {
            if (dataLock.tryLock(10, TimeUnit.MILLISECONDS)) {
                try {
                    for (ExpView experimentView : experimentViews) {
                        for (ExpView.expViewElement eve : experimentView.elements) {
                            eve.onMayReadFromBuffers(this); //Notify each view, that it should update from the buffers
                        }
                    }
                } finally {
                    dataLock.unlock();
                }
            } else
                return false; //This is not urgent. Try another time instead of blocking the UI thread!
        } catch (Exception e) {
            Log.d("updateViews", e.getMessage());
            return false;
        }

        newData = false;
        //Finally call dataComplete on every view to notify them that the data has been sent - heavy operation can now be done by the views while the buffers have been unlocked again
        for (ExpView.expViewElement eve : experimentViews.elementAt(currentView).elements) {
            try {
                eve.dataComplete();
            } catch (Exception e) {
                Log.e("updateViews", "Unhandled exception in view module " + eve.toString() + " on data completion.", e);
            }
        }
        return true;
    }

    //Helper to stop all I/O of this experiment (i.e. when it should be stopped)
    public void stopAllIO() {
        if (!loaded)
            return;

        ExperimentTimeReference.TimeMapping event = experimentTimeReference.getLastMapping();
        if (event == null || event.event != ExperimentTimeReference.TimeMappingEvent.CLEAR)
            experimentTimeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.PAUSE);
        event = experimentTimeReference.getLastMapping();
        lastAnalysis = 0.0;
        //analysisRan is deliberately NOT reset here. Stopping does not exempt anything: the ruled
        //semantics exempt the first run after opening or after STARTING, and startAllIO does that
        //reset. Disarming the gate at stop instead armed it for the paused passes that follow a
        //stop (handleInputViews runs one whenever there is user input while not measuring) - and
        //those run with the analysis inputs already consumed, so every module with a non-append
        //output overwrote its results with nothing. The recorded data was gone and the export
        //that followed held only headers.

        //Recording
        if (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED)
            audioRecord.stop();
        //Sync track: end the current segment while the paused experiment time still stands
        if (syncTrack != null)
            syncTrack.endSegment();
        //Playback
        if (audioOutput != null) {
            audioOutput.stop();
        }
        //Flashlight
        if (flashlightOutput != null) {
            flashlightOutput.stop();
        }
        //Sensors
        for (SensorInput sensor : inputSensors)
            sensor.stop();

        if (gpsIn != null)
            gpsIn.stop();

        if (depthInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            depthInput.stop();

        if (cameraInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            cameraInput.stop();


        for (NetworkConnection networkConnection : networkConnections)
            networkConnection.stop();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            //Bluetooth
            Map<String, Bluetooth> uniqueBluetoothDevices = new HashMap<>();
            for (BluetoothInput bti : bluetoothInputs) {
                bti.stop();
                uniqueBluetoothDevices.put(bti.idString != null && !bti.idString.isEmpty() ? bti.idString : bti.deviceAddress, bti);
            }
            for (BluetoothOutput bto : bluetoothOutputs) {
                bto.stop();
                uniqueBluetoothDevices.put(bto.idString != null && !bto.idString.isEmpty() ? bto.idString : bto.deviceAddress, bto);
            }
            for (Bluetooth b : uniqueBluetoothDevices.values()) {
                b.writeEventCharacteristic(event);
            }
        }

    }


    //Helper to start all I/O of this experiment (i.e. when it should be started)
    public void startAllIO() throws Bluetooth.BluetoothException, DepthInput.DepthInputException {

        if (!loaded)
            return;

        experimentTimeReference.registerEvent(ExperimentTimeReference.TimeMappingEvent.START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            ExperimentTimeReference.TimeMapping event = experimentTimeReference.getLastMapping();
            Map<String, Bluetooth> uniqueBluetoothDevices = new HashMap<>();
            for (BluetoothInput bti : bluetoothInputs) {
                uniqueBluetoothDevices.put(bti.idString != null && !bti.idString.isEmpty() ? bti.idString : bti.deviceAddress, bti);
            }
            for (BluetoothOutput bto : bluetoothOutputs) {
                uniqueBluetoothDevices.put(bto.idString != null && !bto.idString.isEmpty() ? bto.idString : bto.deviceAddress, bto);
            }
            for (Bluetooth b : uniqueBluetoothDevices.values()) {
                b.writeEventCharacteristic(event);
            }
        }

        newUserInput = true; //Set this to true to execute analysis at least ones with default values.
        analysisRan = false; //The first run after starting is exempt from the requireFill gate as well - the passes that ran while the experiment was merely open must not arm it, or an input that only delivers data once the analysis has run (audio recording) would wait for a run that waits for its data.

        for (SensorInput sensor : inputSensors)
            sensor.start();

        if (gpsIn != null)
            gpsIn.start();

        if (depthInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            depthInput.start();

        if (cameraInput != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            cameraInput.start();



        //Playback
        if (audioOutput != null) {
            audioOutput.start(false);
        }

        if (flashlightOutput != null) {
            flashlightOutput.start();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            for (BluetoothInput bti : bluetoothInputs) {
                bti.start();
            }
            for (BluetoothOutput btO : bluetoothOutputs) {
                btO.start();
            }
        }

        for (NetworkConnection networkConnection : networkConnections)
            networkConnection.start();

        //Audio Recording
        if (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED)
            audioRecord.startRecording();

        //Sync track: open a new recording segment for this start. The flag decides, not the
        //track's presence: a disabled track keeps its recorded segments for export.
        if (syncRecording && syncTrack != null)
            syncTrack.beginSegment();

        //We will not start audio output here as it will be triggered by the analysis modules.
    }

    public void init(SensorManager sensorManager, LocationManager locationManager) throws Exception {
        //Update all the views
        for (int i = 0; i < experimentViews.size(); i++) {
            updateViews(i, true);
        }

        //Initialize audio output
        if (audioOutput != null)
            audioOutput.init();

        //Create audioTrack instance
        if (micBufferSize > 0) {
            //When the sync track taps this stream, its throughput is bound to the analysis loop.
            //Give the loop at least a second of HAL-side buffering so a heavy analysis cycle
            //cannot silently overrun the capture before the next read.
            if (syncRecording && micBufferSize < micRate)
                micBufferSize = micRate;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !forceAudioRecordingCompatibilityFormat) {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, micRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, micBufferSize * 2);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    //Some devices and custom ROMs advertise ENCODING_PCM_FLOAT but cannot
                    //actually record with it. Fall back to the 16bit compatibility format,
                    //which also switches the recording code to the corresponding read calls.
                    audioRecord.release();
                    audioRecord = null;
                    forceAudioRecordingCompatibilityFormat = true;
                    Log.w("PhyphoxExperiment", "Could not initialize audio recording with ENCODING_PCM_FLOAT. Falling back to ENCODING_PCM_16BIT.");
                }
            }
            if (audioRecord == null) {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, micRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize * 2);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release();
                    audioRecord = null;
                    throw new Exception("Could not initialize audio recording at a rate of " + micRate + " Hz.");
                }
            }
        }

        //Wire up the sync audio track now that the experiment's audio situation is known: a
        //fresh AudioRecord means the stream is shared, otherwise the track captures on its own.
        //A track without syncRecording (disabled but still holding a session for export) gets
        //the shared reference refreshed but is not re-initialized.
        if (syncTrack != null) {
            if (audioRecord != null)
                syncTrack.attachShared(audioRecord);
            else if (syncRecording) {
                try {
                    syncTrack.initOwn();
                } catch (SyncAudioTrack.SyncAudioTrackException e) {
                    syncTrackError = e.getMessage();
                }
            }
        }

        //Reconnect sensors
        for (SensorInput si : inputSensors) {
            si.attachSensorManager(sensorManager);
        }

        if (gpsIn != null) {
            gpsIn.attachLocationManager(locationManager);
        }

    }

    //Turn the optional sync audio track on. The track object is created here; the capture stream
    //is wired immediately for experiments without an <audio> input, and deferred to init() (which
    //creates that record) for experiments that have one. Returns false if the hardware refused.
    public boolean enableSync(Context ctx) {
        if (syncTrack == null)
            syncTrack = new SyncAudioTrack(experimentTimeReference, new File(ctx.getFilesDir(), "sync"));
        syncRecording = true;
        syncTrackError = null;

        if (audioRecord != null)
            syncTrack.attachShared(audioRecord);
        else if (micBufferSize == 0) {
            //micBufferSize > 0 means the experiment declared <audio>: init() attaches the record
            //once it exists. Only a genuinely audio-less experiment needs our own capture.
            try {
                syncTrack.initOwn();
            } catch (SyncAudioTrack.SyncAudioTrackException e) {
                syncTrackError = e.getMessage();
                syncRecording = false;
                return false;
            }
        }

        //Enabling while a measurement is already running starts recording immediately.
        ExperimentTimeReference.TimeMapping last = experimentTimeReference.getLastMapping();
        if (last != null && last.event == ExperimentTimeReference.TimeMappingEvent.START && syncTrack.isUsable())
            syncTrack.beginSegment();
        return true;
    }

    //Turn the sync audio track off. Segments recorded so far stay around for export.
    public void disableSync() {
        syncRecording = false;
        if (syncTrack != null)
            syncTrack.pauseCapture();
    }

    public void writeStateFileAsync(String customTitle, OutputStream os, Experiment.WriteStateFileCallback writeStateFileCallback){

        ExecutorService stateWriterExecutor = Executors.newSingleThreadExecutor();

        Handler mainThreadHandler = new Handler(Looper.getMainLooper());

        stateWriterExecutor.execute(() -> {
            String result = null;

            try {
                result = writeStateFile(customTitle, os);
            } catch (Exception e){
                result = e.getMessage();
            } finally {
                try {
                    os.close();
                } catch (IOException e){
                    e.printStackTrace();
                    if (result == null)
                        result = "Failed to close stream: " +e.getMessage();
                }
            }

            String finalResult = result;

            mainThreadHandler.post(() -> {
                if (finalResult == null)
                    writeStateFileCallback.onSuccess();
                else
                    writeStateFileCallback.onError(finalResult);
            });

            stateWriterExecutor.shutdown();

        });

    }

    //Package-private rather than private so StateFileWriterTest can drive it directly - the
    //async wrapper above would only add an executor and a callback to the test.
    String writeStateFile(String customTitle, OutputStream os) {

        if (source == null)
            return "Source is null.";
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db;
        try {
            db = dbf.newDocumentBuilder();
        } catch (Exception e) {
            return "Could not create DocumentBuilder: " + e.getMessage();
        }

        Document doc;
        InputStream is = new ByteArrayInputStream(source);
        try {
            doc = db.parse(is);
        } catch (Exception e) {
            return "Could not parse source: " + e.getMessage();
        }

        Element root = doc.getDocumentElement();
        if (root == null)
            return "Source has no root.";

        root.normalize();

        //Drop the metadata of a previous save before appending fresh elements below. Collect
        //first and remove afterwards: getChildNodes() is a live NodeList, so removing item i
        //shifts the next sibling into index i and the loop would skip it. The elements below
        //are appended back-to-back, so that skip left a stale element behind on every re-save
        //(a saved state carries exactly one state-title - the writer replaces, never
        //accumulates; a duplicate makes the file unloadable on iOS).
        NodeList children = root.getChildNodes();
        List<Node> obsolete = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            String name = children.item(i).getNodeName();
            if (name.equals("state-title") || name.equals("color") || name.equals("events"))
                obsolete.add(children.item(i));
        }
        for (Node node : obsolete)
            root.removeChild(node);

        Element customTitleEl = doc.createElement("state-title");
        customTitleEl.setTextContent(customTitle);
        root.appendChild(customTitleEl);

        Element colorEl = doc.createElement("color");
        colorEl.setTextContent("blue");
        root.appendChild(colorEl);

        Element eventsEl = doc.createElement("events");
        for (ExperimentTimeReference.TimeMapping event : experimentTimeReference.getTimeMappings()) {

            Element eventEl = doc.createElement(event.event.name().toLowerCase());
            eventEl.setAttribute("experimentTime", event.experimentTime.toString());
            eventEl.setAttribute("systemTime", Long.toString(event.systemTime));
            eventsEl.appendChild(eventEl);
        }
        root.appendChild(eventsEl);

        NodeList containers = root.getElementsByTagName("data-containers");
        if (containers.getLength() != 1)
            return "Source needs exactly one data-container block.";

        NodeList buffers = containers.item(0).getChildNodes();

        DecimalFormat format = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
        format.applyPattern("0.#########E0");
        DecimalFormatSymbols dfs = format.getDecimalFormatSymbols();
        dfs.setDecimalSeparator('.');
        format.setDecimalFormatSymbols(dfs);
        format.setGroupingUsed(false);

        for (int i = 0; i < buffers.getLength(); i++) {
            if (!buffers.item(i).getNodeName().equals("container"))
                continue;

            DataBuffer buffer = getBuffer(buffers.item(i).getTextContent());
            if (buffer == null)
                continue;

            Attr attr = doc.createAttribute("init");

            //Under the data lock like every other reader of a buffer: this runs on its own
            //thread while the analysis keeps writing, and copying a list that grows underneath
            //throws (see DataExport.collectData).
            Double[] values;
            dataLock.lock();
            try {
                values = buffer.getArray();
            } finally {
                dataLock.unlock();
            }

            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (double v : values) {
                if (first)
                    first = false;
                else
                    sb.append(",");
                sb.append(format.format(v));
            }

            attr.setValue(sb.toString());

            buffers.item(i).getAttributes().setNamedItem(attr);
        }

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t;
        try {
            t = tf.newTransformer();
        } catch (Exception e) {
            return "Could not create transformer: " + e.getMessage();
        }

        DOMSource domSource = new DOMSource(doc);


        StreamResult streamResult = new StreamResult(os);
        try {
            t.transform(domSource, streamResult);
        } catch (Exception e) {
            return "Transform failed: " + e.getMessage();
        }

        return null;
    }
}
