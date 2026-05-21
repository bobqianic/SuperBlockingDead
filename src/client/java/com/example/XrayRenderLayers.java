package com.example;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

import java.util.Optional;

final class XrayRenderLayers {
    private static final RenderPipeline XRAY_LINES_PIPELINE = makeXrayLinesPipeline();

    private static final RenderLayer XRAY_LINES_LAYER = RenderLayer.of(
            "headhighlighter_xray_lines",
            256,
            false,
            true,
            XRAY_LINES_PIPELINE,
            RenderLayer.MultiPhaseParameters.builder().build(false)
    );

    private static final RenderPipeline XRAY_GLOW_PIPELINE = makeXrayGlowPipeline();

    private static final RenderLayer XRAY_GLOW_LAYER = RenderLayer.of(
            "headhighlighter_xray_glow",
            256,
            false,
            true,
            XRAY_GLOW_PIPELINE,
            RenderLayer.MultiPhaseParameters.builder().build(false)
    );

    private XrayRenderLayers() {
    }

    static RenderLayer lines() {
        return XRAY_LINES_LAYER;
    }

    static RenderLayer glow() {
        return XRAY_GLOW_LAYER;
    }

    private static RenderPipeline makeXrayLinesPipeline() {
        RenderPipeline base = RenderPipelines.LINES;

        RenderPipeline.Snippet snippet = makeNoDepthSnippet(base);

        return RenderPipeline.builder(new RenderPipeline.Snippet[]{snippet})
                .withLocation(Identifier.of(HeadHighlighterController.MODID, "xray_lines"))
                .build();
    }

    private static RenderPipeline makeXrayGlowPipeline() {
        RenderPipeline base = RenderPipelines.DEBUG_FILLED_BOX;

        RenderPipeline.Snippet snippet = makeNoDepthSnippet(base);

        return RenderPipeline.builder(new RenderPipeline.Snippet[]{snippet})
                .withLocation(Identifier.of(HeadHighlighterController.MODID, "xray_glow"))
                .build();
    }

    private static RenderPipeline.Snippet makeNoDepthSnippet(RenderPipeline base) {
        return new RenderPipeline.Snippet(
                Optional.of(base.getVertexShader()),
                Optional.of(base.getFragmentShader()),
                Optional.of(base.getShaderDefines()),
                Optional.of(base.getSamplers()),
                Optional.of(base.getUniforms()),
                base.getBlendFunction(),
                Optional.of(DepthTestFunction.NO_DEPTH_TEST),
                Optional.of(base.getPolygonMode()),
                Optional.of(base.isCull()),
                Optional.of(base.isWriteColor()),
                Optional.of(base.isWriteAlpha()),
                Optional.of(false),
                Optional.of(base.getColorLogic()),
                Optional.of(base.getVertexFormat()),
                Optional.of(base.getVertexFormatMode())
        );
    }
}
