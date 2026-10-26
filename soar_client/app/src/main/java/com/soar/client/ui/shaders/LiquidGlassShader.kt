package com.soar.client.ui.shaders

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soar.client.ui.theme.GlassSpecular
import com.soar.client.ui.theme.SoarBorderGlass
import com.soar.client.ui.theme.SoarSurfaceGlass

/**
 * AGSL Elliptical Liquid Glass Droplet Fragment Shader.
 *
 * Physically bounded convex lens:
 *  - Elliptical footprint via float2 lensRadius (radiusX, radiusY) so the droplet can
 *    stretch horizontally / squash vertically while gliding between tabs.
 *  - Radial pull = 0.18 + 0.30 * r^2.4  (max 0.48 at the rim, always < 1.0) so the
 *    sampled UV never crosses the lens center -> icons are magnified, never inverted/smeared.
 *  - Micro chromatic dispersion capped at 1.6% of the radius (only separates existing
 *    bright pixels; there is NO unconditional coloured ring).
 *  - Warm-champagne caustic only where bright icon/border pixels bend against the outer rim.
 *  - Neutral Fresnel rim with diagonal top-left / bottom-right specular reflection.
 */
private const val AGSL_LIQUID_DROPLET_SRC = """
uniform shader content;
uniform float2 resolution;
uniform float2 lensCenter;
uniform float2 lensRadius; // (radiusX, radiusY) of the moving droplet

// Signed Distance Field for a 2D Capsule / Pill centered at resolution * 0.5
float sdPill(float2 p, float2 halfSize, float cornerRadius) {
    float2 q = abs(p) - (halfSize - float2(cornerRadius));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - cornerRadius;
}

half4 main(float2 fragCoord) {
    float2 halfRes = resolution * 0.5;
    float pillRadius = min(halfRes.x, halfRes.y);
    float2 centerRel = fragCoord - halfRes;

    // 1. Pill Slider Track SDF (dPill <= 0 inside the pill)
    float dPill = sdPill(centerRel, halfRes - float2(1.0), pillRadius - 1.0);
    if (dPill > 0.0) {
        return half4(0.0);
    }

    // Distance from the pill's outer border normalized over a 22px glass bevel
    float bevelWidth = min(22.0, pillRadius * 0.65);
    float pillEdgeFactor = clamp(1.0 - (-dPill / bevelWidth), 0.0, 1.0);

    // Compute outward surface normal of the pill bevel
    float2 q = abs(centerRel) - (halfRes - float2(pillRadius));
    float2 pillNormal = (length(max(q, 0.0)) > 0.001)
        ? sign(centerRel) * normalize(max(q, 0.0))
        : sign(centerRel) * float2(step(q.y, q.x), step(q.x, q.y));

    // Pill Track Refraction: bends background email text as it passes behind the pill's curved rim
    float pillRefractPull = pow(pillEdgeFactor, 2.2) * 14.0;
    float2 trackUv = fragCoord - pillNormal * pillRefractPull;

    // Subtle 5-tap frosted glass smoothing + micro-dispersion on the pill track
    float2 pillDisp = pillNormal * (2.2 * pow(pillEdgeFactor, 2.6));
    half4 trackR = content.eval(trackUv - pillDisp);
    half4 trackG = (
        content.eval(trackUv) * 0.4 +
        content.eval(trackUv + float2(1.5, 0.0)) * 0.15 +
        content.eval(trackUv - float2(1.5, 0.0)) * 0.15 +
        content.eval(trackUv + float2(0.0, 1.5)) * 0.15 +
        content.eval(trackUv - float2(0.0, 1.5)) * 0.15
    );
    half4 trackB = content.eval(trackUv + pillDisp);
    half3 trackRgb = half3(trackR.r, trackG.g, trackB.b);

    // Pill Track Specular Fresnel Rim (matching the droplet's liquid glass rim)
    float pillDiagLight = pow(abs(dot(pillNormal, float2(-0.7071, -0.7071))), 1.8);
    float pillRim = smoothstep(0.72, 0.99, pillEdgeFactor);
    float pillInnerGlow = pow(pillEdgeFactor, 3.0) * 0.12;
    trackRgb += half3(0.92, 0.95, 1.0) * (pillRim * (0.20 + 0.45 * pillDiagLight) + pillInnerGlow);

    // 2. Moving Droplet Lens Evaluation
    float2 delta = fragCoord - lensCenter;
    float2 p = delta / max(lensRadius, float2(1.0));
    float r2 = dot(p, p);
    float r = sqrt(r2);

    // Outside the moving droplet: return the refracted Liquid Glass Pill Track + soft droplet shadow
    if (r > 1.0) {
        float shadow = smoothstep(1.18, 1.0, r) * 0.32;
        return half4(trackRgb * (1.0 - shadow), 1.0);
    }

    // Inside the moving droplet: 3D convex lens magnification over both backdrop & icons
    float z = sqrt(max(0.0, 1.0 - r2));
    float edgeCurve = pow(r, 2.4);
    float pull = 0.18 + 0.30 * edgeCurve;
    float2 baseUv = fragCoord - delta * pull;

    float2 dir = (r > 0.001) ? (p / r) : float2(0.0);
    float2 dispOffset = dir * lensRadius * (0.016 * pow(r, 3.2));

    half4 colR = content.eval(baseUv - dispOffset);
    half4 colG = content.eval(baseUv);
    half4 colB = content.eval(baseUv + dispOffset);
    half3 refracted = half3(colR.r, colG.g, colB.b);

    // Subtle warm-champagne caustic ONLY where bright pixels meet the outer rim
    float iconLuma = dot(refracted, half3(0.299, 0.587, 0.114));
    float rimCausticMask = smoothstep(0.76, 0.96, r) * smoothstep(0.35, 0.85, iconLuma);
    refracted += half3(0.22, 0.13, 0.04) * rimCausticMask;

    refracted = mix(refracted, refracted * half3(0.94, 0.96, 1.02), 0.15 * (1.0 - z));

    // Crisp Fresnel rim + diagonal top-left / bottom-right specular reflection on the droplet
    float2 normDir = (r > 0.001) ? normalize(p) : float2(0.0, -1.0);
    float diagLight = pow(abs(dot(normDir, float2(-0.7071, -0.7071))), 2.2);
    float fresnelRim = smoothstep(0.86, 0.995, r);
    float innerSheen = smoothstep(0.55, 0.0, length(p - float2(-0.28, -0.28))) * 0.08;

    half3 specular = half3(0.95, 0.97, 1.0) * (fresnelRim * (0.28 + 0.62 * diagLight) + innerSheen);

    float edgeAlpha = smoothstep(1.0, 0.965, r);
    half3 dropletRgb = clamp(refracted + specular, half3(0.0), half3(1.0));

    return half4(mix(trackRgb, dropletRgb, edgeAlpha), 1.0);
}
"""

