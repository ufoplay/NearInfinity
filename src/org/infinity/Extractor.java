// Near Infinity - An Infinity Engine Browser and Editor
// Copyright (C) 2001 Jon Olav Hauglid
// See LICENSE.txt for license information

package org.infinity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import org.infinity.resource.Profile;
import org.infinity.resource.ResourceFactory;
import org.infinity.resource.bcs.Decompiler;
import org.infinity.resource.key.ResourceEntry;
import org.infinity.util.StaticSimpleXorDecryptor;
import org.infinity.util.io.FileManager;
import org.infinity.util.io.StreamUtils;

/**
 * Headless extractor that reads a {@code chitin.key} and dumps every resource of a given type to disk.
 *
 * <p>For BCS resources the binary scripts are written under {@code <output>/bcs/original} and their
 * decompiled BAF sources under {@code <output>/bcs/source}.
 */
public final class Extractor {
  private static final String TYPE_BCS = "bcs";

  public static void main(String[] args) {
    if (args.length != 3) {
      printUsage();
      System.exit(1);
    }

    final Path gameRoot = Paths.get(args[0]);
    final Path outputRoot = Paths.get(args[1]);
    final String type = args[2].toLowerCase(Locale.ENGLISH);

    final Path chitin = FileManager.resolveExisting(gameRoot.resolve("chitin.key"));
    if (chitin == null || !Files.isRegularFile(chitin)) {
      System.err.println("chitin.key not found in: " + gameRoot);
      System.exit(2);
    }

    if (!TYPE_BCS.equals(type)) {
      System.err.println("Unsupported file type: " + args[2] + " (supported: bcs)");
      System.exit(3);
    }

    try {
      if (!Profile.openGame(chitin)) {
        System.err.println("Failed to open game at: " + gameRoot);
        System.exit(4);
      }

      extractBcs(outputRoot);
      System.out.println("Done.");
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(5);
    }
  }

  private static void printUsage() {
    System.err.println("Usage: java -jar Extractor.jar <gameRoot> <output> <type>");
    System.err.println("  <gameRoot>  Path to the game folder containing chitin.key");
    System.err.println("  <output>    Output destination path");
    System.err.println("  <type>      File type to extract (currently only 'bcs')");
  }

  private static void extractBcs(Path outputRoot) throws Exception {
    final Path typeDir = outputRoot.resolve(TYPE_BCS);
    final Path originalDir = typeDir.resolve("original");
    final Path sourceDir = typeDir.resolve("source");
    Files.createDirectories(originalDir);
    Files.createDirectories(sourceDir);

    final List<ResourceEntry> entries = ResourceFactory.getResources("BCS");
    if (entries == null || entries.isEmpty()) {
      System.out.println("No BCS resources found.");
      return;
    }

    final Charset charset = Profile.getDefaultCharset();
    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      System.out.println("[" + idx + "/" + total + "] " + name + ".bcs");
      try {
        final ByteBuffer buffer = entry.getResourceBuffer();
        writeBuffer(originalDir.resolve(name + ".bcs"), buffer);

        final ByteBuffer code = (buffer.limit() > 1 && buffer.getShort(0) == -1)
            ? StaticSimpleXorDecryptor.decrypt(buffer, 2)
            : buffer;
        final String text = StreamUtils.readString(code, 0, code.limit(), charset);
        final Decompiler decompiler = new Decompiler(text, false);
        decompiler.setGenerateComments(false);
        final String source = decompiler.getSource();
        Files.write(sourceDir.resolve(name + ".baf"), source.getBytes(charset));
      } catch (Exception e) {
        failures++;
        System.err.println("  failed: " + e.getMessage());
      }
    }

    System.out.println("Extracted " + (total - failures) + "/" + total + " BCS resources to " + typeDir);
    if (failures > 0) {
      System.err.println(failures + " resource(s) failed to extract.");
    }
  }

  private static void writeBuffer(Path file, ByteBuffer buffer) throws IOException {
    final byte[] data = new byte[buffer.limit()];
    final ByteBuffer dup = buffer.duplicate();
    dup.position(0);
    dup.get(data);
    Files.write(file, data);
  }

  private Extractor() {
  }
}
