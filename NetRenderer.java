package com.tentnets.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tentnets.TentNetsMod;
import com.tentnets.net.NetEntity;
import com.tentnets.net.NetShape;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Рисует сеть как гладкую сетку треугольников (двусторонняя), плюс крепления:
 * болтик на каждой опорной точке и обмотка верёвкой, если сеть привязана к столбу (забор, стена).
 * Антидроновая — полупрозрачная сетка, маскировочная — непрозрачный камуфляж.
 */
public class NetRenderer extends EntityRenderer<NetEntity> {
    private static final Map<NetEntity.Variant, ResourceLocation> TEX = new EnumMap<>(NetEntity.Variant.class);
    private static final ResourceLocation HARDWARE =
            ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/net_hardware.png");

    static {
        for (NetEntity.Variant v : NetEntity.Variant.values()) {
            TEX.put(v, ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/" + v.id + ".png"));
        }
    }

    /** Сколько повторов текстуры на блок. */
    private static final float ANTI_UV_PER_BLOCK = 1.0F;
    private static final float MASK_UV_PER_BLOCK = 0.5F;

    public NetRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(NetEntity entity) {
        return TEX.get(entity.getVariant());
    }

    @Override
    public void render(NetEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        NetShape shape = entity.getShape();
        if (shape == null) return;

        boolean anti = entity.getKind() == NetEntity.Kind.ANTI;
        ResourceLocation tex = TEX.get(entity.getVariant());
        VertexConsumer vc = buffer.getBuffer(anti
                ? RenderType.entityTranslucent(tex)
                : RenderType.entityCutoutNoCull(tex));
        float uvScale = anti ? ANTI_UV_PER_BLOCK : MASK_UV_PER_BLOCK;

        // Центр сети может оказаться внутри блока (тёмный свет) — подтягиваем минимум.
        int light = LightTexture.pack(
                Math.max(LightTexture.block(packedLight), 4),
                Math.max(LightTexture.sky(packedLight), 8));

        PoseStack.Pose pose = poseStack.last();
        Vec3 origin = entity.position();
        float nx = (float) shape.normal.x;
        float ny = (float) shape.normal.y;
        float nz = (float) shape.normal.z;

        // Режим QUADS: четвёртая вершина дублирует третью.
        NetShape.Mesh mesh = shape.mesh();
        for (int q = 0; q < mesh.triangles(); q++) {
            for (int k = 0; k < 3; k++) {
                vertex(vc, pose, mesh.tri[q * 3 + k], origin,
                        (float) mesh.uv[q * 6 + k * 2] * uvScale, (float) mesh.uv[q * 6 + k * 2 + 1] * uvScale,
                        light, nx, ny, nz);
            }
            vertex(vc, pose, mesh.tri[q * 3 + 2], origin,
                    (float) mesh.uv[q * 6 + 4] * uvScale, (float) mesh.uv[q * 6 + 5] * uvScale,
                    light, nx, ny, nz);
        }

        renderHardware(entity, shape, buffer, pose, origin, light);
    }

    // ---------------------------------------------------------------- крепления

    private void renderHardware(NetEntity entity, NetShape shape, MultiBufferSource buffer, PoseStack.Pose pose,
                                Vec3 origin, int light) {
        VertexConsumer hv = buffer.getBuffer(RenderType.entityCutoutNoCull(HARDWARE));
        Level level = entity.level();

        for (int i = 0; i < shape.verts.length; i++) {
            Vec3 p = shape.verts[i];
            Direction face = NetEntity.anchorDir(shape, i);
            if (face == null) continue;

            // Болтик: плоская шайба на грани блока и шляпка над ней.
            double ax = face.getAxis() == Direction.Axis.X ? 1.0 : 0.0;
            double ay = face.getAxis() == Direction.Axis.Y ? 1.0 : 0.0;
            double az = face.getAxis() == Direction.Axis.Z ? 1.0 : 0.0;
            box(hv, pose, origin, p.x, p.y, p.z,
                    ax > 0 ? 0.02 : 0.07, ay > 0 ? 0.02 : 0.07, az > 0 ? 0.02 : 0.07,
                    0.5F, 0.0F, 1.0F, 1.0F, light);
            box(hv, pose, origin,
                    p.x + face.getStepX() * 0.045, p.y + face.getStepY() * 0.045, p.z + face.getStepZ() * 0.045,
                    ax > 0 ? 0.03 : 0.035, ay > 0 ? 0.03 : 0.035, az > 0 ? 0.03 : 0.035,
                    0.5F, 0.0F, 1.0F, 1.0F, light);

            // Обмотка вокруг столба (забор, стена и т.п.), если сеть привязана к боковой грани.
            if (face.getAxis() != Direction.Axis.Y) {
                BlockPos anchor = NetEntity.anchorBlock(shape, i);
                if (anchor != null) {
                    AABB core = postCore(level, anchor);
                    if (core != null) {
                        wrap(hv, pose, origin, core, p.y, light);
                    }
                }
            }
        }
    }

    /** Ствол столба в мировых координатах или null, если блок — не столб. */
    private static AABB postCore(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return null;

        double half;
        if (state.is(BlockTags.FENCES)) {
            half = 0.125;
        } else if (state.is(BlockTags.WALLS)) {
            half = 0.25;
        } else {
            VoxelShape vs = state.getShape(level, pos);
            if (vs.isEmpty()) return null;
            AABB b = vs.bounds();
            if (b.getXsize() > 0.55 || b.getZsize() > 0.55 || b.getYsize() < 0.5) return null;
            return b.move(pos);
        }
        return new AABB(pos.getX() + 0.5 - half, pos.getY(), pos.getZ() + 0.5 - half,
                pos.getX() + 0.5 + half, pos.getY() + 1.0, pos.getZ() + 0.5 + half);
    }

    /** Два витка верёвки вокруг столба на высоте y. */
    private static void wrap(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin, AABB core, double y, int light) {
        double e = 0.018;
        double x0 = core.minX - e, x1 = core.maxX + e;
        double z0 = core.minZ - e, z1 = core.maxZ + e;
        double yc = Math.max(core.minY + 0.07, Math.min(core.maxY - 0.07, y));
        for (int band = 0; band < 2; band++) {
            double yb = yc + (band == 0 ? -0.04 : 0.04);
            double y0 = yb - 0.02, y1 = yb + 0.02;
            quad(vc, pose, origin, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, light);
            quad(vc, pose, origin, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, light);
            quad(vc, pose, origin, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, light);
            quad(vc, pose, origin, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, light);
        }
    }

    /** Верёвка: левая половина текстуры крепежа. */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3 o,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz,
                             float nx, float ny, float nz, int light) {
        quadUv(vc, pose, o, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, ny, nz, 0.0F, 0.25F, 0.5F, 0.75F, light);
    }