/**
 * Static Circular Liquid Glass Shader for standalone circular buttons (e.g. Profile).
 */
private const val AGSL_STATIC_CIRCLE_GLASS_SRC = """
    uniform shader content;
    uniform float2 resolution;
    uniform float radius;

    half4 main(float2 fragCoord) {
        float2 center = resolution * 0.5;
        float2 delta = fragCoord - center;
        float dist = length(delta);
        float r = dist / max(radius, 0.001);

        if (r > 1.0) {
            return half4(0.0);
        }

        float z = sqrt(max(0.0, 1.0 - r * r));
        float2 normDir = (r > 0.001) ? (delta / dist) : float2(0.0, -1.0);

        // Bevel & lens magnification
        float edgeCurve = pow(r, 2.2);
        float pull = 0.16 + 0.28 * edgeCurve;
        float2 uv = fragCoord - delta * pull;

        // Micro dispersion
        float2 dispOffset = normDir * radius * (0.015 * pow(r, 3.0));
        half4 colR = content.eval(uv - dispOffset);
        half4 colG = (
            content.eval(uv) * 0.5 +
            content.eval(uv + float2(1.5, 0.0)) * 0.125 +
            content.eval(uv - float2(1.5, 0.0)) * 0.125 +
            content.eval(uv + float2(0.0, 1.5)) * 0.125 +
            content.eval(uv - float2(0.0, 1.5)) * 0.125
        );
        half4 colB = content.eval(uv + dispOffset);
        half3 rgb = half3(colR.r, colG.g, colB.b);

        // Diagonal specular Fresnel rim
        float diagLight = pow(abs(dot(normDir, float2(-0.7071, -0.7071))), 2.0);
        float fresnel = smoothstep(0.82, 0.99, r);
        float innerSheen = smoothstep(0.55, 0.0, length(delta / radius - float2(-0.28, -0.28))) * 0.08;
        half3 specular = half3(0.95, 0.97, 1.0) * (fresnel * (0.24 + 0.55 * diagLight) + innerSheen);

        float edgeAlpha = smoothstep(1.0, 0.97, r);
        return half4(clamp(rgb + specular, half3(0.0), half3(1.0)), edgeAlpha);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun newLiquidDropletShader(): RuntimeShader = RuntimeShader(AGSL_LIQUID_DROPLET_SRC)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun newStaticCircleShader(): RuntimeShader = RuntimeShader(AGSL_STATIC_CIRCLE_GLASS_SRC)

/**
 * Applies the elliptical AGSL liquid droplet to EVERYTHING drawn inside this layer.
 *
 * IMPORTANT: place this modifier BEFORE `.background()` / `.border()` and the children so the
 * pill fill, the 1dp border and the icons are all recorded into the shader's `content` input.
 *
 * [lensCenter] and [lensRadius] are lambdas so that animated values are read inside the
 * graphicsLayer block: each animation frame only invalidates the layer (no recomposition),
 * and the compiled RuntimeShader is reused — only its uniforms change per frame.
 */
fun Modifier.liquidDropletShader(
    lensCenter: () -> Offset,
    lensRadius: () -> Offset
): Modifier = composed {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { runCatching { newLiquidDropletShader() }.getOrNull() }
        if (shader == null) return@composed this
        this.graphicsLayer {
            if (size.width > 0f && size.height > 0f) {
                try {
                    val center = lensCenter()
                    val radius = lensRadius()
                    shader.setFloatUniform("resolution", size.width, size.height)
                    shader.setFloatUniform("lensCenter", center.x, center.y)
                    shader.setFloatUniform(
                        "lensRadius",
                        radius.x.coerceAtLeast(1f),
                        radius.y.coerceAtLeast(1f)
                    )
                    renderEffect = RenderEffect
                        .createRuntimeShaderEffect(shader, "content")
                        .asComposeRenderEffect()
                } catch (t: Throwable) {
                    // Fall back gracefully to un-refracted content
                    renderEffect = null
                }
            }
        }
    } else {
        this
    }
}

/**
 * Modifier that applies the static circular liquid glass shader.
 * Place BEFORE `.background()` / `.border()` so the fill and rim are refracted too.
 */
fun Modifier.staticCircleGlass(
    radius: Float
): Modifier = composed {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { runCatching { newStaticCircleShader() }.getOrNull() }
        if (shader == null) return@composed this
        this.graphicsLayer {
            if (size.width > 0f && size.height > 0f) {
                try {
                    shader.setFloatUniform("resolution", size.width, size.height)
                    shader.setFloatUniform("radius", radius.coerceAtLeast(1f))
                    renderEffect = RenderEffect
                        .createRuntimeShaderEffect(shader, "content")
                        .asComposeRenderEffect()
                } catch (t: Throwable) {
                    renderEffect = null
                }
            }
        }
    } else {
        this
    }
}

/**
 * Standard card liquid glass modifier for headers and cards.
 */
private const val AGSL_LIQUID_GLASS_SRC = """
    uniform shader image;
    uniform float2 size;
    uniform float cornerRadius;
    uniform float specularIntensity;

    half4 main(float2 coord) {
        half4 original = image.eval(coord);
        
        float2 halfSize = size * 0.5;
        float2 p = abs(coord - halfSize) - (halfSize - float2(cornerRadius));
        float d = length(max(p, 0.0)) + min(max(p.x, p.y), 0.0) - cornerRadius;
        
        float edgeDist = clamp(-d / 12.0, 0.0, 1.0);
        float fresnel = pow(1.0 - edgeDist, 2.5);
        
        half3 specular = half3(0.92, 0.92, 0.94) * (fresnel * specularIntensity);
        
        return half4(original.rgb + specular, original.a);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createLiquidGlassRenderEffect(width: Float, height: Float, cornerRadius: Float): RenderEffect {
    val shader = RuntimeShader(AGSL_LIQUID_GLASS_SRC)
    shader.setFloatUniform("size", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
    shader.setFloatUniform("cornerRadius", cornerRadius)
    shader.setFloatUniform("specularIntensity", 0.45f)

    return RenderEffect.createRuntimeShaderEffect(shader, "image")
}

fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    borderWidth: Dp = 1.dp,
    tintColor: Color = SoarSurfaceGlass
): Modifier = composed {
    this
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    tintColor.copy(alpha = 0.82f),
                    tintColor.copy(alpha = 0.94f)
                )
            ),
            shape = shape
        )
        .border(
            width = borderWidth,
            brush = Brush.linearGradient(
                colors = listOf(
                    SoarBorderGlass.copy(alpha = 0.45f),
                    GlassSpecular,
                    SoarBorderGlass.copy(alpha = 0.15f)
                )
            ),
            shape = shape
        )
        .graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && size.width > 0 && size.height > 0) {
                try {
                    renderEffect = createLiquidGlassRenderEffect(
                        width = size.width,
                        height = size.height,
                        cornerRadius = 24f
                    ).asComposeRenderEffect()
                } catch (t: Throwable) {
                    // Fall back gracefully
                }
            }
        }
}
