package dev.purefps.module;

import java.util.List;

/**
 * Moteur mathématique d'aimantation et de collage ("Glue & Axis Snap") des éléments HUD MashClient 4.0 :
 * - Aligne et colle les éléments HUD entre eux bord-à-bord (0 px ou gap 3 px), bord-sur-bord (gauche/droite/haut/bas),
 *   centre-sur-centre, ainsi que sur les axes centraux et marges de l'écran.
 * - Supporte le verrouillage d'axe strict (touche Shift maintenue) pour déplacer sur un axe horizontal ou vertical pur.
 */
public final class HudSnapEngine {

    public static final float DEFAULT_SNAP_RADIUS = 5.5F;
    public static final float GLUE_GAP = 3.0F;
    public static final float SCREEN_MARGIN = 8.0F;

    public static final class Box {
        public final String id;
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public Box(String id, float x, float y, float w, float h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static final class SnapResult {
        public float x;
        public float y;

        public boolean hasVerticalGuide;
        public float verticalLineX;
        public float vMinY;
        public float vMaxY;
        public boolean verticalIsGlueOrCenter;

        public boolean hasHorizontalGuide;
        public float horizontalLineY;
        public float hMinX;
        public float hMaxX;
        public boolean horizontalIsGlueOrCenter;
    }

    private HudSnapEngine() {
    }

    public static SnapResult computeSnap(
            String draggedId,
            float rawX,
            float rawY,
            float w,
            float h,
            float dragOriginX,
            float dragOriginY,
            boolean lockAxisShift,
            float screenW,
            float screenH,
            List<Box> others,
            float snapRadius
    ) {
        SnapResult res = new SnapResult();

        float candidateX = rawX;
        float candidateY = rawY;

        if (lockAxisShift) {
            float dx = Math.abs(rawX - dragOriginX);
            float dy = Math.abs(rawY - dragOriginY);
            if (dx >= dy) {
                candidateY = dragOriginY;
                res.hasHorizontalGuide = true;
                res.horizontalLineY = dragOriginY + h * 0.5F;
                res.hMinX = 0.0F;
                res.hMaxX = screenW;
                res.horizontalIsGlueOrCenter = true;
            } else {
                candidateX = dragOriginX;
                res.hasVerticalGuide = true;
                res.verticalLineX = dragOriginX + w * 0.5F;
                res.vMinY = 0.0F;
                res.vMaxY = screenH;
                res.verticalIsGlueOrCenter = true;
            }
        }

        float bestDx = snapRadius + 0.001F;
        float snappedX = candidateX;
        boolean foundV = false;
        float vGuideX = 0.0F;
        float vY0 = 0.0F;
        float vY1 = screenH;
        boolean vSpecial = false;

        float bestDy = snapRadius + 0.001F;
        float snappedY = candidateY;
        boolean foundH = false;
        float hGuideY = 0.0F;
        float hX0 = 0.0F;
        float hX1 = screenW;
        boolean hSpecial = false;

        // 1. Axes centraux et marges de l'écran
        float screenCx = screenW * 0.5F;
        float screenCy = screenH * 0.5F;

        if (!lockAxisShift || Math.abs(rawX - dragOriginX) >= Math.abs(rawY - dragOriginY)) {
            float dCenter = Math.abs((candidateX + w * 0.5F) - screenCx);
            if (dCenter < bestDx) {
                bestDx = dCenter;
                snappedX = screenCx - w * 0.5F;
                foundV = true;
                vGuideX = screenCx;
                vY0 = 0.0F;
                vY1 = screenH;
                vSpecial = true;
            }
            float dLeftMargin = Math.abs(candidateX - SCREEN_MARGIN);
            if (dLeftMargin < bestDx) {
                bestDx = dLeftMargin;
                snappedX = SCREEN_MARGIN;
                foundV = true;
                vGuideX = SCREEN_MARGIN;
                vY0 = 0.0F;
                vY1 = screenH;
                vSpecial = false;
            }
            float dRightMargin = Math.abs((candidateX + w) - (screenW - SCREEN_MARGIN));
            if (dRightMargin < bestDx) {
                bestDx = dRightMargin;
                snappedX = screenW - SCREEN_MARGIN - w;
                foundV = true;
                vGuideX = screenW - SCREEN_MARGIN;
                vY0 = 0.0F;
                vY1 = screenH;
                vSpecial = false;
            }
        }

        if (!lockAxisShift || Math.abs(rawY - dragOriginY) > Math.abs(rawX - dragOriginX)) {
            float dCenterY = Math.abs((candidateY + h * 0.5F) - screenCy);
            if (dCenterY < bestDy) {
                bestDy = dCenterY;
                snappedY = screenCy - h * 0.5F;
                foundH = true;
                hGuideY = screenCy;
                hX0 = 0.0F;
                hX1 = screenW;
                hSpecial = true;
            }
            float dTopMargin = Math.abs(candidateY - SCREEN_MARGIN);
            if (dTopMargin < bestDy) {
                bestDy = dTopMargin;
                snappedY = SCREEN_MARGIN;
                foundH = true;
                hGuideY = SCREEN_MARGIN;
                hX0 = 0.0F;
                hX1 = screenW;
                hSpecial = false;
            }
            float dBotMargin = Math.abs((candidateY + h) - (screenH - SCREEN_MARGIN));
            if (dBotMargin < bestDy) {
                bestDy = dBotMargin;
                snappedY = screenH - SCREEN_MARGIN - h;
                foundH = true;
                hGuideY = screenH - SCREEN_MARGIN;
                hX0 = 0.0F;
                hX1 = screenW;
                hSpecial = false;
            }
        }

        // 2. Alignement & Collage magnétique inter-éléments (bord-sur-bord, centre, collage direct & collage gap 3px)
        if (others != null) {
            for (int i = 0; i < others.size(); i++) {
                Box o = others.get(i);
                if (o == null || (draggedId != null && draggedId.equals(o.id))) {
                    continue;
                }
                float ox0 = o.x;
                float ox1 = o.x + o.w;
                float ocx = o.x + o.w * 0.5F;
                float oy0 = o.y;
                float oy1 = o.y + o.h;
                float ocy = o.y + o.h * 0.5F;

                if (!lockAxisShift || Math.abs(rawX - dragOriginX) >= Math.abs(rawY - dragOriginY)) {
                    // Gauche == Gauche
                    float d = Math.abs(candidateX - ox0);
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ox0;
                        foundV = true;
                        vGuideX = ox0;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = false;
                    }
                    // Droite == Droite
                    d = Math.abs((candidateX + w) - ox1);
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ox1 - w;
                        foundV = true;
                        vGuideX = ox1;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = false;
                    }
                    // Centre X == Centre X
                    d = Math.abs((candidateX + w * 0.5F) - ocx);
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ocx - w * 0.5F;
                        foundV = true;
                        vGuideX = ocx;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = true;
                    }
                    // Collage bord gauche sur bord droit (0px et +3px)
                    d = Math.abs(candidateX - ox1);
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ox1;
                        foundV = true;
                        vGuideX = ox1;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = true;
                    }
                    d = Math.abs(candidateX - (ox1 + GLUE_GAP));
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ox1 + GLUE_GAP;
                        foundV = true;
                        vGuideX = ox1 + GLUE_GAP * 0.5F;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = true;
                    }
                    // Collage bord droit sur bord gauche (0px et -3px)
                    d = Math.abs((candidateX + w) - ox0);
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ox0 - w;
                        foundV = true;
                        vGuideX = ox0;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = true;
                    }
                    d = Math.abs((candidateX + w) - (ox0 - GLUE_GAP));
                    if (d < bestDx) {
                        bestDx = d;
                        snappedX = ox0 - GLUE_GAP - w;
                        foundV = true;
                        vGuideX = ox0 - GLUE_GAP * 0.5F;
                        vY0 = Math.min(candidateY, oy0) - 8.0F;
                        vY1 = Math.max(candidateY + h, oy1) + 8.0F;
                        vSpecial = true;
                    }
                }

                if (!lockAxisShift || Math.abs(rawY - dragOriginY) > Math.abs(rawX - dragOriginX)) {
                    // Haut == Haut
                    float d = Math.abs(candidateY - oy0);
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = oy0;
                        foundH = true;
                        hGuideY = oy0;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = false;
                    }
                    // Bas == Bas
                    d = Math.abs((candidateY + h) - oy1);
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = oy1 - h;
                        foundH = true;
                        hGuideY = oy1;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = false;
                    }
                    // Centre Y == Centre Y
                    d = Math.abs((candidateY + h * 0.5F) - ocy);
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = ocy - h * 0.5F;
                        foundH = true;
                        hGuideY = ocy;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = true;
                    }
                    // Collage haut sous bas (0px et +3px)
                    d = Math.abs(candidateY - oy1);
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = oy1;
                        foundH = true;
                        hGuideY = oy1;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = true;
                    }
                    d = Math.abs(candidateY - (oy1 + GLUE_GAP));
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = oy1 + GLUE_GAP;
                        foundH = true;
                        hGuideY = oy1 + GLUE_GAP * 0.5F;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = true;
                    }
                    // Collage bas sur haut (0px et -3px)
                    d = Math.abs((candidateY + h) - oy0);
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = oy0 - h;
                        foundH = true;
                        hGuideY = oy0;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = true;
                    }
                    d = Math.abs((candidateY + h) - (oy0 - GLUE_GAP));
                    if (d < bestDy) {
                        bestDy = d;
                        snappedY = oy0 - GLUE_GAP - h;
                        foundH = true;
                        hGuideY = oy0 - GLUE_GAP * 0.5F;
                        hX0 = Math.min(snappedX, ox0) - 8.0F;
                        hX1 = Math.max(snappedX + w, ox1) + 8.0F;
                        hSpecial = true;
                    }
                }
            }
        }

        res.x = clamp(snappedX, 1.0F, Math.max(1.0F, screenW - w - 1.0F));
        res.y = clamp(snappedY, 1.0F, Math.max(1.0F, screenH - h - 1.0F));

        if (foundV) {
            res.hasVerticalGuide = true;
            res.verticalLineX = vGuideX;
            res.vMinY = Math.max(0.0F, vY0);
            res.vMaxY = Math.min(screenH, vY1);
            res.verticalIsGlueOrCenter = vSpecial;
        }
        if (foundH) {
            res.hasHorizontalGuide = true;
            res.horizontalLineY = hGuideY;
            res.hMinX = Math.max(0.0F, hX0);
            res.hMaxX = Math.min(screenW, hX1);
            res.horizontalIsGlueOrCenter = hSpecial;
        }

        return res;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
