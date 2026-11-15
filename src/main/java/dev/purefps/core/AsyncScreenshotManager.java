package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import javax.imageio.ImageIO;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Pipeline asynchrone de captures d'écran F2 (0 ms de gel en PvP) :
 * - Convertit et inverse verticalement les pixels OpenGL en une seule passe System.arraycopy vers DataBufferInt.
 * - Encode le PNG sur un thread dédié en arrière-plan.
 * - Ajoute les actions cliquables [Ouvrir] et [Copier] dans le chat pour coller directement dans Discord (Ctrl+V).
 */
public final class AsyncScreenshotManager {

    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
    private static final int MAX_CACHED_RECORDS = 8;

    private static final ExecutorService SCREENSHOT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MashClient-ScreenshotIO");
        t.setDaemon(true);
        return t;
    });

    private static final class Record {
        final File file;
        final CountDownLatch ready = new CountDownLatch(1);
        volatile BufferedImage cachedImage;

        Record(File file) {
            this.file = file;
        }
    }

    private static final Map<String, Record> RECENT_RECORDS = new LinkedHashMap<String, Record>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Record> eldest) {
            return size() > MAX_CACHED_RECORDS;
        }
    };

    private AsyncScreenshotManager() {
    }

    public static synchronized File allocateScreenshotFile(File screenshotsDir, String customName) {
        if (!screenshotsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            screenshotsDir.mkdirs();
        }
        if (customName != null && !customName.trim().isEmpty()) {
            return new File(screenshotsDir, customName);
        }
        String base = TIMESTAMP_FORMAT.format(new Date());
        int idx = 1;
        while (true) {
            File candidate = new File(screenshotsDir, base + (idx == 1 ? "" : "_" + idx) + ".png");
            if (!candidate.exists() && !RECENT_RECORDS.containsKey(candidate.getName())) {
                return candidate;
            }
            idx++;
        }
    }

    public static IChatComponent submitAsyncEncode(
            final File targetFile,
            final int[] rawPixels,
            final int texWidth,
            final int texHeight,
            final int viewWidth,
            final int viewHeight,
            final boolean fboMode
    ) {
        final Record rec = new Record(targetFile);
        synchronized (AsyncScreenshotManager.class) {
            RECENT_RECORDS.put(targetFile.getName(), rec);
        }

        SCREENSHOT_IO.execute(() -> {
            try {
                BufferedImage img = buildFlippedRgbImage(rawPixels, texWidth, texHeight, viewWidth, viewHeight, fboMode);
                rec.cachedImage = img;
                ImageIO.write(img, "png", targetFile);
            } catch (Throwable ignored) {
            } finally {
                rec.ready.countDown();
            }
        });

        return buildInteractiveChatMessage(targetFile);
    }

    public static BufferedImage buildFlippedRgbImage(
            int[] rawPixels,
            int texWidth,
            int texHeight,
            int viewWidth,
            int viewHeight,
            boolean fboMode
    ) {
        // Inversion verticale directe en 1 seule passe (OpenGL ligne 0 = bas -> BufferedImage ligne 0 = haut)
        int outW = fboMode ? viewWidth : texWidth;
        int outH = fboMode ? viewHeight : texHeight;
        BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        int copyW = Math.min(outW, texWidth);
        for (int y = 0; y < outH; y++) {
            int glSrcRow = (outH - 1) - y;
            if (glSrcRow >= 0 && glSrcRow < texHeight) {
                System.arraycopy(rawPixels, glSrcRow * texWidth, dst, y * outW, copyW);
            }
        }
        return image;
    }

    public static IChatComponent buildInteractiveChatMessage(File targetFile) {
        boolean en = dev.purefps.config.PureFPSConfig.isEnglish();
        ChatComponentText root = new ChatComponentText(en ? "\u00a76[MashClient] \u00a7fScreenshot: " : "\u00a76[MashClient] \u00a7fCapture : ");

        ChatComponentText fileLink = new ChatComponentText(targetFile.getName());
        fileLink.setChatStyle(new ChatStyle()
                .setColor(EnumChatFormatting.GRAY)
                .setUnderlined(Boolean.TRUE)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, targetFile.getAbsolutePath()))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(en ? "\u00a77Open PNG file" : "\u00a77Ouvrir le fichier PNG"))));
        root.appendSibling(fileLink);

        root.appendText("  ");

        ChatComponentText openBtn = new ChatComponentText(en ? "[Open]" : "[Ouvrir]");
        openBtn.setChatStyle(new ChatStyle()
                .setColor(EnumChatFormatting.GREEN)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, targetFile.getAbsolutePath()))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(en ? "\u00a7aOpen screenshot" : "\u00a7aOuvrir la capture d'écran"))));
        root.appendSibling(openBtn);

        root.appendText(" ");

        ChatComponentText copyBtn = new ChatComponentText(en ? "[Copy]" : "[Copier]");
        copyBtn.setChatStyle(new ChatStyle()
                .setColor(EnumChatFormatting.GOLD)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mashclient copyshot " + targetFile.getName()))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(en ? "\u00a7eCopy to clipboard (Ctrl+V in Discord)" : "\u00a7eCopier dans le presse-papiers (Ctrl+V sur Discord)"))));
        root.appendSibling(copyBtn);

        return root;
    }

    public static void copyScreenshotToClipboardAsync(final String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return;
        }
        final String cleanName = new File(fileName.trim()).getName();
        Thread worker = new Thread(() -> {
            boolean en = dev.purefps.config.PureFPSConfig.isEnglish();
            try {
                Record rec;
                synchronized (AsyncScreenshotManager.class) {
                    rec = RECENT_RECORDS.get(cleanName);
                }
                BufferedImage img = null;
                File diskFile = null;
                if (rec != null) {
                    rec.ready.await(3, TimeUnit.SECONDS);
                    img = rec.cachedImage;
                    diskFile = rec.file;
                }
                if (img == null) {
                    Minecraft mc = Minecraft.getMinecraft();
                    File baseDir = mc != null ? mc.mcDataDir : new File(".");
                    if (diskFile == null) {
                        diskFile = new File(new File(baseDir, "screenshots"), cleanName);
                    }
                    if (diskFile.isFile()) {
                        img = ImageIO.read(diskFile);
                    }
                }
                if (img == null) {
                    postClientChat(en
                            ? "\u00a76[MashClient] \u00a7cScreenshot not found: " + cleanName
                            : "\u00a76[MashClient] \u00a7cCapture introuvable : " + cleanName);
                    return;
                }
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageTransferable(img), null);
                postClientChat(en
                        ? "\u00a76[MashClient] \u00a7aScreenshot copied to clipboard! (Ctrl+V)"
                        : "\u00a76[MashClient] \u00a7aCapture copiée dans le presse-papiers ! (Ctrl+V)");
            } catch (Throwable t) {
                postClientChat(en
                        ? "\u00a76[MashClient] \u00a7cFailed to copy screenshot: " + t.getClass().getSimpleName()
                        : "\u00a76[MashClient] \u00a7cImpossible de copier la capture : " + t.getClass().getSimpleName());
            }
        }, "MashClient-ClipboardCopy");
        worker.setDaemon(true);
        worker.start();
    }

    private static void postClientChat(final String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.addScheduledTask(() -> {
                if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                    mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
                }
            });
        }
    }

    private static final class ImageTransferable implements Transferable {
        private final BufferedImage image;

        ImageTransferable(BufferedImage image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return this.image;
        }
    }
}
