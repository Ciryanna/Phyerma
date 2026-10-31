package de.rwth_aachen.phyphox;

import static de.rwth_aachen.phyphox.helper.DataExportUtility.MIME_TYPE_CSV_ZIP;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.rwth_aachen.phyphox.helper.DataExportUtility;
import de.rwth_aachen.phyphox.helper.FileNameFormat;

//The DataExport class provides export functionality for a phyphoxExperiment.
//There is exactly one export format: a zip with one plain CSV per export set (comma separated,
//decimal point), a meta/ folder describing the device and the run, and - when the experiment
//recorded them - the sync/ audio and video/ files that belong to the same run.
public class DataExport implements Serializable {

    private static final char SEPARATOR = ',';

    //ExportSet class
    //An export set is a collection of related dataBuffers, which (ideally) have the same size
    //exportSets are defined for each experiments and represent logical subsets of all dataBuffers which the user might want to export
    public class ExportSet implements Serializable {
        String name;

        //This class maps dataBuffers (by their key name) to a name in this ExportSet
        protected class SourceMapping implements Serializable {
            String name;
            String source;

            SourceMapping(String name, String source) {
                this.name = name;
                this.source = source;
            }

            String getSecureName() {
                //Returns a sanitized version of name to prevent creating malicious data files through phyphox.

                //Prevent spreadsheet formula injection (seriously, in case of csv, Excel should not be executing anyways, but let's protect the users...)
                if (name.startsWith("=") || name.startsWith("+") || name.startsWith("-") || name.startsWith("@")) {
                    return "'" + name;
                }

                return name;
            }
        }

        //The set consists of an arbitrary number of sourceMappings. So each entry in the dataSet has a name and a dataBuffer-source
        Vector<SourceMapping> sources = new Vector<>();

        //We will also hold an array with all the data. The idea is to let all dataSets collect their
        // data as fast as possible (like a snapshot) and then take care of pushing the data to the
        // zip writer. So after instantiating an ExportSet (with a name), addSource is called for
        // each dataBuffer that should be added to the collection. Then getData is called so the
        // content of these buffers is collected and finally the dataSet is written to the export.
        Double[][] data;

        //constructor with name for this set
        ExportSet(String name) {
            this.name = name;
        }

        //Add dataBuffers with names to this set
        public void addSource(String name, String source) {
            this.sources.add(new SourceMapping(name, source));
        }

        //How many rows this set exports: as many as its longest column holds, with the shorter
        //ones padded out (ruled 2026-08-25, export-set-row-count in phyphox-docs). Sizing by the
        //first column instead truncated every longer column, and dropped the whole set whenever
        //the first container happened to be empty while the others held data.
        int rowCount() {
            int rows = 0;
            for (Double[] column : data)
                rows = Math.max(rows, column.length);
            return rows;
        }

        //Retrieve all data from the dataBuffers
        public void getData() {
            data = new Double[sources.size()][];
            for (int i = 0; i < sources.size(); i++) {
                DataBuffer buffer = experiment.getBuffer(sources.get(i).source); //Get the buffer for this source
                data[i] = buffer.getArray(); //Get all data as a double array
            }
        }
    }

    private PhyphoxExperiment experiment; //The phyphoxExperiment which uses this DataExport
    public List<ExportSet> exportSets = new ArrayList<>(); //The available export sets
    private transient volatile boolean exporting = false; //An export zip is being built in the background

    //The constructor just has to store a reference to the experiment
    DataExport(PhyphoxExperiment experiment) {
        this.experiment = experiment;
    }

    //Add an ExportSet to this exporter
    public void addSet(ExportSet set) {
        this.exportSets.add(set);
    }

    //Is there anything an export could contain? Sets, or recorded video / sync audio.
    public boolean hasContent() {
        return !exportSets.isEmpty()
                || (experiment.videoInput != null && experiment.videoInput.hasData())
                || (experiment.syncTrack != null && experiment.syncTrack.hasData());
    }

    public String getFilename(String fileNameBase) {
        return fileNameBase + ".zip";
    }

    public String getType() {
        return MIME_TYPE_CSV_ZIP;
    }

