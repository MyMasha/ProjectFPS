package dev.purefps.mixin;

import dev.purefps.client.ui.ResourcePackBrowserHelper;
import dev.purefps.client.ui.ResourcePackFolderEntry;
import dev.purefps.config.PureFPSConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiResourcePackAvailable;
import net.minecraft.client.gui.GuiResourcePackSelected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenResourcePacks;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.ResourcePackListEntry;
import net.minecraft.client.resources.ResourcePackListEntryFound;
import net.minecraft.client.resources.ResourcePackRepository;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ajoute une barre de recherche instantanée et la navigation par sous-dossiers
 * directement dans l'écran Resource Packs de Minecraft 1.8.9.
 */
@Mixin(GuiScreenResourcePacks.class)
public abstract class MixinGuiScreenResourcePacks extends GuiScreen {

    @Shadow
    private List<ResourcePackListEntry> availableResourcePacks;

    @Shadow
    private List<ResourcePackListEntry> selectedResourcePacks;

    @Shadow
    private GuiResourcePackAvailable availableResourcePacksList;

    @Shadow
    private GuiResourcePackSelected selectedResourcePacksList;

    @Unique
    private static String purefps$persistedQuery = "";

    @Unique
    private GuiTextField purefps$searchBox;

    @Unique
    private File purefps$rootDir;

    @Unique
    private File purefps$currentDir;

    @Unique
    private final List<ResourcePackListEntryFound> purefps$allDiscoveredPacks = new ArrayList<>();

    @Unique
    private int purefps$lastSelectedSignature = Integer.MIN_VALUE;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void purefps$onInitGuiReturn(CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.availableResourcePacksList == null) {
            return;
        }
        Keyboard.enableRepeatEvents(true);

        ResourcePackRepository repo = this.mc.getResourcePackRepository();
        this.purefps$rootDir = repo.getDirResourcepacks();
        if (this.purefps$currentDir == null
                || !this.purefps$currentDir.isDirectory()
                || (!ResourcePackBrowserHelper.isSameDirectory(this.purefps$currentDir, this.purefps$rootDir)
                && !ResourcePackBrowserHelper.isInsideDirectory(this.purefps$currentDir, this.purefps$rootDir))) {
            this.purefps$currentDir = this.purefps$rootDir;
        }

        purefps$indexAllPacks();

        this.availableResourcePacksList.top = 52;
        int boxX = this.width / 2 - 203;
        int boxY = 33;
        this.purefps$searchBox = new GuiTextField(9901, this.fontRendererObj, boxX, boxY, 198, 15);
        this.purefps$searchBox.setMaxStringLength(48);
        this.purefps$searchBox.setText(purefps$persistedQuery != null ? purefps$persistedQuery : "");

