// Near Infinity - An Infinity Engine Browser and Editor
// Copyright (C) 2001 Jon Olav Hauglid
// See LICENSE.txt for license information

package org.infinity;

import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.infinity.resource.Profile;
import org.infinity.resource.ResourceFactory;
import org.infinity.resource.bcs.Decompiler;
import org.infinity.resource.graphics.BamDecoder;
import org.infinity.resource.graphics.BamResource;
import org.infinity.resource.graphics.ColorConvert;
import org.infinity.resource.graphics.Compressor;
import org.infinity.resource.graphics.MosDecoder;
import org.infinity.resource.graphics.MosV1Decoder;
import org.infinity.resource.graphics.PvrDecoder;
import org.infinity.resource.graphics.TisConvert;
import org.infinity.resource.graphics.TisDecoder;
import org.infinity.resource.graphics.decoder.PvrInfo;
import org.infinity.resource.key.ResourceEntry;
import org.infinity.resource.sound.AudioFactory;
import org.infinity.util.StaticSimpleXorDecryptor;
import org.infinity.util.io.FileManager;
import org.infinity.util.io.StreamUtils;

/**
 * Headless extractor that reads a {@code chitin.key} and dumps every resource of every supported type to disk.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Decode (default):</b> supported types are converted to convenient formats — BCS/BS to BAF source,
 *       BAM/MOS/TIS/PVRZ to PNG, ACM/WAV to PCM WAV; XOR-encrypted text resources (2DA, IDS, INI, BIO, MUS, …)
 *       are decrypted. The original bytes are kept alongside the decoded artifacts.</li>
 *   <li><b>Raw ({@code --raw}):</b> every resource is written verbatim as
 *       {@code <output>/<type>/<name>.<ext>}. No conversion, no XOR decryption.</li>
 * </ul>
 */
public final class Extractor {

  // Text / data resources (XOR-decryptable in decode mode).
  private static final String TYPE_2DA = "2da";
  private static final String TYPE_BIO = "bio";
  private static final String TYPE_IDS = "ids";
  private static final String TYPE_INI = "ini";
  private static final String TYPE_MUS = "mus";
  private static final String TYPE_SRC = "src";

  // EE-era text resources (rarely encrypted; maybeDecrypt is a no-op when unencrypted).
  private static final String TYPE_GLSL = "glsl";
  private static final String TYPE_GUI  = "gui";
  private static final String TYPE_LUA  = "lua";
  private static final String TYPE_MENU = "menu";
  private static final String TYPE_SQL  = "sql";

  // Binary scripts (decompiled to BAF in decode mode).
  private static final String TYPE_BCS = "bcs";
  private static final String TYPE_BS  = "bs";

  // Graphics decoders (rendered to PNG in decode mode).
  private static final String TYPE_BAM  = "bam";
  private static final String TYPE_MOS  = "mos";
  private static final String TYPE_PVRZ = "pvrz";
  private static final String TYPE_TIS  = "tis";

  // Graphics, raw only.
  private static final String TYPE_BMP = "bmp";
  private static final String TYPE_FNT = "fnt";
  private static final String TYPE_PLT = "plt";
  private static final String TYPE_PNG = "png";
  private static final String TYPE_TTF = "ttf";

  // Audio (decoded to PCM WAV in decode mode).
  private static final String TYPE_ACM = "acm";
  private static final String TYPE_WAV = "wav";

  // Audio, raw only.
  private static final String TYPE_WFX = "wfx";

  // Video, raw only (per spec).
  private static final String TYPE_MVE = "mve";
  private static final String TYPE_WBM = "wbm";

  // Game structs / misc binary, raw only.
  private static final String TYPE_ARE  = "are";
  private static final String TYPE_BAH  = "bah";
  private static final String TYPE_CHR  = "chr";
  private static final String TYPE_CHU  = "chu";
  private static final String TYPE_CRE  = "cre";
  private static final String TYPE_DLG  = "dlg";
  private static final String TYPE_EFF  = "eff";
  private static final String TYPE_GAM  = "gam";
  private static final String TYPE_ITM  = "itm";
  private static final String TYPE_MAZE = "maze";
  private static final String TYPE_PRO  = "pro";
  private static final String TYPE_SPL  = "spl";
  private static final String TYPE_STO  = "sto";
  private static final String TYPE_TOH  = "toh";
  private static final String TYPE_TOT  = "tot";
  private static final String TYPE_VEF  = "vef";
  private static final String TYPE_VVC  = "vvc";
  private static final String TYPE_WED  = "wed";
  private static final String TYPE_WMP  = "wmp";

