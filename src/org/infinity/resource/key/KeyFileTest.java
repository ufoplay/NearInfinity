package org.infinity.resource.key;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertNotNull;

public class KeyFileTest {

    @Test
    public void testDetectBiffType() throws Exception {
        // Prepare input test file
        Path testFile = Paths.get("/home/ufo/Temp/Games/Baldur's Gate - Enhanced Edition/chitin.key");

        Keyfile key = new Keyfile(testFile);
        key.init();



        // Assert the result (replace ExpectedType with your enum/class)
//        assertNotNull(type);
//        assertEquals(ExpectedType.SOME_VALUE, result);
    }
}