    //Writes the export zip. Any failure deletes the partial file and returns null: a truncated
    //zip must never be handed to the user as if it were a complete export.
    protected File buildZip(List<ExportSet> sets, File exportPath, String fileName, boolean withMedia, Context ctx) {
        File file = new File(exportPath, getFilename(fileName));
        try (ZipOutputStream zstream = new ZipOutputStream(new FileOutputStream(file))) {
            DecimalFormat format = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
            format.applyPattern("0.000000000E0");
            DecimalFormatSymbols dfs = format.getDecimalFormatSymbols();
            dfs.setDecimalSeparator('.');
            format.setDecimalFormatSymbols(dfs);
            format.setGroupingUsed(false);

            writeSets(zstream, sets, format);
            writeMeta(zstream, format, dfs, ctx);
            if (withMedia)
                writeMedia(zstream);
        } catch (Exception e) {
            Log.e("export", "Export failed.", e);
            file.delete();
            return null;
        }
        return file;
    }

    private void writeSets(ZipOutputStream zstream, List<ExportSet> sets, DecimalFormat format) throws IOException {
        for (ExportSet set : sets) { // For each dataset...
            zstream.putNextEntry(new ZipEntry(set.name + ".csv")); //...create a file and add it to the zip

            //Contruct the table header in the first line
            StringBuilder header = new StringBuilder();
            for (int j = 0; j < set.data.length; j++) { //Each column gets a name...
                header.append("\"").append(set.sources.get(j).getSecureName()).append("\"");
                if (j < set.data.length - 1)
                    header.append(SEPARATOR);
            }
            header.append("\n");
            zstream.write(header.toString().getBytes(StandardCharsets.UTF_8));

            //Then add all the data
            int rows = set.rowCount();
            for (int i = 0; i < rows; i++) { //For each row of data - as many as the longest column holds
                StringBuilder data = new StringBuilder();
                for (int j = 0; j < set.data.length; j++) { //For each column within this row
                    if (i < set.data[j].length) //Do we have data for this cell?
                        data.append(format.format(set.data[j][i])); //Add it to the row
                    else
                        data.append("NaN"); //No data? Enter NaN in the row
                    if (j < set.data.length - 1)
                        data.append(SEPARATOR);
                }
                data.append("\n");
                zstream.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }

            zstream.closeEntry(); //This dataset is complete. Close its file within the zip
        }
    }

    private void writeMeta(ZipOutputStream zstream, DecimalFormat format, DecimalFormatSymbols dfs, Context ctx) throws IOException {
        zstream.putNextEntry(new ZipEntry("meta/device.csv"));
        zstream.write(("\"property\"" + SEPARATOR + "\"value\"\n").getBytes(StandardCharsets.UTF_8));

        StringBuilder data = new StringBuilder();
        for (Metadata.DeviceMetadata deviceMetadata : Metadata.DeviceMetadata.values()) {
            if (deviceMetadata == Metadata.DeviceMetadata.sensorMetadata || deviceMetadata == Metadata.DeviceMetadata.uniqueID || deviceMetadata == Metadata.DeviceMetadata.camera2api || deviceMetadata == Metadata.DeviceMetadata.camera2apiFull)
                continue;
            String identifier = deviceMetadata.toString();
            data.append("\"").append(identifier).append("\"").append(SEPARATOR);
            data.append("\"").append(new Metadata(identifier, ctx).get("")).append("\"").append("\n");
        }
        for (SensorInput.SensorName sensor : Metadata.sensorsWithMetadata()) {
            for (Metadata.SensorMetadata sensorMetadata : Metadata.SensorMetadata.values()) {
                String identifier = sensorMetadata.toString();
                data.append("\"").append(sensor.name()).append(" ").append(identifier).append("\"").append(SEPARATOR);
                data.append("\"").append(new Metadata(sensor.name() + identifier, ctx).get("")).append("\"").append("\n");
            }
        }
        zstream.write(data.toString().getBytes(StandardCharsets.UTF_8));
        zstream.closeEntry();

        zstream.putNextEntry(new ZipEntry("meta/time.csv"));
        zstream.write(("\"event\"" + SEPARATOR + "\"experiment time\"" + SEPARATOR + "\"system time\"" + SEPARATOR + "\"system time text\"\n").getBytes(StandardCharsets.UTF_8));

        DecimalFormat longformat = (DecimalFormat) NumberFormat.getInstance(Locale.ENGLISH);
        longformat.applyPattern("############0.000");
        longformat.setDecimalFormatSymbols(dfs);
        longformat.setGroupingUsed(false);

        data = new StringBuilder();
        SimpleDateFormat dateFormat;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'XXX");
        else
            dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'Z");
        for (ExperimentTimeReference.TimeMapping timeMapping : experiment.experimentTimeReference.getTimeMappings()) {
            data.append("\"").append(timeMapping.event.name()).append("\"").append(SEPARATOR);
            data.append(format.format(timeMapping.experimentTime)).append(SEPARATOR);
            data.append(longformat.format(timeMapping.systemTime / 1000.)).append(SEPARATOR);
            data.append("\"").append(dateFormat.format(timeMapping.systemTime)).append("\"").append("\n");
        }
        zstream.write(data.toString().getBytes(StandardCharsets.UTF_8));
        zstream.closeEntry();
    }

