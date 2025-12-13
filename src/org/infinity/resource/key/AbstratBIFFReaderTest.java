package org.infinity.resource.key;
import org.junit.Test;
import static org.junit.Assert.*;

        import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AbstratBIFFReaderTest {

    @Test
    public void testDetectBiffType() throws Exception {
        // Prepare input test file
        Path testFile = Paths.get("/home/ufo/workspaces/github_ufoscout/baldurs_gate/infinitier/src/core/tests/resources/iwd/CD2/Data/AR3603.cbf");

        AbstractBIFFReader type = AbstractBIFFReader.open(testFile);

        // Assert the result (replace ExpectedType with your enum/class)
        assertNotNull(type);
//        assertEquals(ExpectedType.SOME_VALUE, result);
    }

    @Test
    public void parseBiffType() throws Exception {
        // Prepare input test file
        Path testFile = Paths.get("/home/ufo/Temp/Games/Baldur's Gate 2 - Enhanced Edition/data/area500c.bif");

        AbstractBIFFReader type = AbstractBIFFReader.open(testFile);

        // Assert the result (replace ExpectedType with your enum/class)
        assertNotNull(type);
//        assertEquals(ExpectedType.SOME_VALUE, result);
    }
}