    private static void quadUv(VertexConsumer vc, PoseStack.Pose pose, Vec3 o,
                               double ax, double ay, double az, double bx, double by, double bz,
                               double cx, double cy, double cz, double dx, double dy, double dz,
                               float nx, float ny, float nz, float u0, float v0, float u1, float v1, int light) {
        vertex(vc, pose, new Vec3(ax, ay, az), o, u0, v1, light, nx, ny, nz);
        vertex(vc, pose, new Vec3(bx, by, bz), o, u0, v0, light, nx, ny, nz);
        vertex(vc, pose, new Vec3(cx, cy, cz), o, u1, v0, light, nx, ny, nz);
        vertex(vc, pose, new Vec3(dx, dy, dz), o, u1, v1, light, nx, ny, nz);
    }

    /** Параллелепипед с центром в (cx,cy,cz) и половинными размерами hx,hy,hz. */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, Vec3 o, double cx, double cy, double cz,
                            double hx, double hy, double hz, float u0, float v0, float u1, float v1, int light) {
        double x0 = cx - hx, x1 = cx + hx;
        double y0 = cy - hy, y1 = cy + hy;
        double z0 = cz - hz, z1 = cz + hz;
        quadUv(vc, pose, o, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1, 0, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0, 0, 1, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1, u0, v0, u1, v1, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 origin,
                               float u, float v, int light, float nx, float ny, float nz) {
        vc.addVertex(pose, (float) (p.x - origin.x), (float) (p.y - origin.y), (float) (p.z - origin.z))
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