    private void writeMedia(ZipOutputStream zstream) throws IOException {
        //The acoustic-sync track (if the user opted in) ships next to the data:
        //sync/audio_<i>.wav per recording segment plus sync/sync.json with the
        //monotonic-clock anchors that map each sample onto experiment time.
        if (experiment.syncTrack != null && experiment.syncTrack.hasData())
            experiment.syncTrack.writeToZip(zstream, BuildConfig.VERSION_NAME);

        //Recorded <video> segments ship as video/video_<i>.mp4 plus a video.json
        //sidecar. The MP4s can be large; the zip is streamed, so nothing extra is
        //held in memory beyond the copy buffer.
        if (experiment.videoInput != null && experiment.videoInput.hasData())
            experiment.videoInput.writeToZip(zstream, BuildConfig.VERSION_NAME);
    }

    //Take the snapshot every export starts from. The buffers are written by the analysis and
    //sensor threads while this runs, so it happens under the experiment's data lock: without it
    //a buffer can grow between the moment its size is read and the moment it is copied, and
    //LinkedList.toArray walks off the end of the array it just sized (seen as a 500 on /export
    //of a running experiment, ArrayIndexOutOfBoundsException from DataBuffer.getArray). Locking
    //once around all sets also makes the sets consistent with each other, which is what a
    //snapshot is supposed to be.
    private void collectData() {
        experiment.dataLock.lock();
        try {
            for (int i = 0; i < exportSets.size(); i++) {
                exportSets.get(i).getData();
            }
        } finally {
            experiment.dataLock.unlock();
        }
    }

    //One-tap export: snapshot the data now, build the zip off the UI thread (video can make it
    //large) and hand it to the system share sheet. withMedia=false limits the zip to the data
    //and meta/ - used by the single-graph export, which has no business shipping the video.
    public void export(final Activity c, final boolean withMedia) {
        if (exporting)
            return;

        collectData();

        final String fileName = FileNameFormat.formatFilename(c, experiment.title, experiment.experimentTimeReference);
        final List<ExportSet> sets = new ArrayList<>(exportSets);

        final boolean hasMedia = withMedia && ((experiment.videoInput != null && experiment.videoInput.hasData())
                || (experiment.syncTrack != null && experiment.syncTrack.hasData()));
        if (hasMedia)
            Toast.makeText(c, R.string.export_preparing, Toast.LENGTH_SHORT).show();
        if (withMedia && experiment.videoInput != null && experiment.videoInput.isRecording())
            Toast.makeText(c, R.string.export_video_in_progress, Toast.LENGTH_LONG).show();

        exporting = true;
        new Thread(() -> {
            final File file = buildZip(sets, c.getCacheDir(), fileName, withMedia, c);
            c.runOnUiThread(() -> {
                exporting = false;
                if (c.isFinishing() || c.isDestroyed())
                    return;
                if (file == null)
                    Toast.makeText(c, R.string.export_failed, Toast.LENGTH_LONG).show();
                else
                    DataExportUtility.shareFile(file, getType(), c);
            });
        }, "DataExport").start();
    }

    //This function allows to export the data without dialogs. It is used when the export is
    //   triggered from the web interface, which downloads the resulting file in the browser.
    //Returns null if the export could not be written.
    protected File exportDirect(File cacheDir, boolean withMedia, final String fileName, Context ctx) {
        collectData();

        return buildZip(exportSets, cacheDir, fileName, withMedia, ctx);
    }

}
