package io.github.zensu357.camswap.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResidualCleanerTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testCleanItem_file() throws Exception {
        File dummyFile = temporaryFolder.newFile("dummy_residual.mp4");
        assertTrue(dummyFile.exists());

        boolean cleaned = ResidualCleaner.INSTANCE.cleanItem(dummyFile.getAbsolutePath());
        assertTrue(cleaned);
        assertFalse(dummyFile.exists());
    }

    @Test
    public void testCleanItem_directory() throws Exception {
        File dummyDir = temporaryFolder.newFolder("dummy_dir");
        File childFile = new File(dummyDir, "child.txt");
        childFile.createNewFile();
        assertTrue(dummyDir.exists());
        assertTrue(childFile.exists());

        boolean cleaned = ResidualCleaner.INSTANCE.cleanItem(dummyDir.getAbsolutePath());
        assertTrue(cleaned);
        assertFalse(dummyDir.exists());
    }

    @Test
    public void testCleanAll() throws Exception {
        File f1 = temporaryFolder.newFile("test1.mp4");
        File f2 = temporaryFolder.newFile("test2.json");

        List<ResidualCleaner.ScanResult> items = List.of(
                new ResidualCleaner.ScanResult(f1.getAbsolutePath(), "Item 1", true, false),
                new ResidualCleaner.ScanResult(f2.getAbsolutePath(), "Item 2", true, false)
        );

        int count = ResidualCleaner.INSTANCE.cleanAll(items);
        assertEquals(2, count);
        assertFalse(f1.exists());
        assertFalse(f2.exists());
    }
}
