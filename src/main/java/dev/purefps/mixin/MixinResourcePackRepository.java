package dev.purefps.mixin;

import dev.purefps.client.ui.ResourcePackBrowserHelper;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.resources.ResourcePackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.File;
import java.io.FileFilter;

/**
 * Permet à ResourcePackRepository de détecter et conserver les packs rangés dans des sous-dossiers
 * de {@code resourcepacks/} (ex: PvP, 16x, BedWars) d'un redémarrage à l'autre.
 */
@Mixin(ResourcePackRepository.class)
public class MixinResourcePackRepository {

    @Redirect(
            method = "getResourcePackFiles",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/io/File;listFiles(Ljava/io/FileFilter;)[Ljava/io/File;",
                    remap = false
            )
    )
    private File[] purefps$scanSubfoldersForPacks(File rootDir, FileFilter filter) {
        if (!PureFPSConfig.enabled) {
            return rootDir.listFiles(filter);
        }
        return ResourcePackBrowserHelper.collectPackFilesRecursively(rootDir, filter);
    }
}
