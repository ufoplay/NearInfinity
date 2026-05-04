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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.infinity.resource.Profile;
import org.infinity.resource.ResourceFactory;
import org.infinity.resource.bcs.Decompiler;
import org.infinity.resource.graphics.BamDecoder;
import org.infinity.resource.graphics.BamResource;
import org.infinity.resource.graphics.Compressor;
import org.infinity.resource.key.ResourceEntry;
import org.infinity.util.StaticSimpleXorDecryptor;
import org.infinity.util.io.FileManager;
import org.infinity.util.io.StreamUtils;

/**
 * Headless extractor that reads a {@code chitin.key} and dumps every resource of a given type to disk.
 *
 * <p>For BCS resources the binary scripts are written under {@code <output>/bcs/original} and their
 * decompiled BAF sources under {@code <output>/bcs/source}. Other supported types are written
 * directly under {@code <output>/<type>/}.
 */
public final class Extractor {
  private static final String TYPE_BCS = "bcs";
  private static final String TYPE_2DA = "2da";
  private static final String TYPE_BMP = "bmp";
  private static final String TYPE_IDS = "ids";
  private static final String TYPE_INI = "ini";
  private static final String TYPE_WED = "wed";
  private static final String TYPE_BAM = "bam";

  private static final List<String> SUPPORTED_TYPES =
      Arrays.asList(TYPE_BCS, TYPE_2DA, TYPE_BMP, TYPE_IDS, TYPE_INI, TYPE_WED, TYPE_BAM);

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

    if (!SUPPORTED_TYPES.contains(type)) {
      System.err.println("Unsupported file type: " + args[2] + " (supported: " + String.join(", ", SUPPORTED_TYPES) + ")");
      System.exit(3);
    }

    try {
      if (!Profile.openGame(chitin)) {
        System.err.println("Failed to open game at: " + gameRoot);
        System.exit(4);
      }

      switch (type) {
        case TYPE_BCS:
          extractBcs(outputRoot);
          break;
        case TYPE_2DA:
          extractSimple(outputRoot, TYPE_2DA, true);
          break;
        case TYPE_BMP:
          extractSimple(outputRoot, TYPE_BMP, false);
          break;
        case TYPE_IDS:
          extractSimple(outputRoot, TYPE_IDS, true);
          break;
        case TYPE_INI:
          extractSimple(outputRoot, TYPE_INI, true);
          break;
        case TYPE_WED:
          extractSimple(outputRoot, TYPE_WED, false);
          break;
        case TYPE_BAM:
          extractBam(outputRoot);
          break;
        default:
          throw new IllegalStateException("Unhandled type: " + type);
      }
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
    System.err.println("  <type>      File type to extract (supported: " + String.join(", ", SUPPORTED_TYPES) + ")");
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

        final ByteBuffer code = maybeDecrypt(buffer);
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

  private static void extractSimple(Path outputRoot, String type, boolean decryptText) throws Exception {
    final Path typeDir = outputRoot.resolve(type);
    Files.createDirectories(typeDir);

    final String upperType = type.toUpperCase(Locale.ENGLISH);
    final List<ResourceEntry> entries = ResourceFactory.getResources(upperType);
    if (entries == null || entries.isEmpty()) {
      System.out.println("No " + upperType + " resources found.");
      return;
    }

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      System.out.println("[" + idx + "/" + total + "] " + name + "." + type);
      try {
        ByteBuffer buffer = entry.getResourceBuffer();
        if (decryptText) {
          buffer = maybeDecrypt(buffer);
        }
        writeBuffer(typeDir.resolve(name + "." + type), buffer);
      } catch (Exception e) {
        failures++;
        System.err.println("  failed: " + e.getMessage());
      }
    }

    System.out.println("Extracted " + (total - failures) + "/" + total + " " + upperType + " resources to " + typeDir);
    if (failures > 0) {
      System.err.println(failures + " resource(s) failed to extract.");
    }
  }

  private static void extractBam(Path outputRoot) throws Exception {
    final Path rootDir = outputRoot.resolve(TYPE_BAM);
    Files.createDirectories(rootDir);

    final List<ResourceEntry> entries = ResourceFactory.getResources("BAM");
    if (entries == null || entries.isEmpty()) {
      System.out.println("No BAM resources found.");
      return;
    }

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      try {
        final BamDecoder.Type bamType = BamDecoder.getType(entry);
        final String groupName = bamGroupName(bamType);
        if (groupName == null) {
          throw new IOException("Not a recognized BAM resource (type=" + bamType + ")");
        }
        System.out.println("[" + idx + "/" + total + "] " + name + ".bam (" + groupName + ")");

        final Path bamDir = rootDir.resolve(groupName).resolve(name);
        Files.createDirectories(bamDir);

        final ByteBuffer original = entry.getResourceBuffer();
        writeBuffer(bamDir.resolve(name + ".bam"), original);

        if (bamType == BamDecoder.Type.BAMC) {
          final ByteBuffer decompressed = Compressor.decompress(original.duplicate());
          writeBuffer(bamDir.resolve(name + "_decompressed.bam"), decompressed);
        }

        final BamDecoder decoder = BamDecoder.loadBam(entry);
        if (decoder == null) {
          throw new IOException("Failed to load BAM decoder");
        }
        try {
          BamResource.exportFrames(decoder, bamDir, name + "_", ".png", "png", true);
        } finally {
          decoder.close();
        }
      } catch (Exception e) {
        failures++;
        System.err.println("[" + idx + "/" + total + "] " + name + ".bam failed: " + e.getMessage());
      }
    }

    System.out.println("Extracted " + (total - failures) + "/" + total + " BAM resources to " + rootDir);
    if (failures > 0) {
      System.err.println(failures + " resource(s) failed to extract.");
    }
  }

  private static String bamGroupName(BamDecoder.Type type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case BAMV1: return "bam_v1";
      case BAMV2: return "bam_v2";
      case BAMC:  return "bamc";
      default:    return null;
    }
  }

  /** Returns the decrypted payload of an XOR-encrypted text resource, or the original buffer if unencrypted. */
  private static ByteBuffer maybeDecrypt(ByteBuffer buffer) {
    if (buffer.limit() > 1 && buffer.getShort(0) == -1) {
      return StaticSimpleXorDecryptor.decrypt(buffer, 2);
    }
    return buffer;
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