  private static final List<String> SUPPORTED_TYPES = Arrays.asList(
      TYPE_2DA, TYPE_ACM, TYPE_ARE, TYPE_BAH, TYPE_BAM, TYPE_BCS, TYPE_BIO, TYPE_BMP, TYPE_BS,
      TYPE_CHR, TYPE_CHU, TYPE_CRE, TYPE_DLG, TYPE_EFF, TYPE_FNT, TYPE_GAM, TYPE_GLSL, TYPE_GUI,
      TYPE_IDS, TYPE_INI, TYPE_ITM, TYPE_LUA, TYPE_MAZE, TYPE_MENU, TYPE_MOS, TYPE_MUS, TYPE_MVE,
      TYPE_PLT, TYPE_PNG, TYPE_PRO, TYPE_PVRZ, TYPE_SPL, TYPE_SQL, TYPE_SRC, TYPE_STO, TYPE_TIS,
      TYPE_TOH, TYPE_TOT, TYPE_TTF, TYPE_VEF, TYPE_VVC, TYPE_WAV, TYPE_WBM, TYPE_WED, TYPE_WFX,
      TYPE_WMP);

  public static void main(String[] args) {
    final List<String> positional = new ArrayList<>(args.length);
    boolean decode = true;
    boolean allTypes = false;
    String typeListArg = null;
    for (int i = 0; i < args.length; i++) {
      final String arg = args[i];
      switch (arg) {
        case "--raw":
        case "--extract-only":
          decode = false;
          break;
        case "--decode":
          decode = true;
          break;
        case "--all-types":
          allTypes = true;
          break;
        case "--type":
          if (i + 1 >= args.length) {
            System.err.println("--type requires a comma-separated list of types");
            printUsage();
            System.exit(1);
          }
          typeListArg = args[++i];
          break;
        case "-h":
        case "--help":
          printUsage();
          System.exit(0);
          break;
        default:
          if (arg.startsWith("--type=")) {
            typeListArg = arg.substring("--type=".length());
          } else if (arg.startsWith("--")) {
            System.err.println("Unknown flag: " + arg);
            printUsage();
            System.exit(1);
          } else {
            positional.add(arg);
          }
      }
    }

    if (positional.size() != 2) {
      printUsage();
      System.exit(1);
    }
    if (allTypes == (typeListArg != null)) {
      // Either both set or neither set: both are errors.
      System.err.println(allTypes
          ? "--type and --all-types are mutually exclusive"
          : "Specify either --type <list> or --all-types");
      printUsage();
      System.exit(1);
    }

    final Path gameRoot = Paths.get(positional.get(0));
    final Path outputRoot = Paths.get(positional.get(1));
    final List<String> types;
    if (allTypes) {
      types = SUPPORTED_TYPES;
    } else {
      types = parseTypeList(typeListArg);
      if (types.isEmpty()) {
        System.err.println("--type list is empty");
        System.exit(1);
      }
    }

    final Path chitin = FileManager.resolveExisting(gameRoot.resolve("chitin.key"));
    if (chitin == null || !Files.isRegularFile(chitin)) {
      System.err.println("chitin.key not found in: " + gameRoot);
      System.exit(2);
    }

    try {
      if (!Profile.openGame(chitin)) {
        System.err.println("Failed to open game at: " + gameRoot);
        System.exit(4);
      }

      final String modeLabel = decode ? "decode" : "raw";
      for (final String type : types) {
        if (types.size() > 1) {
          System.out.println("=== Extracting " + type.toUpperCase(Locale.ENGLISH) + " (" + modeLabel + ") ===");
        }
        extractType(outputRoot, type, decode);
      }
      System.out.println("Done.");
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(5);
    }
  }