        purefps$rebuildAvailablePacks();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void purefps$syncBeforeDraw(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.purefps$searchBox == null) {
            return;
        }
        int sig = purefps$computeSelectedSignature();
        if (sig != this.purefps$lastSelectedSignature) {
            purefps$rebuildAvailablePacks();
        }
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void purefps$drawSearchOverlay(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.purefps$searchBox == null) {
            return;
        }
        this.purefps$searchBox.drawTextBox();
        String text = this.purefps$searchBox.getText();
        if ((text == null || text.isEmpty()) && !this.purefps$searchBox.isFocused()) {
            String hint = (this.purefps$currentDir != null && !ResourcePackBrowserHelper.isSameDirectory(this.purefps$currentDir, this.purefps$rootDir))
                    ? "Rechercher (\uD83D\uDCC1 " + this.purefps$currentDir.getName() + ")..."
                    : "Rechercher un pack...";
            this.fontRendererObj.drawStringWithShadow(
                    this.fontRendererObj.trimStringToWidth(hint, 188),
                    this.purefps$searchBox.xPosition + 4,
                    this.purefps$searchBox.yPosition + 3,
                    0xFF788296
            );
        } else if (text != null && !text.isEmpty()) {
            int clearX = this.purefps$searchBox.xPosition + this.purefps$searchBox.width - 13;
            int clearY = this.purefps$searchBox.yPosition + 2;
            boolean hov = mouseX >= clearX - 2 && mouseX <= clearX + 10 && mouseY >= clearY - 1 && mouseY <= clearY + 12;
            Gui.drawRect(clearX - 1, clearY, clearX + 9, clearY + 11, hov ? 0x553B4256 : 0x00000000);
            this.fontRendererObj.drawStringWithShadow("\u00d7", clearX + 2, clearY + 1, hov ? 0xFFFF9B9B : 0xFFAAB4C8);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void purefps$onMouseClickedHead(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        if (!PureFPSConfig.enabled || this.purefps$searchBox == null) {
            return;
        }
        String text = this.purefps$searchBox.getText();
        if (mouseButton == 0 && text != null && !text.isEmpty()) {
            int clearX = this.purefps$searchBox.xPosition + this.purefps$searchBox.width - 13;
            int clearY = this.purefps$searchBox.yPosition + 2;
            if (mouseX >= clearX - 2 && mouseX <= clearX + 10 && mouseY >= clearY - 1 && mouseY <= clearY + 12) {
                this.purefps$searchBox.setText("");
                purefps$persistedQuery = "";
                purefps$rebuildAvailablePacks();
                ci.cancel();
                return;
            }
        }
        this.purefps$searchBox.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 1
                && mouseX >= this.purefps$searchBox.xPosition
                && mouseX <= this.purefps$searchBox.xPosition + this.purefps$searchBox.width
                && mouseY >= this.purefps$searchBox.yPosition
                && mouseY <= this.purefps$searchBox.yPosition + this.purefps$searchBox.height) {
            this.purefps$searchBox.setText("");
            this.purefps$searchBox.setFocused(true);
            purefps$persistedQuery = "";
            purefps$rebuildAvailablePacks();
            ci.cancel();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (PureFPSConfig.enabled && this.purefps$searchBox != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                super.keyTyped(typedChar, keyCode);
                return;
            }
            if (!this.purefps$searchBox.isFocused() && typedChar >= 32 && typedChar != 127) {
                this.purefps$searchBox.setFocused(true);
            }
            String before = this.purefps$searchBox.getText();
            if (this.purefps$searchBox.textboxKeyTyped(typedChar, keyCode)) {
                String after = this.purefps$searchBox.getText();
                if (!after.equals(before)) {
                    purefps$persistedQuery = after;
                    purefps$rebuildAvailablePacks();
                }
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Unique
    private void purefps$indexAllPacks() {
        this.purefps$allDiscoveredPacks.clear();
        Set<ResourcePackRepository.Entry> seen = new HashSet<>();

        if (this.selectedResourcePacks != null) {
            for (ResourcePackListEntry entry : this.selectedResourcePacks) {
                if (entry instanceof ResourcePackListEntryFound) {
                    ResourcePackListEntryFound found = (ResourcePackListEntryFound) entry;
                    if (seen.add(found.func_148318_i())) {
                        this.purefps$allDiscoveredPacks.add(found);
                    }
                }
            }
        }
        if (this.availableResourcePacks != null) {
            for (ResourcePackListEntry entry : this.availableResourcePacks) {
                if (entry instanceof ResourcePackListEntryFound) {
                    ResourcePackListEntryFound found = (ResourcePackListEntryFound) entry;
                    if (seen.add(found.func_148318_i())) {
                        this.purefps$allDiscoveredPacks.add(found);
                    }
                }
            }
        }
    }

    @Unique
    private int purefps$computeSelectedSignature() {
        if (this.selectedResourcePacks == null) {
            return 0;
        }
        int h = this.selectedResourcePacks.size();
        for (int i = 0; i < this.selectedResourcePacks.size(); i++) {
            ResourcePackListEntry e = this.selectedResourcePacks.get(i);
            h = 31 * h + System.identityHashCode(e);
        }
        return h;
    }

    @Unique
    private void purefps$rebuildAvailablePacks() {
        if (this.availableResourcePacks == null) {
            return;
        }
        Set<ResourcePackRepository.Entry> selectedSet = new HashSet<>();
        if (this.selectedResourcePacks != null) {
            for (ResourcePackListEntry e : this.selectedResourcePacks) {
                if (e instanceof ResourcePackListEntryFound) {
                    selectedSet.add(((ResourcePackListEntryFound) e).func_148318_i());
                }
            }
        }

        String query = this.purefps$searchBox != null ? this.purefps$searchBox.getText() : "";
        boolean hasQuery = query != null && !query.trim().isEmpty();

        this.availableResourcePacks.clear();
        GuiScreenResourcePacks self = (GuiScreenResourcePacks) (Object) this;

        if (!hasQuery && this.purefps$rootDir != null && this.purefps$currentDir != null) {
            if (!ResourcePackBrowserHelper.isSameDirectory(this.purefps$currentDir, this.purefps$rootDir)) {
                File parent = this.purefps$currentDir.getParentFile();
                final File targetParent = (parent != null && (ResourcePackBrowserHelper.isSameDirectory(parent, this.purefps$rootDir)
                        || ResourcePackBrowserHelper.isInsideDirectory(parent, this.purefps$rootDir)))
                        ? parent
                        : this.purefps$rootDir;
                this.availableResourcePacks.add(new ResourcePackFolderEntry(
                        self, targetParent, true, 0,
                        () -> {
                            this.purefps$currentDir = targetParent;
                            purefps$rebuildAvailablePacks();
                        }
                ));
            }

            List<File> subfolders = ResourcePackBrowserHelper.listSubfolders(this.purefps$currentDir);
            for (final File folder : subfolders) {
                int countInSubtree = purefps$countAvailableInSubtree(folder, selectedSet);
                this.availableResourcePacks.add(new ResourcePackFolderEntry(
                        self, folder, false, countInSubtree,
                        () -> {
                            this.purefps$currentDir = folder;
                            purefps$rebuildAvailablePacks();
                        }
                ));
            }
        }

        for (ResourcePackListEntryFound packEntry : this.purefps$allDiscoveredPacks) {
            ResourcePackRepository.Entry repoEntry = packEntry.func_148318_i();
            if (repoEntry == null || selectedSet.contains(repoEntry)) {
                continue;
            }
            File packFile = ResourcePackBrowserHelper.getEntryFile(repoEntry);
            if (hasQuery) {
                if (ResourcePackBrowserHelper.matchesSearch(
                        repoEntry.getResourcePackName(),
                        repoEntry.getTexturePackDescription(),
                        packFile,
                        query
                )) {
                    this.availableResourcePacks.add(packEntry);
                }
            } else {
                File parentDir = packFile != null ? packFile.getParentFile() : this.purefps$rootDir;
                if (this.purefps$currentDir == null
                        || parentDir == null
                        || ResourcePackBrowserHelper.isSameDirectory(parentDir, this.purefps$currentDir)) {
                    this.availableResourcePacks.add(packEntry);
                }
            }
        }

        if (this.availableResourcePacksList != null) {
            this.availableResourcePacksList.scrollBy(-100000);
        }
        this.purefps$lastSelectedSignature = purefps$computeSelectedSignature();
    }

    @Unique
    private int purefps$countAvailableInSubtree(File folder, Set<ResourcePackRepository.Entry> selectedSet) {
        int count = 0;
        for (ResourcePackListEntryFound packEntry : this.purefps$allDiscoveredPacks) {
            ResourcePackRepository.Entry repoEntry = packEntry.func_148318_i();
            if (repoEntry == null || selectedSet.contains(repoEntry)) {
                continue;
            }
            File f = ResourcePackBrowserHelper.getEntryFile(repoEntry);
            if (ResourcePackBrowserHelper.isInsideDirectory(f, folder)) {
                count++;
            }
        }
        return count;
    }
}
