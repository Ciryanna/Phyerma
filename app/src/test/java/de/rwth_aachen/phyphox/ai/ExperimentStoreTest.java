package de.rwth_aachen.phyphox.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExperimentStoreTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private static String file(String title) {
        return "<phyphox version=\"1.20\"><title>" + title + "</title></phyphox>";
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String crc(String text) {
        CRC32 crc = new CRC32();
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        crc.update(data, 0, data.length);
        return Long.toHexString(crc.getValue());
    }

    @Test
    public void titleComesFromTheRootsChild() {
        assertEquals("Pendulum", ExperimentStore.titleOf(stream(file("Pendulum ")), "x"));
        assertEquals("fallback", ExperimentStore.titleOf(stream("not xml"), "fallback"));
        assertEquals("fallback", ExperimentStore.titleOf(stream(file("  ")), "fallback"));
    }

    @Test
    public void createWritesANewFileAndUndoRemovesIt() throws Exception {
        ExperimentStore.Change change = ExperimentStore.create(context, file("One"));
        assertTrue(change.created);
        assertTrue(change.file.getName().endsWith(".phyphox"));
        assertEquals(file("One"), ExperimentStore.read(context, new ExperimentStore.Ref("One", change.file.getName(), false)));
        change.undo();
        assertFalse(change.file.exists());
    }

    @Test
    public void listShowsOwnExperimentsFirst() throws Exception {
        ExperimentStore.Change change = ExperimentStore.create(context, file("Mine"));
        List<ExperimentStore.Ref> refs = ExperimentStore.list(context);
        assertEquals("Mine", refs.get(0).title);
        assertFalse(refs.get(0).asset);
        change.undo();
    }

    @Test
    public void replaceKeepsTheOldVersionForUndo() throws Exception {
        ExperimentStore.Change created = ExperimentStore.create(context, file("v1"));
        ExperimentStore.Ref ref = new ExperimentStore.Ref("v1", created.file.getName(), false);
        ExperimentStore.Change replaced = ExperimentStore.replace(context, ref, file("v2"));
        assertFalse(replaced.created);
        assertEquals(file("v2"), ExperimentStore.read(context, ref));
        replaced.undo();
        assertEquals(file("v1"), ExperimentStore.read(context, ref));
        created.undo();
    }

    @Test
    public void resourceFolderFollowsTheContent() throws Exception {
        ExperimentStore.Change created = ExperimentStore.create(context, file("v1"));
        ExperimentStore.Ref ref = new ExperimentStore.Ref("v1", created.file.getName(), false);
        File folder = new File(context.getFilesDir(), crc(file("v1")));
        assertTrue(folder.mkdirs());
        ExperimentStore.Change replaced = ExperimentStore.replace(context, ref, file("v2"));
        File moved = new File(context.getFilesDir(), crc(file("v2")));
        assertTrue(moved.isDirectory());
        assertFalse(folder.exists());
        replaced.undo();
        assertTrue(folder.isDirectory());
        assertFalse(moved.exists());
        created.undo();
    }

    @Test
    public void builtInExperimentsCannotBeOverwritten() {
        try {
            ExperimentStore.replace(context, new ExperimentStore.Ref("x", "whatever.phyphox", true), file("x"));
            fail("expected an IOException");
        } catch (IOException expected) {
        }
    }
}