  private static void extractType(Path outputRoot, String type, boolean decode) throws Exception {
    if (!decode) {
      extractRaw(outputRoot, type);
      return;
    }
    switch (type) {
      case TYPE_BCS:
      case TYPE_BS:
        extractScript(outputRoot, type);
        break;
      case TYPE_BAM:
        extractBam(outputRoot);
        break;
      case TYPE_MOS:
        extractMos(outputRoot);
        break;
      case TYPE_PVRZ:
        extractPvrz(outputRoot);
        break;
      case TYPE_TIS:
        extractTis(outputRoot);
        break;
      case TYPE_ACM:
      case TYPE_WAV:
        extractAudio(outputRoot, type);
        break;
      default:
        // Text-ish types: try XOR-decrypt; everything else: copy bytes verbatim into <type>/<name>.<ext>.
        extractSimple(outputRoot, type, isTextType(type));
        break;
    }
  }

  private static boolean isTextType(String type) {
    switch (type) {
      case TYPE_2DA:
      case TYPE_BIO:
      case TYPE_IDS:
      case TYPE_INI:
      case TYPE_MUS:
      case TYPE_SRC:
      case TYPE_GLSL:
      case TYPE_GUI:
      case TYPE_LUA:
      case TYPE_MENU:
      case TYPE_SQL:
        return true;
      default:
        return false;
    }
  }

  private static List<String> parseTypeList(String raw) {
    final List<String> result = new ArrayList<>();
    for (final String token : raw.split(",")) {
      final String type = token.trim().toLowerCase(Locale.ENGLISH);
      if (type.isEmpty()) {
        continue;
      }
      if (!SUPPORTED_TYPES.contains(type)) {
        System.err.println("Unsupported file type: " + token
            + " (supported: " + String.join(", ", SUPPORTED_TYPES) + ")");
        System.exit(3);
      }
      if (!result.contains(type)) {
        result.add(type);
      }
    }
    return result;
  }

  private static void printUsage() {
    System.err.println("Usage: java -jar Extractor.jar <gameRoot> <output> (--type <list>|--all-types) [--raw|--decode]");
    System.err.println("  <gameRoot>     Path to the game folder containing chitin.key");
    System.err.println("  <output>       Output destination path");
    System.err.println("  --type <list>  Comma-separated list of types to extract (e.g. --type are,acm).");
    System.err.println("                 Supported: " + String.join(", ", SUPPORTED_TYPES));
    System.err.println("  --all-types    Extract every supported type.");
    System.err.println("  --decode       [default] Decode supported types: BCS/BS -> BAF source,");
    System.err.println("                 BAM/MOS/TIS/PVRZ -> PNG, ACM/WAV -> PCM WAV,");
    System.err.println("                 XOR-encrypted text resources are decrypted.");
    System.err.println("  --raw          Extract raw bytes only (no decoding). Output layout:");
    System.err.println("                 <output>/<type>/<name>.<ext>");
  }

  // === Raw extraction (used for --raw mode, and for non-decoded types in decode mode) ===

  private static void extractRaw(Path outputRoot, String type) throws Exception {
    final String upperType = type.toUpperCase(Locale.ENGLISH);
    final List<ResourceEntry> entries = ResourceFactory.getResources(upperType);
    if (entries == null || entries.isEmpty()) {
      System.out.println("No " + upperType + " resources found.");
      return;
    }

    final Path typeDir = outputRoot.resolve(type);
    Files.createDirectories(typeDir);

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      System.out.println("[" + idx + "/" + total + "] " + name + "." + type);
      try {
        writeBuffer(typeDir.resolve(name + "." + type), entry.getResourceBuffer());
      } catch (Exception e) {
        failures++;
        System.err.println("  failed: " + e.getMessage());
      }
    }

