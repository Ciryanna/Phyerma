package de.rwth_aachen.phyphox.ai;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;

//Where the AI tab reads and writes experiments: the user's own files in the app's private
//directory (the same <uuid>.phyphox files the experiment list shows). The built-in experiments in
//the assets are read-only, so changing one saves a copy.
public final class ExperimentStore {
    private static final String BACKUP_DIR = "ai_backup";

    private ExperimentStore() {}

    //An experiment that can be picked as the target of a change
    public static final class Ref {
        public final String title;
        //File name in filesDir, or the asset name below experiments/
        public final String file;
        public final boolean asset;

        Ref(String title, String file, boolean asset) {
            this.title = title;
            this.file = file;
            this.asset = asset;
        }
    }

    //One write, remembered so that it can be taken back
    public static final class Change {
        public final File file;
        //true if the write created the file (undo deletes it), false if it replaced an existing one
        public final boolean created;
        private final File backup;
        private final File movedFrom, movedTo;

        Change(File file, boolean created, File backup, File movedFrom, File movedTo) {
            this.file = file;
            this.created = created;
            this.backup = backup;
            this.movedFrom = movedFrom;
            this.movedTo = movedTo;
        }

        public void undo() throws IOException {
            if (created) {
                if (file.exists() && !file.delete())
                    throw new IOException("Could not remove " + file.getName());
                return;
            }
            writeAtomically(file, readAll(new FileInputStream(backup)));
            backup.delete();
            if (movedFrom != null && movedTo.exists() && !movedFrom.exists())
                movedTo.renameTo(movedFrom);
        }
    }

    //The user's own experiments first, then the built-in ones, each group sorted by title
    public static List<Ref> list(Context context) {
        List<Ref> local = new ArrayList<>();
        File[] files = context.getFilesDir().listFiles((dir, name) -> name.endsWith(".phyphox"));
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory())
                    continue;
                try (InputStream in = new FileInputStream(f)) {
                    local.add(new Ref(titleOf(in, f.getName()), f.getName(), false));
                } catch (Exception ignored) {
                }
            }
        }
        List<Ref> assets = new ArrayList<>();
        try {
            String[] names = context.getAssets().list("experiments");
            if (names != null) {
                for (String name : names) {
                    if (!name.endsWith(".phyphox"))
                        continue;
                    try (InputStream in = context.getAssets().open("experiments/" + name)) {
                        assets.add(new Ref(titleOf(in, name), name, true));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        Collator collator = Collator.getInstance();
        Collections.sort(local, (a, b) -> collator.compare(a.title, b.title));
        Collections.sort(assets, (a, b) -> collator.compare(a.title, b.title));
        local.addAll(assets);
        return local;
    }

    //The title of an experiment file; the fallback if it has none or cannot be read
    static String titleOf(InputStream in, String fallback) {
        try {
            XmlPullParser xpp = Xml.newPullParser();
            xpp.setInput(in, null);
            int depth = 0;
            for (int event = xpp.getEventType(); event != XmlPullParser.END_DOCUMENT; event = xpp.next()) {
                if (event == XmlPullParser.START_TAG) {
                    depth++;
                    if (depth == 2 && xpp.getName().equalsIgnoreCase("title")) {
                        String title = xpp.nextText().trim();
                        return title.isEmpty() ? fallback : title;
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    depth--;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static String read(Context context, Ref ref) throws IOException {
        InputStream in = ref.asset
                ? context.getAssets().open("experiments/" + ref.file)
                : new FileInputStream(new File(context.getFilesDir(), ref.file));
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    //Save as a new experiment in the user's list
    public static Change create(Context context, String xml) throws IOException {
        File file = new File(context.getFilesDir(), UUID.randomUUID().toString().replace("-", "") + ".phyphox");
        writeAtomically(file, xml.getBytes(StandardCharsets.UTF_8));
        return new Change(file, true, null, null, null);
    }

    //Replace one of the user's own experiments. The old file is kept for undo(). The folder with its
    //resources (images ...) is named after the CRC32 of the file, so it has to follow the new content.
    public static Change replace(Context context, Ref target, String xml) throws IOException {
        if (target.asset)
            throw new IOException("Built-in experiments are read-only");
        File file = new File(context.getFilesDir(), target.file);
        byte[] old = readAll(new FileInputStream(file));
        byte[] updated = xml.getBytes(StandardCharsets.UTF_8);

        File backupDir = new File(context.getFilesDir(), BACKUP_DIR);
        if (!backupDir.isDirectory() && !backupDir.mkdirs())
            throw new IOException("Could not create the backup folder");
        File backup = new File(backupDir, target.file);
        writeAtomically(backup, old);
        writeAtomically(file, updated);

        File from = new File(context.getFilesDir(), crcName(old));
        File to = new File(context.getFilesDir(), crcName(updated));
        if (from.isDirectory() && !to.exists() && from.renameTo(to))
            return new Change(file, false, backup, from, to);
        return new Change(file, false, backup, null, null);
    }

    private static String crcName(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return Long.toHexString(crc.getValue()).toLowerCase(Locale.ROOT);
    }

    //Write next to the target and rename, so a failure never leaves half a file in the list
    private static void writeAtomically(File file, byte[] data) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            tmp.delete();
            throw new IOException("Could not write " + file.getName());
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Could not write " + file.getName());
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = stream.read(chunk)) != -1)
                out.write(chunk, 0, n);
            return out.toByteArray();
        }
    }
}
