package dev.purefps.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool borné à 4 threads daemon pour le téléchargement HTTP des skins/capes (ThreadDownloadImageData) :
 * - Remplace la création illimitée de 60 à 120 threads OS simultanés à l'entrée d'un lobby Hypixel.
 * - Ajoute des timeouts réseau stricts (10 s) pour ne jamais bloquer un worker indéfiniment.
 */
public final class SkinDownloadPool {

    private static final Logger LOGGER = LogManager.getLogger("PureFPS-SkinDL");
    private static final AtomicInteger THREAD_ID = new AtomicInteger(0);

    /**
     * Thread sentinelle jamais démarré : stocké dans ThreadDownloadImageData.imageThread afin que
     * loadTexture() voie imageThread != null et ne soumette jamais deux téléchargements identiques.
     */
    public static final Thread MARKER_THREAD = new Thread("PureFPS-SkinDL-Marker");

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "PureFPS-SkinDL-" + THREAD_ID.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private SkinDownloadPool() {
    }

    public static void submitDownload(
            ThreadDownloadImageData target,
            File cacheFile,
            String imageUrl,
            IImageBuffer imageBuffer
    ) {
        if (target == null || imageUrl == null || imageUrl.isEmpty()) {
            return;
        }
        POOL.execute(() -> downloadBlocking(target, cacheFile, imageUrl, imageBuffer));
    }

    private static final int MAX_SKIN_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    private static void downloadBlocking(
            ThreadDownloadImageData target,
            File cacheFile,
            String imageUrl,
            IImageBuffer imageBuffer
    ) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(imageUrl);
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            java.net.URLConnection rawConn = (mc != null && mc.getProxy() != null)
                    ? url.openConnection(mc.getProxy())
                    : url.openConnection();
            if (!(rawConn instanceof HttpURLConnection)) {
                return;
            }
            conn = (HttpURLConnection) rawConn;
            conn.setDoInput(true);
            conn.setDoOutput(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) {
                return;
            }
            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_SKIN_DOWNLOAD_BYTES) {
                return;
            }
            byte[] payload = readBounded(conn.getInputStream(), MAX_SKIN_DOWNLOAD_BYTES);
            if (payload == null || payload.length == 0) {
                return;
            }
            BufferedImage img;
            if (cacheFile != null) {
                FileUtils.writeByteArrayToFile(cacheFile, payload);
                img = ImageIO.read(cacheFile);
            } else {
                img = TextureUtil.readBufferedImage(new java.io.ByteArrayInputStream(payload));
            }
            if (img != null) {
                if (imageBuffer != null) {
                    img = imageBuffer.parseUserSkin(img);
                }
                target.setBufferedImage(img);
            }
        } catch (Exception e) {
            LOGGER.debug("Couldn't download http texture from {}", imageUrl, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readBounded(java.io.InputStream in, int maxBytes) throws java.io.IOException {
        if (in == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (total + n > maxBytes) {
                    return null;
                }
                baos.write(buf, 0, n);
                total += n;
            }
            return baos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }
}