    reportFooter(upperType, typeDir, total, failures);
  }

  // === Decoders (decode mode) ===

  private static void extractScript(Path outputRoot, String type) throws Exception {
    final String upperType = type.toUpperCase(Locale.ENGLISH);
    final List<ResourceEntry> entries = ResourceFactory.getResources(upperType);
    if (entries == null || entries.isEmpty()) {
      System.out.println("No " + upperType + " resources found.");
      return;
    }

    final Path typeDir = outputRoot.resolve(type);
    final Path originalDir = typeDir.resolve("original");
    final Path sourceDir = typeDir.resolve("source");
    Files.createDirectories(originalDir);
    Files.createDirectories(sourceDir);

    final Charset charset = Profile.getDefaultCharset();
    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      System.out.println("[" + idx + "/" + total + "] " + name + "." + type);
      try {
        final ByteBuffer buffer = entry.getResourceBuffer();
        writeBuffer(originalDir.resolve(name + "." + type), buffer);

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

    reportFooter(upperType, typeDir, total, failures);
  }

  private static void extractSimple(Path outputRoot, String type, boolean decryptText) throws Exception {
    final String upperType = type.toUpperCase(Locale.ENGLISH);
    final List<ResourceEntry> entries = ResourceFactory.getResources(upperType);
    if (entries == null || entries.isEmpty()) {
      System.out.println("No " + upperType + " resources found.");
      return;
    }

    final Path typeDir = outputRoot.resolve(type);
    Files.createDirectories(typeDir);

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

    reportFooter(upperType, typeDir, total, failures);
  }

  private static void extractBam(Path outputRoot) throws Exception {
    final List<ResourceEntry> entries = ResourceFactory.getResources("BAM");
    if (entries == null || entries.isEmpty()) {
      System.out.println("No BAM resources found.");
      return;
    }

    final Path rootDir = outputRoot.resolve(TYPE_BAM);
    Files.createDirectories(rootDir);

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

        final Path groupDir = rootDir.resolve(groupName);
        final Path originalDir = groupDir.resolve("original");
        final Path pngDir = groupDir.resolve("png");
        Files.createDirectories(originalDir);
        Files.createDirectories(pngDir);

        final ByteBuffer original = entry.getResourceBuffer();
        writeBuffer(originalDir.resolve(name + ".bam"), original);

        if (bamType == BamDecoder.Type.BAMC) {
          final Path decompressedDir = groupDir.resolve("decompressed");
          Files.createDirectories(decompressedDir);
          final ByteBuffer decompressed = Compressor.decompress(original.duplicate());
          writeBuffer(decompressedDir.resolve(name + ".bam"), decompressed);
        }

        final BamDecoder decoder = BamDecoder.loadBam(entry);
        if (decoder == null) {
          throw new IOException("Failed to load BAM decoder");
        }
        try {
          BamResource.exportFrames(decoder, pngDir, name + "_", ".png", "png", true);
        } finally {
          decoder.close();
        }
      } catch (Exception e) {
        failures++;
        System.err.println("[" + idx + "/" + total + "] " + name + ".bam failed: " + e.getMessage());
      }
    }

    reportFooter("BAM", rootDir, total, failures);
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

  private static void extractPvrz(Path outputRoot) throws Exception {
    final List<ResourceEntry> entries = ResourceFactory.getResources("PVRZ");
    if (entries == null || entries.isEmpty()) {
      System.out.println("No PVRZ resources found.");
      return;
    }

    final Path rootDir = outputRoot.resolve(TYPE_PVRZ);
    Files.createDirectories(rootDir);

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      try {
        final ByteBuffer original = entry.getResourceBuffer();
        final ByteBuffer decompressed = Compressor.decompress(original.duplicate(), 0);
        if (decompressed == null) {
          throw new IOException("Failed to decompress PVRZ payload");
        }

        final PvrDecoder decoder = PvrDecoder.loadPvr(entry);
        if (decoder == null) {
          throw new IOException("Failed to load PVR decoder");
        }
        final PvrInfo info = decoder.getInfo();
        final PvrInfo.PixelFormat pixelFormat = info.getPixelFormat();
        final String groupName = (pixelFormat != null ? pixelFormat.name() : "unknown")
            .toLowerCase(Locale.ENGLISH);
        System.out.println("[" + idx + "/" + total + "] " + name + ".pvrz (" + groupName + ")");

        final Path groupDir = rootDir.resolve(groupName);
        final Path originalDir = groupDir.resolve("original");
        final Path decompressedDir = groupDir.resolve("decompressed");
        final Path pngDir = groupDir.resolve("png");
        Files.createDirectories(originalDir);
        Files.createDirectories(decompressedDir);
        Files.createDirectories(pngDir);

        writeBuffer(originalDir.resolve(name + ".pvrz"), original);
        writeBuffer(decompressedDir.resolve(name + ".pvr"), decompressed);

        try {
          final BufferedImage image = new BufferedImage(info.getWidth(), info.getHeight(), BufferedImage.TYPE_INT_ARGB);
          if (decoder.decode(image)) {
            ImageIO.write(image, "png", pngDir.resolve(name + ".png").toFile());
          } else {
            System.err.println("  PNG skipped: decoder reported failure for format " + groupName);
          }
        } finally {
          PvrDecoder.flushCache();
        }
      } catch (Exception e) {
        failures++;
        System.err.println("[" + idx + "/" + total + "] " + name + ".pvrz failed: " + e.getMessage());
      }
    }

    reportFooter("PVRZ", rootDir, total, failures);
  }

  private static void extractMos(Path outputRoot) throws Exception {
    final List<ResourceEntry> entries = ResourceFactory.getResources("MOS");
    if (entries == null || entries.isEmpty()) {
      System.out.println("No MOS resources found.");
      return;
    }

    final Path typeDir = outputRoot.resolve(TYPE_MOS);
    Files.createDirectories(typeDir);

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      try {
        final MosDecoder.Type mosType = MosDecoder.getType(entry);
        final String groupName = mosGroupName(mosType);
        if (groupName == null) {
          throw new IOException("Not a recognized MOS resource (type=" + mosType + ")");
        }
        System.out.println("[" + idx + "/" + total + "] " + name + ".mos (" + groupName + ")");

        final Path groupDir = typeDir.resolve(groupName);
        final Path originalDir = groupDir.resolve("original");
        final Path pngDir = groupDir.resolve("png");
        Files.createDirectories(originalDir);
        Files.createDirectories(pngDir);

        final ByteBuffer original = entry.getResourceBuffer();
        writeBuffer(originalDir.resolve(name + ".mos"), original);

        if (mosType == MosDecoder.Type.MOSC) {
          final Path decompressedDir = groupDir.resolve("decompressed");
          Files.createDirectories(decompressedDir);
          final ByteBuffer decompressed = Compressor.decompress(original.duplicate());
          writeBuffer(decompressedDir.resolve(name + ".mos"), decompressed);
        }

        final MosDecoder decoder = MosDecoder.loadMos(entry);
        if (decoder == null) {
          throw new IOException("Failed to load MOS decoder");
        }
        try {
          if (decoder instanceof MosV1Decoder) {
            ((MosV1Decoder) decoder).setTransparencyEnabled(true);
          }
          final BufferedImage image = ColorConvert.toBufferedImage(decoder.getImage(), true);
          if (image == null) {
            throw new IOException("Decoder produced no image");
          }
          ImageIO.write(image, "png", pngDir.resolve(name + ".png").toFile());
        } finally {
          decoder.close();
          PvrDecoder.flushCache();
        }
      } catch (Exception e) {
        failures++;
        System.err.println("[" + idx + "/" + total + "] " + name + ".mos failed: " + e.getMessage());
      }
    }

    reportFooter("MOS", typeDir, total, failures);
  }

  private static String mosGroupName(MosDecoder.Type type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case MOSV1: return "mos_v1";
      case MOSV2: return "mos_v2";
      case MOSC:  return "mosc";
      default:    return null;
    }
  }

  private static void extractTis(Path outputRoot) throws Exception {
    final List<ResourceEntry> entries = ResourceFactory.getResources("TIS");
    if (entries == null || entries.isEmpty()) {
      System.out.println("No TIS resources found.");
      return;
    }

    final Path rootDir = outputRoot.resolve(TYPE_TIS);
    Files.createDirectories(rootDir);

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      try {
        final TisDecoder.Type tisType = TisDecoder.getType(entry);
        final String groupName = tisGroupName(tisType);
        if (groupName == null) {
          throw new IOException("Not a recognized TIS resource (type=" + tisType + ")");
        }
        System.out.println("[" + idx + "/" + total + "] " + name + ".tis (" + groupName + ")");

        final Path groupDir = rootDir.resolve(groupName);
        final Path originalDir = groupDir.resolve("original");
        final Path pngDir = groupDir.resolve("png");
        Files.createDirectories(originalDir);
        Files.createDirectories(pngDir);

        final ByteBuffer original = entry.getResourceBuffer();
        writeBuffer(originalDir.resolve(name + ".tis"), original);

        final TisDecoder decoder = TisDecoder.loadTis(entry);
        if (decoder == null) {
          throw new IOException("Failed to load TIS decoder");
        }
        try {
          final int tileCount = decoder.getTileCount();
          final int columns = Math.max(1, TisConvert.calcTilesetWidth(entry, true, tileCount));
          final int rows = (tileCount + columns - 1) / columns;
          final int tileW = decoder.getTileWidth();
          final int tileH = decoder.getTileHeight();

          final BufferedImage tile = ColorConvert.createCompatibleImage(tileW, tileH, Transparency.BITMASK);
          final BufferedImage image =
              ColorConvert.createCompatibleImage(tileW * columns, tileH * rows, Transparency.BITMASK);
          final Graphics2D g = image.createGraphics();
          try {
            for (int i = 0; i < tileCount; i++) {
              decoder.getTile(i, tile);
              g.drawImage(tile, tileW * (i % columns), tileH * (i / columns), null);
            }
          } finally {
            g.dispose();
          }
          ImageIO.write(image, "png", pngDir.resolve(name + ".png").toFile());
        } finally {
          decoder.close();
          PvrDecoder.flushCache();
        }
      } catch (Exception e) {
        failures++;
        System.err.println("[" + idx + "/" + total + "] " + name + ".tis failed: " + e.getMessage());
      }
    }

    reportFooter("TIS", rootDir, total, failures);
  }

  private static String tisGroupName(TisDecoder.Type type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case PALETTE: return "tis_palette";
      case PVRZ:    return "tis_pvrz";
      default:      return null;
    }
  }

  private static void extractAudio(Path outputRoot, String type) throws Exception {
    final String upperType = type.toUpperCase(Locale.ENGLISH);
    final List<ResourceEntry> entries = ResourceFactory.getResources(upperType);
    if (entries == null || entries.isEmpty()) {
      System.out.println("No " + upperType + " resources found.");
      return;
    }

    final Path typeDir = outputRoot.resolve(type);
    final Path originalDir = typeDir.resolve("original");
    final Path wavDir = typeDir.resolve("wav");
    Files.createDirectories(originalDir);
    Files.createDirectories(wavDir);

    final int total = entries.size();
    int idx = 0;
    int failures = 0;
    int decodeSkips = 0;
    for (final ResourceEntry entry : entries) {
      idx++;
      final String name = entry.getResourceRef();
      System.out.println("[" + idx + "/" + total + "] " + name + "." + type);
      try {
        writeBuffer(originalDir.resolve(name + "." + type), entry.getResourceBuffer());

        final byte[] wav = AudioFactory.convertAudio(entry);
        if (wav == null || wav.length == 0) {
          decodeSkips++;
          System.err.println("  decode skipped: AudioFactory returned no PCM data");
        } else {
          Files.write(wavDir.resolve(name + ".wav"), wav);
        }
      } catch (Exception e) {
        failures++;
        System.err.println("  failed: " + e.getMessage());
      }
    }

    System.out.println("Extracted " + (total - failures) + "/" + total + " " + upperType
        + " resources to " + typeDir + (decodeSkips > 0 ? " (" + decodeSkips + " decode skips)" : ""));
    if (failures > 0) {
      System.err.println(failures + " resource(s) failed to extract.");
    }
  }

  private static void reportFooter(String upperType, Path destDir, int total, int failures) {
    System.out.println("Extracted " + (total - failures) + "/" + total + " " + upperType + " resources to " + destDir);
    if (failures > 0) {
      System.err.println(failures + " resource(s) failed to extract.");
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
